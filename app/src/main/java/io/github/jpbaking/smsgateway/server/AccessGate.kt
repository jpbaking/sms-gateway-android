package io.github.jpbaking.smsgateway.server

import io.github.jpbaking.smsgateway.net.AddressRanges
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.response.respond
import java.net.InetAddress

/**
 * Answers `403` to every request whose addresses [allows] refuses, before anything else looks at
 * it, the API key included. `remote` is where the connection came from and `local` is the phone's
 * own address it arrived at; either is null when it can't be read.
 */
internal fun Application.restrictCallers(allows: (remote: InetAddress?, local: InetAddress?) -> Boolean) {
    intercept(ApplicationCallPipeline.Plugins) {
        val connection = context.request.local
        val remote = AddressRanges.parse(connection.remoteAddress)
        val local = AddressRanges.parse(connection.localAddress)
        if (!allows(remote, local)) {
            context.respond(HttpStatusCode.Forbidden)
            finish()
        }
    }
}
