package wotw.server.api

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wotw.server.main.WotwBackendServer
import java.time.Instant

class ServerEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        route("server") {
            get("/time") {
                call.respond(Instant.now().toEpochMilli())
            }
        }
    }
}



