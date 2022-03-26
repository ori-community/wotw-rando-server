package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.response.*
import io.ktor.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.admin.PopulationCacheContent
import wotw.io.messages.admin.RemoteTrackerEndpointDescriptor
import wotw.io.messages.protobuf.UserInfo
import wotw.server.database.model.User
import wotw.server.main.WotwBackendServer

class AdminEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(JWT_AUTH) {
            route("admin") {
                get("/caches/population/{world_id}/{player_id}") {
                    requireAdmin()

                    val worldId = call.parameters["world_id"]?.toLong() ?: throw BadRequestException("world_id required")
                    val playerId = call.parameters["player_id"] ?: throw BadRequestException("player_id required")

                    val populationCacheContent = newSuspendedTransaction {
                        PopulationCacheContent(
                            playerId,
                            worldId,
                            server.populationCache.get(playerId, worldId).mapNotNull {
                                User.findById(id)?.let { user ->
                                    server.infoMessagesService.generateUserInfo(user)
                                }
                            }
                        )
                    }

                    call.respond(populationCacheContent)
                }

                get("/remote-trackers") {
                    requireAdmin()

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



