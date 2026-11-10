package io.github.jpbaking.smsgateway.server

import io.github.jpbaking.smsgateway.BuildConfig
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.ApiKeyEntity
import io.github.jpbaking.smsgateway.data.Direction
import io.github.jpbaking.smsgateway.data.MessageEntity
import io.github.jpbaking.smsgateway.data.MessageStatus
import io.github.jpbaking.smsgateway.data.SecretBox
import io.github.jpbaking.smsgateway.data.Settings
import io.github.jpbaking.smsgateway.data.StoredHeader
import io.github.jpbaking.smsgateway.data.WebhookEntity
import io.github.jpbaking.smsgateway.webhook.WebhookEvent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

/** The HTTP API, served by Ktor's CIO engine on the phone itself. */
class GatewayServer(private val app: GatewayApp) {

    private var server: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = server != null

    fun start(port: Int) {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }
            .also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }

    private fun Application.module() {
        install(ContentNegotiation) { json(apiJson) }
        install(StatusPages) {
            exception<BadRequestException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "Bad request"))
            }
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, ErrorDto(cause.message ?: "Internal error"))
            }
        }

        routing {
            get("/health") {
                call.respond(HealthDto("ok", BuildConfig.VERSION_NAME))
            }

            route("/api/v1") {
                get("/sims") {
                    authorized() ?: return@get
                    call.respond(app.sims.activeSims())
                }

                post("/messages") {
                    val caller = authorized() ?: return@post
                    val req = call.receive<SendRequest>()
                    if (req.to.isBlank()) return@post badRequest("'to' is required")
                    if (req.body.isEmpty()) return@post badRequest("'body' is required")

                    val sim = app.sims.resolve(req.simSlot, req.subscriptionId)
                    if (sim == null && (req.simSlot != null || req.subscriptionId != null)) {
                        return@post badRequest("Requested SIM is not active. See GET /api/v1/sims.")
                    }
                    if (sim == null && !app.sims.hasPermission()) {
                        return@post badRequest("Phone permission not granted on the device.")
                    }

                    val now = System.currentTimeMillis()
                    val message = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        direction = Direction.OUT,
                        phoneNumber = req.to.trim(),
                        body = req.body,
                        subscriptionId = sim?.subscriptionId ?: -1,
                        simSlot = sim?.slot ?: -1,
                        status = MessageStatus.QUEUED,
                        createdAt = now,
                        updatedAt = now,
                        apiKeyId = caller.id,
                        apiKeyName = caller.name,
                    )
                    app.db.messages().insert(message)
                    app.scope.launch { app.sender.send(message, sim) }
                    call.respond(HttpStatusCode.Accepted, MessageDto.from(message))
                }

                get("/messages") {
                    authorized() ?: return@get
                    val direction = when (call.request.queryParameters["direction"]?.lowercase()) {
                        null, "" -> null
                        "in" -> Direction.IN
                        "out" -> Direction.OUT
                        else -> return@get badRequest("'direction' must be 'in' or 'out'")
                    }
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                    val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                    val items = app.db.messages().list(direction, limit, offset).map(MessageDto::from)
                    call.respond(MessageList(items, limit, offset))
                }

                get("/messages/{id}") {
                    authorized() ?: return@get
                    val message = app.db.messages().get(call.parameters["id"].orEmpty())
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Message not found"))
                    call.respond(MessageDto.from(message))
                }

                get("/webhooks") {
                    authorized() ?: return@get
                    call.respond(app.db.webhooks().all().map(WebhookDto::from))
                }

                post("/webhooks") {
                    authorized() ?: return@post
                    val req = call.receive<CreateWebhookRequest>()
                    val scheme = runCatching { URI(req.url).scheme?.lowercase() }.getOrNull()
                    if (scheme != "http" && scheme != "https") {
                        return@post badRequest("'url' must be an http(s) URL")
                    }
                    val events = req.events ?: WebhookEvent.entries.map { it.wireName }
                    val unknown = events.filter { WebhookEvent.fromWireName(it) == null }
                    if (events.isEmpty() || unknown.isNotEmpty()) {
                        return@post badRequest(
                            "Unknown events: $unknown. Valid: ${WebhookEvent.entries.map { it.wireName }}",
                        )
                    }
                    val headers = req.headers.orEmpty()
                    validateHeaders(headers)?.let { return@post badRequest(it) }
                    val hook = WebhookEntity(
                        id = UUID.randomUUID().toString(),
                        url = req.url,
                        events = events.joinToString(","),
                        secret = SecretBox.encrypt(req.secret?.takeIf { it.isNotBlank() } ?: Settings.randomToken()),
                        createdAt = System.currentTimeMillis(),
                        headers = StoredHeader.encode(
                            headers.map {
                                StoredHeader(
                                    key = it.key.trim(),
                                    value = if (it.isSecret) SecretBox.encrypt(it.value) else it.value,
                                    isSecret = it.isSecret,
                                )
                            },
                        ),
                    )
                    app.db.webhooks().insert(hook)
                    call.respond(HttpStatusCode.Created, WebhookDto.from(hook, revealSecret = true))
                }

                delete("/webhooks/{id}") {
                    authorized() ?: return@delete
                    val removed = app.db.webhooks().delete(call.parameters["id"].orEmpty())
                    if (removed == 0) {
                        call.respond(HttpStatusCode.NotFound, ErrorDto("Webhook not found"))
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }

    /**
     * Accepts `X-API-Key: <key>` or `Authorization: Bearer <key>` and returns the matching key.
     * Responds 401 and returns null otherwise.
     */
    private suspend fun RoutingContext.authorized(): ApiKeyEntity? {
        val provided = call.request.headers["X-API-Key"]
            ?: call.request.headers[HttpHeaders.Authorization]
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring(7)?.trim()
        val key = provided?.takeIf { it.isNotEmpty() }?.let { app.apiKeys.authenticate(it) }
        if (key == null) call.respond(HttpStatusCode.Unauthorized, ErrorDto("Missing or invalid API key"))
        return key
    }

    /** Returns an error message, or null if the headers are fine. */
    private fun validateHeaders(headers: List<HeaderInput>): String? {
        if (headers.size > MAX_HEADERS) return "At most $MAX_HEADERS headers are allowed"
        val seen = mutableSetOf<String>()
        for (h in headers) {
            val key = h.key.trim()
            if (!HEADER_NAME.matches(key)) return "Invalid header name: '${h.key}'"
            val lower = key.lowercase()
            if (lower in RESERVED_HEADERS || lower.startsWith("x-gateway-")) {
                return "Header '$key' is set by the gateway and can't be overridden"
            }
            if (!seen.add(lower)) return "Duplicate header: '$key'"
            if (h.value.any { it == '\r' || it == '\n' }) return "Header '$key' contains a line break"
        }
        return null
    }

    private suspend fun RoutingContext.badRequest(message: String) {
        call.respond(HttpStatusCode.BadRequest, ErrorDto(message))
    }

    private companion object {
        const val MAX_HEADERS = 20

        // RFC 9110 token characters.
        val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        val RESERVED_HEADERS = setOf("content-type", "content-length", "host", "connection", "transfer-encoding")
    }
}
