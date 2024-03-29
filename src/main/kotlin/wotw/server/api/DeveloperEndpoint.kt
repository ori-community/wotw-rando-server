package wotw.server.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.ClaimBingoCardRequest
import wotw.io.messages.ImpersonateRequest
import wotw.io.messages.admin.RemoteTrackerEndpointDescriptor
import wotw.server.database.model.BingoCardClaim
import wotw.server.database.model.User
import wotw.server.main.WotwBackendServer
import wotw.server.util.doAfterTransaction
import java.util.*

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

                post<ClaimBingoCardRequest>("/bingo/claim") { request ->
                    requireDeveloper()

                    newSuspendedTransaction {
                        val user = authenticatedUser()
                        val multiverse = user.currentMultiverse ?: throw BadRequestException("You are currently not in a multiverse")
                        val universe = user.currentWorld?.universe ?: throw BadRequestException("You are currently not in a multiverse")
                        val board = multiverse.board ?: throw NotFoundException("The multiverse you are in does not have a bingo board")
                        val claims = multiverse.bingoCardClaims
                        val multiverseId = multiverse.id.value

                        if (
                            claims.none { it.universe.id.value == universe.id.value && it.x == request.x && it.y == request.y } && (
                                !board.config.lockout || claims.none { it.x == request.x && it.y == request.y }
                            )
                        ) {
                            BingoCardClaim.new {
                                this.universe = universe
                                this.multiverse = multiverse
                                this.manual = false
                                this.time = Date().time
                                this.x = request.x
                                this.y = request.y
                            }

                            doAfterTransaction {
                                server.sync.syncMultiverseProgress(multiverseId)
                            }
                        }
                    }

                    call.respond(HttpStatusCode.Created)
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



