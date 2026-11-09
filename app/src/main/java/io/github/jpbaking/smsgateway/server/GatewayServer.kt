package io.github.jpbaking.smsgateway.server

import io.github.jpbaking.smsgateway.BuildConfig
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.Direction
import io.github.jpbaking.smsgateway.data.MessageEntity
import io.github.jpbaking.smsgateway.data.MessageStatus
import io.github.jpbaking.smsgateway.data.Settings
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
import java.security.MessageDigest
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
                    if (!authorized()) return@get
                    call.respond(app.sims.activeSims())
                }

                post("/messages") {
                    if (!authorized()) return@post
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
                    )
                    app.db.messages().insert(message)
                    app.scope.launch { app.sender.send(message, sim) }
                    call.respond(HttpStatusCode.Accepted, MessageDto.from(message))
                }

                get("/messages") {
                    if (!authorized()) return@get
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
                    if (!authorized()) return@get
                    val message = app.db.messages().get(call.parameters["id"].orEmpty())
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Message not found"))
                    call.respond(MessageDto.from(message))
                }

                get("/webhooks") {
                    if (!authorized()) return@get
                    call.respond(app.db.webhooks().all().map(WebhookDto::from))
                }

                post("/webhooks") {
                    if (!authorized()) return@post
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
                    val hook = WebhookEntity(
                        id = UUID.randomUUID().toString(),
                        url = req.url,
                        events = events.joinToString(","),
                        secret = req.secret?.takeIf { it.isNotBlank() } ?: Settings.randomToken(),
                        createdAt = System.currentTimeMillis(),
                    )
                    app.db.webhooks().insert(hook)
                    call.respond(HttpStatusCode.Created, WebhookDto.from(hook))
                }

                delete("/webhooks/{id}") {
                    if (!authorized()) return@delete
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

    /** Accepts `X-API-Key: <key>` or `Authorization: Bearer <key>`. Responds 401 otherwise. */
    private suspend fun RoutingContext.authorized(): Boolean {
        val provided = call.request.headers["X-API-Key"]
            ?: call.request.headers[HttpHeaders.Authorization]
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring(7)?.trim()
        val expected = app.settings.apiKey
        val ok = provided != null &&
            MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())
        if (!ok) call.respond(HttpStatusCode.Unauthorized, ErrorDto("Missing or invalid API key"))
        return ok
    }

    private suspend fun RoutingContext.badRequest(message: String) {
        call.respond(HttpStatusCode.BadRequest, ErrorDto(message))
    }
}
