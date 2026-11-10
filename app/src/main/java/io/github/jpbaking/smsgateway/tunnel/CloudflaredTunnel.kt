package io.github.jpbaking.smsgateway.tunnel

import android.content.Context
import android.util.Log
import io.github.jpbaking.smsgateway.data.Settings
import io.github.jpbaking.smsgateway.data.TunnelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface TunnelState {
    data object Off : TunnelState
    data object NotBundled : TunnelState
    data object MissingToken : TunnelState
    data object WaitingForNetwork : TunnelState
    data object Connecting : TunnelState
    data class Connected(val connections: Int) : TunnelState
    data class Reconnecting(val reason: String) : TunnelState
}

/**
 * Runs the bundled cloudflared binary as a child process and keeps it connected.
 *
 * - If cloudflared exits or crashes, it's restarted after 2s, 4s, 8s, ... (up to a minute).
 * - When the phone's network comes back or changes (Wi-Fi to mobile data and back), cloudflared
 *   is restarted right away instead of waiting out its own retry backoff. For quick tunnels a
 *   restart means a new URL, so there it first gets [QUICK_RECONNECT_GRACE_MS] to reconnect by itself.
 */
class CloudflaredTunnel(private val context: Context, private val settings: Settings) {

    // A bug in the tunnel code shouldn't take the SMS gateway down with it: log it instead.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Tunnel failed", e)
            appendLog("Tunnel error: $e")
            _state.value = TunnelState.Reconnecting("internal error, turn the tunnel off and on")
        },
    )
    private val lock = Mutex()
    private var job: Job? = null
    private var port = Settings.DEFAULT_PORT

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    /** The trycloudflare.com URL of the current quick tunnel, or null. */
    private val _quickUrl = MutableStateFlow<String?>(null)
    val quickUrl: StateFlow<String?> = _quickUrl.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    val binary: File get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
    val isBundled: Boolean get() = binary.exists()

    @Volatile
    private var serverRunning = false

    @Volatile
    private var lastRegisteredAt = 0L

    /** Called by the gateway service when the HTTP server starts or stops. */
    fun setServerRunning(isRunning: Boolean, port: Int = this.port) {
        serverRunning = isRunning
        this.port = port
        reload()
    }

    /** Re-reads the tunnel settings and (re)starts or stops the tunnel to match them. */
    fun reload() {
        scope.launch {
            lock.withLock {
                job?.cancelAndJoin()
                job = null
                when {
                    !serverRunning || !settings.tunnelEnabled -> _state.value = TunnelState.Off
                    !isBundled -> _state.value = TunnelState.NotBundled
                    settings.tunnelMode == TunnelMode.TOKEN && settings.tunnelToken.isNullOrBlank() ->
                        _state.value = TunnelState.MissingToken
                    else -> {
                        val mode = settings.tunnelMode
                        val token = settings.tunnelToken
                        val tunnelPort = port
                        _state.value = TunnelState.Connecting
                        job = scope.launch { supervise(mode, token, tunnelPort) }
                    }
                }
            }
        }
    }

    private suspend fun supervise(mode: TunnelMode, token: String?, port: Int) {
        val watcher = NetworkWatcher(context)
        watcher.start()
        val restarts = Channel<String>(Channel.CONFLATED)
        try {
            killStaleProcesses()
            coroutineScope {
                // React to the network coming back or switching, without waiting for cloudflared.
                launch {
                    var previous = watcher.network.value
                    watcher.network.collect { network ->
                        if (network == null) {
                            appendLog("Network lost")
                            _state.value = TunnelState.WaitingForNetwork
                        } else if (network != previous) {
                            appendLog("Network available")
                            if (mode == TunnelMode.TOKEN) {
                                restarts.trySend("network changed")
                            } else {
                                // A restart would give the quick tunnel a new URL, so let it try first.
                                val changedAt = System.currentTimeMillis()
                                launch {
                                    delay(QUICK_RECONNECT_GRACE_MS)
                                    if (lastRegisteredAt < changedAt) restarts.trySend("network changed")
                                }
                            }
                        }
                        previous = network
                    }
                }
                runLoop(mode, token, port, watcher, restarts)
            }
        } finally {
            watcher.stop()
            _quickUrl.value = null
        }
    }

    private suspend fun runLoop(
        mode: TunnelMode,
        token: String?,
        port: Int,
        watcher: NetworkWatcher,
        restarts: Channel<String>,
    ) = coroutineScope {
        var failures = 0
        while (isActive) {
            if (watcher.network.value == null) {
                _state.value = TunnelState.WaitingForNetwork
                watcher.network.first { it != null }
            }
            restarts.tryReceive() // anything asked for before this start is covered by it
            _state.value = TunnelState.Connecting
            val process = try {
                spawn(mode, token, port)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start cloudflared", e)
                appendLog("Could not start cloudflared: ${e.message}")
                failures++
                _state.value = TunnelState.Reconnecting("could not start cloudflared")
                waitBeforeRestart(failures, restarts)
                continue
            }

            var connectedThisRun = false
            var lastError: String? = null
            val reader = launch(Dispatchers.IO) {
                val connections = mutableSetOf<Int>()
                // Stopping cloudflared closes its output stream under this reader, so a read
                // can fail with "Stream closed". That just means the process is gone.
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            appendLog(line)
                            when (val event = TunnelLog.parse(line)) {
                                is LogEvent.Registered -> {
                                    connections += event.connIndex
                                    connectedThisRun = true
                                    lastRegisteredAt = System.currentTimeMillis()
                                    _state.value = TunnelState.Connected(connections.size)
                                }
                                is LogEvent.Dropped -> {
                                    event.connIndex?.let { connections -= it }
                                    _state.value = if (connections.isEmpty()) {
                                        TunnelState.Reconnecting("connection to Cloudflare lost")
                                    } else {
                                        TunnelState.Connected(connections.size)
                                    }
                                }
                                is LogEvent.QuickTunnelUrl -> _quickUrl.value = event.url
                                is LogEvent.Error -> lastError = event.message
                                null -> Unit
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "cloudflared output closed: ${e.message}")
                }
            }
            val exit = async(Dispatchers.IO) { process.waitFor() }

            val restartReason: String? = try {
                select<String?> {
                    exit.onAwait { null }
                    restarts.onReceive { it }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { kill(process) }
                throw e
            }

            if (restartReason != null) {
                appendLog("Restarting cloudflared: $restartReason")
                _state.value = TunnelState.Reconnecting(restartReason)
                withContext(NonCancellable) { kill(process) }
                exit.await()
                reader.join()
                failures = 0
                continue
            }

            reader.join()
            appendLog("cloudflared exited with code ${exit.await()}")
            failures = if (connectedThisRun) 1 else failures + 1
            _state.value = TunnelState.Reconnecting(
                "cloudflared stopped" + (lastError?.let { ": $it" } ?: "") +
                    ". Restarting in ${restartDelayMillis(failures) / 1000}s",
            )
            waitBeforeRestart(failures, restarts)
        }
    }

    /** Waits out the backoff, but restarts right away if the network changes meanwhile. */
    private suspend fun waitBeforeRestart(failures: Int, restarts: Channel<String>) {
        withTimeoutOrNull(restartDelayMillis(failures)) { restarts.receive() }
    }

    private fun spawn(mode: TunnelMode, token: String?, port: Int): Process {
        val home = File(context.filesDir, "cloudflared").apply { mkdirs() }
        val args = mutableListOf(binary.path, "tunnel", "--no-autoupdate", "--grace-period", "1s")
        val edges = EdgeAddresses.resolve()
        if (edges.isEmpty()) {
            appendLog("Could not look up Cloudflare's tunnel servers; letting cloudflared try")
        } else {
            edges.forEach { args += listOf("--edge", it) }
        }
        when (mode) {
            TunnelMode.TOKEN -> args += "run"
            TunnelMode.QUICK -> args += listOf("--url", "http://127.0.0.1:$port")
        }
        _quickUrl.value = null
        appendLog("Starting cloudflared (${if (mode == TunnelMode.TOKEN) "token" else "quick tunnel"})")
        return ProcessBuilder(args)
            .directory(home)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = home.path
                environment()["TMPDIR"] = context.cacheDir.path
                // Passed via the environment so it doesn't show up in the process list.
                if (mode == TunnelMode.TOKEN && token != null) environment()["TUNNEL_TOKEN"] = token
            }
            .start()
    }

    private fun kill(process: Process) {
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }

    /** cloudflared processes left behind if the app was killed without a chance to stop them. */
    private fun killStaleProcesses() {
        val me = android.os.Process.myPid()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (cmdline.startsWith(binary.path)) {
                Log.w(TAG, "Killing leftover cloudflared process $pid")
                android.os.Process.killProcess(pid)
            }
        }
    }

    private fun appendLog(line: String) {
        Log.i(TAG, line)
        _log.update { (it + line).takeLast(LOG_LINES) }
    }

    companion object {
        private const val TAG = "CloudflaredTunnel"
        const val BINARY_NAME = "libcloudflared.so"
        private const val LOG_LINES = 200
        const val QUICK_RECONNECT_GRACE_MS = 15_000L
    }
}
