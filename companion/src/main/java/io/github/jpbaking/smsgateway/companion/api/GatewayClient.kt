package io.github.jpbaking.smsgateway.companion.api

import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

data class GatewayConfig(
    /** Normalized, without a trailing slash or `/api/v1`. */
    val baseUrl: String,
    val apiKey: String,
    /** SHA-256 fingerprint of a self-signed gateway certificate to trust, or null. */
    val pinnedSha256: String?,
)

/** The gateway answered with an error status. */
class ApiException(val code: Int, message: String) : IOException(message)

/** What the event stream reports, in order. */
sealed interface StreamSignal {
    data object Open : StreamSignal
    data class Event(val id: String?, val type: String?, val data: String) : StreamSignal
}

fun Throwable.isCertificateProblem(): Boolean =
    generateSequence(this) { it.cause }.any { it is CertificateException || it is SSLException && it.message.orEmpty().contains("certificate", true) }

class GatewayClient(val config: GatewayConfig) {
    private val http: OkHttpClient = Tls.apply(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS),
        config.pinnedSha256,
    ).build()

    // The gateway pings every 30 s, so a stream silent for much longer than that is dead.
    private val streamHttp: OkHttpClient = http.newBuilder()
        .readTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun request(path: String) = Request.Builder()
        .url(config.baseUrl + path)
        .header("X-API-Key", config.apiKey)
        .header("Accept", "application/json")

    suspend fun health(): Health = apiJson.decodeFromString(Health.serializer(), call(request("/health").build()))

    suspend fun sims(): List<Sim> =
        apiJson.decodeFromString(ListSerializer(Sim.serializer()), call(request("/api/v1/sims").build()))

    suspend fun messages(limit: Int, offset: Int): MessagePage =
        apiJson.decodeFromString(
            MessagePage.serializer(),
            call(request("/api/v1/messages?limit=$limit&offset=$offset").build()),
        )

    suspend fun send(body: SendRequest): Message {
        val json = apiJson.encodeToString(SendRequest.serializer(), body)
        val req = request("/api/v1/messages").post(json.toRequestBody(JSON)).build()
        return apiJson.decodeFromString(Message.serializer(), call(req))
    }

    /**
     * The gateway's event stream. Completes when the gateway closes it, and fails with
     * [ApiException] for an error status (404 while the stream is turned off on the gateway) or
     * an [IOException] when the connection drops.
     */
    fun events(lastEventId: Long?): Flow<StreamSignal> = callbackFlow {
        val req = request("/api/v1/events")
            .header("Accept", "text/event-stream")
            .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId.toString()) }
            .build()
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(StreamSignal.Open)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySend(StreamSignal.Event(id, type, data))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val error = when {
                    response != null && !response.isSuccessful -> errorFor(response)
                    t != null -> t
                    response != null -> IOException("Not an event stream (${response.header("Content-Type")})")
                    else -> IOException("Event stream failed")
                }
                close(error)
            }
        }
        val source = EventSources.createFactory(streamHttp).newEventSource(req, listener)
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    private suspend fun call(req: Request): String {
        val response = http.newCall(req).await()
        return response.use {
            if (!it.isSuccessful) throw errorFor(it)
            it.body?.string().orEmpty()
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun errorFor(response: Response): ApiException {
            val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
            val message = runCatching { apiJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
            return ApiException(
                response.code,
                message ?: when (response.code) {
                    401 -> "The gateway didn't accept the API key"
                    404 -> "Not found"
                    else -> "The gateway answered ${response.code}"
                },
            )
        }

        /**
         * Connects to [baseUrl] without checking its certificate, only to read the certificate,
         * so the user can compare its fingerprint with the gateway's and choose to trust it.
         */
        suspend fun fetchCertificate(baseUrl: String): X509Certificate {
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(Tls.InspectOnly), SecureRandom()) }
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .sslSocketFactory(context.socketFactory, Tls.InspectOnly)
                .hostnameVerifier { _, _ -> true }
                .build()
            val response = client.newCall(Request.Builder().url("$baseUrl/health").build()).await()
            response.use {
                return it.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                    ?: throw IOException("The gateway didn't present a certificate. Is the URL https://?")
            }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
