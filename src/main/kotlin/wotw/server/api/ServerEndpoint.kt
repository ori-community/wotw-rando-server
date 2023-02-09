package wotw.server.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.main.WotwBackendServer
import wotw.server.util.put
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



