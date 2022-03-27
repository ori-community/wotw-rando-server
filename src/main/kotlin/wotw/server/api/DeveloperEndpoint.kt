package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.response.*
import io.ktor.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.admin.RemoteTrackerEndpointDescriptor
import wotw.server.database.model.User
import wotw.server.main.WotwBackendServer

class DeveloperEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(JWT_AUTH) {
            route("dev") {
                get("/caches/population/{player_id}") {
                    requireDeveloper()

                    val playerId = call.parameters["player_id"] ?: throw BadRequestException("player_id required")

                    val populationCacheContent = newSuspendedTransaction {
                        server.populationCache.get(playerId)
                    }

                    call.respond(populationCacheContent)
                }

                get("/remote-trackers") {
                    requireDeveloper()

                    call.respond(newSuspendedTransaction {
                        server.connections.remoteTrackerEndpoints.map {
                            RemoteTrackerEndpointDescriptor(
                                it.key,
                                it.value.broadcasterConnection?.principal?.userId?.let { userId ->
                                    User.findById(userId)?.let { user ->
                                        server.infoMessagesService.generateUserInfo(user)
                                    }
                                },
                                it.value.listeners.map { listener ->
                                    listener.webSocket.call.request.origin.remoteHost
                                },
                                it.value.expires,
                            )
                        }
                    })
                }
            }
        }
    }
}



