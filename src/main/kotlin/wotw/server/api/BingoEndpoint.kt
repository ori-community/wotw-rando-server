package wotw.server.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.websocket.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.BingoData
import wotw.server.database.model.Multiverse
import wotw.server.database.model.User
import wotw.server.io.handleClientSocket
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import java.util.concurrent.CancellationException

class BingoEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        userboardWebsocket()

        authenticate(JWT_AUTH) {
            get("bingo/{multiverse_id}") {
                val multiverseId =
                    call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("Cannot parse multiverse_id")

                val playerIsSpectator = newSuspendedTransaction {
                    val multiverse = Multiverse.findById(multiverseId)
                    val player = authenticatedUserOrNull()

                    player != null && multiverse?.spectators?.contains(player) ?: false
                }

                newSuspendedTransaction {
                    val boardData = newSuspendedTransaction {
                        val player = authenticatedUserOrNull()
                        val currentPlayerWorld = player?.currentWorld

                        val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException()
                        multiverse.board ?: throw NotFoundException()
                        val info = multiverse.bingoUniverseInfo()
                        BingoData(multiverse.createSyncableBoard(currentPlayerWorld?.universe, playerIsSpectator), info)
                    }
                    call.respond(boardData)
                }
            }
        }

        observerWebsocket()
    }

    private fun Route.userboardWebsocket() {
        webSocket(path = "/observers/latest/") {
            val playerId = call.wotwPrincipalOrNull()?.userId
                ?: return@webSocket this.close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No playerId!")
                )
            if (!call.wotwPrincipal().hasScope(Scope.BOARDS_READ)) return@webSocket this.close(
                CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No read access!")
            )

            handleClientSocket {
                afterAuthenticated {
                    server.connections.registerObserverConnection(socketConnection, null, playerId)
                }
                onClose {
                    server.connections.unregisterObserverConnection(socketConnection, null, playerId)
                }
                onError {
                    if (it !is CancellationException) {
                        logger().error(it.message)
                    }
                    server.connections.unregisterObserverConnection(socketConnection, null, playerId)
                    this@webSocket.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "an error occurred"))
                }
            }
        }
    }

    private fun Route.observerWebsocket() {
        webSocket(path = "/observers/{multiverse_id}") {
            val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: return@webSocket this.close(
                CloseReason(
                    CloseReason.Codes.VIOLATED_POLICY,
                    "Multiverse-ID is required"
                )
            )

            handleClientSocket {
                afterAuthenticated {
                    if (!principal.hasScope(Scope.BOARDS_READ)) return@afterAuthenticated this@webSocket.close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No read access!")
                    )

                    val playerId = principal.userId

                    val (multiverseExists, playerIsSpectator) = newSuspendedTransaction {
                        val multiverse = Multiverse.findById(multiverseId)
                        val player = User.findById(playerId)

                        (multiverse != null) to (multiverse?.spectators?.contains(player) ?: false)
                    }

                    if (!multiverseExists)
                        return@afterAuthenticated this@webSocket.close(
                            CloseReason(
                                CloseReason.Codes.NORMAL,
                                "Requested Multiverse does not exist"
                            )
                        )

                    server.connections.registerObserverConnection(
                        socketConnection,
                        multiverseId,
                        playerId,
                        playerIsSpectator
                    )
                }
                onClose {
                    server.connections.unregisterAllObserverConnections(socketConnection, multiverseId)
                }
                onError {
                    server.connections.unregisterAllObserverConnections(socketConnection, multiverseId)
                }
                onMessage(Any::class) {
                    println("Incoming Message: $this")
                }
            }
        }
    }
}