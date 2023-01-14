package wotw.server.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
                        server.playerEnvironmentCache.get(playerId)
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

                get("/handlers/{multiverse_id}/state") {
                    requireDeveloper()

                    val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("multiverse_id required")

                    val handler = server.gameHandlerRegistry.getHandler(multiverseId)
                    var state = handler.serializeState() ?: "{}"

                    handler.getAdditionalDebugInformation()?.let { debugInfo ->
                        state += "\n\n$debugInfo"
                    }

                    call.respondText(state, ContentType("text", "plain"))
                }
            }
        }
    }
}



