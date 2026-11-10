package io.github.jpbaking.smsgateway.tls

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Serves HTTPS by terminating TLS and passing the plain bytes to the HTTP server on loopback.
 *
 * Ktor's CIO engine has no TLS support, and the Netty engine would add several megabytes and
 * a second server to keep working on Android. Android's own TLS stack (Conscrypt) does the
 * crypto here; this class only copies bytes, so HTTP keep-alive, chunked bodies and so on
 * work exactly as they do over plain HTTP.
 */
class TlsFrontend(
    private val sslContext: SSLContext,
    private val port: Int,
    private val backendPort: Int,
    private val backendHost: String = "127.0.0.1",
    private val maxConnections: Int = 64,
    /** Gets unexpected errors. Kept out of android.util.Log so this class runs in unit tests. */
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val connections: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private val active = AtomicInteger()

    /** The port actually bound, useful when [port] is 0. */
    val localPort: Int get() = serverSocket?.localPort ?: -1

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val socket = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        socket.reuseAddress = true
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), BACKLOG)
        val pool = Executors.newCachedThreadPool(daemonThreads())
        serverSocket = socket
        executor = pool
        pool.execute { acceptLoop(socket, pool) }
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(connections) { connections.toList() }.forEach { runCatching { it.close() } }
        connections.clear()
        executor?.shutdownNow()
        executor = null
    }

    private fun acceptLoop(server: ServerSocket, pool: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (server.isClosed) return
                onError("accept failed", e)
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (active.incrementAndGet() > maxConnections) {
                active.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            try {
                pool.execute { handle(client as SSLSocket, pool) }
            } catch (e: Exception) {
                active.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: SSLSocket, pool: ExecutorService) {
        connections += client
        var backend: Socket? = null
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.startHandshake()
            client.soTimeout = IDLE_TIMEOUT_MS
            backend = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(backendHost, backendPort), CONNECT_TIMEOUT_MS)
                soTimeout = IDLE_TIMEOUT_MS
            }
            connections += backend
            client.tcpNoDelay = true
            val toClient = backend
            // Response direction on another thread; when the backend is done, the connection is.
            pool.execute {
                try {
                    copy(toClient.getInputStream(), client.getOutputStream())
                } finally {
                    close(client, toClient)
                }
            }
            copy(client.getInputStream(), toClient.getOutputStream())
            // The client stopped sending. Let the backend finish its response, which ends the connection.
            runCatching { toClient.shutdownOutput() }
        } catch (e: Exception) {
            // Handshake failures (e.g. a client that doesn't trust the certificate) land here. Normal.
            close(client, backend)
        } finally {
            active.decrementAndGet()
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // Peer closed or timed out.
        }
    }

    private fun close(vararg sockets: Socket?) {
        for (socket in sockets) {
            if (socket == null) continue
            runCatching { socket.close() }
            connections -= socket
        }
    }

    private fun daemonThreads(): ThreadFactory {
        val count = AtomicInteger()
        return ThreadFactory { r -> Thread(r, "tls-${count.incrementAndGet()}").apply { isDaemon = true } }
    }

    private companion object {
        const val BACKLOG = 50
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val CONNECT_TIMEOUT_MS = 5_000
        const val IDLE_TIMEOUT_MS = 5 * 60_000
    }
}
