package io.github.jpbaking.smsgateway.server

import android.util.Log
import io.github.jpbaking.smsgateway.BuildConfig
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.ApiKeyEntity
import io.github.jpbaking.smsgateway.data.Direction
import io.github.jpbaking.smsgateway.data.MessageEntity
import io.github.jpbaking.smsgateway.data.MessageStatus
import io.github.jpbaking.smsgateway.data.Scope
import io.github.jpbaking.smsgateway.data.SecretBox
import io.github.jpbaking.smsgateway.data.Settings
import io.github.jpbaking.smsgateway.data.StoredHeader
import io.github.jpbaking.smsgateway.data.ShortCodes
import io.github.jpbaking.smsgateway.data.WebhookEntity
import io.github.jpbaking.smsgateway.data.WebhookOrigin
import io.github.jpbaking.smsgateway.net.AccessPolicy
import io.github.jpbaking.smsgateway.webhook.WebhookEvent
import io.ktor.http.ContentType
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
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

/** The HTTP API, served by Ktor's CIO engine on the phone itself. */
class GatewayServer(private val app: GatewayApp) {

    private var server: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = server != null

    /**
     * [host] is "0.0.0.0" to accept connections from the network, or "127.0.0.1" for this phone
     * only. [policy] then decides, per request, whether the caller's address is let in.
     */
    fun start(port: Int, host: String = "0.0.0.0", policy: AccessPolicy = AccessPolicy.OPEN) {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = host) { module(policy) }
            .also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }

    private fun Application.module(policy: AccessPolicy) {
        // Before anything else, including the API key: is this caller's address let in at all?
        restrictCallers(policy::allows)
        install(ContentNegotiation) { json(apiJson) }
        install(StatusPages) {
            exception<BadRequestException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "Bad request"))
            }
            exception<Throwable> { call, cause ->
                // The detail stays on the phone: it can name tables, files or internal state.
                Log.e(TAG, "Request failed", cause)
                call.respond(HttpStatusCode.InternalServerError, ErrorDto("Internal error"))
            }
        }

        routing {
            // Without a key: a bare 200, enough to tell the server is up. Details need a valid key.
            get("/health") {
                if (presentedKey() == null) return@get call.respond(HttpStatusCode.OK)
                authorized() ?: return@get
                call.respond(HealthDto("ok", BuildConfig.VERSION_NAME))
            }

            route("/api/v1") {
                get("/sims") {
                    authorized() ?: return@get
                    call.respond(app.sims.activeSims())
                }

                post("/messages") {
                    val caller = authorized(Scope.SEND) ?: return@post
                    val req = call.receive<SendRequest>()
                    if (req.to.isBlank()) return@post badRequest("'to' is required")
                    if (req.body.isEmpty()) return@post badRequest("'body' is required")
                    if (ShortCodes.isShortCode(req.to) && !caller.has(Scope.SEND_SHORTCODE)) {
                        return@post missingScope(Scope.SEND_SHORTCODE, "Sending to a short code")
                    }

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

                // With `read`: every message. With only `send`: the messages this key sent.
                get("/messages") {
                    val caller = authorized() ?: return@get
                    val ownOnly = !caller.has(Scope.READ)
                    if (ownOnly && !caller.has(Scope.SEND)) return@get missingScope(Scope.READ, "Reading messages")
                    val direction = when (call.request.queryParameters["direction"]?.lowercase()) {
                        null, "" -> null
                        "in" -> Direction.IN
                        "out" -> Direction.OUT
                        else -> return@get badRequest("'direction' must be 'in' or 'out'")
                    }
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                    val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                    val items = when {
                        !ownOnly -> app.db.messages().list(direction, limit, offset)
                        direction == Direction.IN -> return@get missingScope(Scope.READ, "Reading incoming messages")
                        else -> app.db.messages().listSentBy(caller.id, limit, offset)
                    }
                    call.respond(MessageList(items.map(MessageDto::from), limit, offset))
                }

                get("/messages/{id}") {
                    val caller = authorized() ?: return@get
                    val ownOnly = !caller.has(Scope.READ)
                    if (ownOnly && !caller.has(Scope.SEND)) return@get missingScope(Scope.READ, "Reading messages")
                    val message = app.db.messages().get(call.parameters["id"].orEmpty())
                        // A key without `read` sees only what it sent; anything else looks absent.
                        ?.takeIf { !ownOnly || it.apiKeyId == caller.id }
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("Message not found"))
                    call.respond(MessageDto.from(message))
                }

                get("/events") {
                    // Off means not served at all: the same bare 404 as an unknown path, before
                    // the key is even checked.
                    if (!app.events.enabled.value) return@get call.respond(HttpStatusCode.NotFound)
                    val caller = authorized(Scope.EVENTS) ?: return@get
                    val lastId = call.request.headers["Last-Event-ID"] ?: call.request.queryParameters["lastEventId"]
                    val lastEventId = when {
                        lastId.isNullOrBlank() -> null
                        else -> lastId.trim().toLongOrNull()?.takeIf { it >= 0 }
                            ?: return@get badRequest("'Last-Event-ID' must be an event id")
                    }
                    val events = call.request.queryParameters["events"]
                        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                        ?.takeIf { it.isNotEmpty() }
                    val unknown = events.orEmpty().filter { WebhookEvent.fromWireName(it) == null }
                    if (unknown.isNotEmpty()) {
                        return@get badRequest(
                            "Unknown events: $unknown. Valid: ${WebhookEvent.entries.map { it.wireName }}",
                        )
                    }
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    // Asks reverse proxies not to buffer the stream.
                    call.response.header("X-Accel-Buffering", "no")
                    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                        try {
                            app.events.serve(this, lastEventId, events, caller.id) {
                                app.apiKeys.stillAllows(caller, Scope.EVENTS)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // The client went away; nothing to answer.
                        }
                    }
                }

                // A key sees and removes only the webhooks it registered. The phone manages all of them.
                get("/webhooks") {
                    val caller = authorized(Scope.WEBHOOKS) ?: return@get
                    call.respond(app.db.webhooks().byKey(caller.id).map(WebhookDto::from))
                }

                post("/webhooks") {
                    val caller = authorized(Scope.WEBHOOKS) ?: return@post
                    if (app.settings.webhooksPhoneOnly) return@post phoneOnly()
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
                        insecure = req.insecure,
                        createdByKeyId = caller.id,
                        createdByKeyName = caller.name,
                        createdVia = WebhookOrigin.API,
                    )
                    app.db.webhooks().insert(hook)
                    // A new place for incoming SMS to go is worth the owner's attention.
                    runCatching { app.notifier.webhookAdded(caller.name, hook.url) }
                    call.respond(HttpStatusCode.Created, WebhookDto.from(hook, revealSecret = true))
                }

                delete("/webhooks/{id}") {
                    val caller = authorized(Scope.WEBHOOKS) ?: return@delete
                    if (app.settings.webhooksPhoneOnly) return@delete phoneOnly()
                    val removed = app.db.webhooks().deleteOwned(call.parameters["id"].orEmpty(), caller.id)
                    if (removed == 0) {
                        call.respond(HttpStatusCode.NotFound, ErrorDto("Webhook not found"))
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }

    /** The key sent as `X-API-Key: <key>` or `Authorization: Bearer <key>`, or null if there is none. */
    private fun RoutingContext.presentedKey(): String? =
        (
            call.request.headers["X-API-Key"]
                ?: call.request.headers[HttpHeaders.Authorization]
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substring(7)?.trim()
            )?.takeIf { it.isNotEmpty() }

    /**
     * Returns the caller's key if it is valid, enabled and has [scope] (when one is given).
     * Otherwise responds and returns null: 401 for a missing, unknown or disabled key, 403 for a
     * key without the scope.
     */
    private suspend fun RoutingContext.authorized(scope: Scope? = null): ApiKeyEntity? {
        val key = presentedKey()?.let { app.apiKeys.authenticate(it) }
        if (key == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("Missing, invalid or disabled API key"))
            return null
        }
        if (scope != null && !key.has(scope)) {
            missingScope(scope, "This request")
            return null
        }
        return key
    }

    private suspend fun RoutingContext.missingScope(scope: Scope, what: String) {
        call.respond(
            HttpStatusCode.Forbidden,
            ScopeErrorDto("$what needs the '${scope.wire}' scope, which this API key doesn't have.", scope.wire),
        )
    }

    private suspend fun RoutingContext.phoneOnly() {
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorDto("Webhooks are managed on the phone only (Settings > Webhooks in the gateway app)."),
        )
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
        const val TAG = "GatewayServer"
        const val MAX_HEADERS = 20

        // RFC 9110 token characters.
        val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        val RESERVED_HEADERS = setOf("content-type", "content-length", "host", "connection", "transfer-encoding")
    }
}
