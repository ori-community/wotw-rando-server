package wotw.server.api

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.util.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.BingoGenProperties
import wotw.io.messages.GameProperties
import wotw.io.messages.protobuf.BingoData
import wotw.io.messages.protobuf.RequestUpdatesMessage
import wotw.server.bingo.BingoBoardGenerator
import wotw.server.database.model.*
import wotw.server.io.protocol
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger

class BingoEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        get("bingo/latest/{playerId?}") {
            val boardData = newSuspendedTransaction {
                val player = call.parameters["playerId"]?.ifEmpty { null }?.let { User.findById(it) } ?: sessionInfo()
                val game = player.latestBingoGame ?: throw NotFoundException()
                game.board ?: throw NotFoundException()
                val info = game.bingoTeamInfo()
                BingoData(game.createSyncableBoard(Team.find(game.id.value, player.id.value)), info)
            }
            call.respond(boardData)
        }
        userboardWebsocket()

        get("bingo/{game_id}") {
            val gameId = call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Cannot parse game_id")
            val spectate = call.request.queryParameters["spectate"] == "true"

            val boardData = newSuspendedTransaction {
                val player = sessionInfoOrNull()
                val team = player?.let { Team.find(gameId, player.id.value) }

                val game = Game.findById(gameId) ?: throw NotFoundException()
                game.board ?: throw NotFoundException()
                val info = game.bingoTeamInfo()
                BingoData(game.createSyncableBoard(team, spectate), info)
            }
            call.respond(boardData)
        }
        post("bingo") {
            val props = call.receiveOrNull<BingoGenProperties>()
            val game = newSuspendedTransaction {
                Game.new {
                    board = BingoBoardGenerator().generateBoard(props)
                    this.props = GameProperties(isCoop = true)
                }
            }
            call.respondText("${game.id.value}", status = HttpStatusCode.Created)
        }

        observerWebsocket()
    }

    private fun Route.userboardWebsocket() {
        webSocket(path = "/observers/latest/{playerId?}") {
            val playerId = call.parameters["playerId"]?.ifEmpty {
                call.sessions.get<UserSession>()?.user
            } ?: return@webSocket this.close(
                CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No playerId!")
            )

            server.connections.registerObserverConnection(this@webSocket, null, playerId)
            protocol {
                onClose {
                    server.connections.unregisterObserverConnection(this@webSocket, null, playerId)
                }
                onError {
                    logger().error(it)
                    server.connections.unregisterObserverConnection(this@webSocket, null, playerId)
                    this@webSocket.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "an error occurred"))
                }
            }
        }
    }

    private fun Route.observerWebsocket() {
        webSocket(path = "/observers/{game_id}") {
            val gameId = call.parameters["game_id"]?.toLongOrNull() ?: return@webSocket this.close(
                CloseReason(
                    CloseReason.Codes.VIOLATED_POLICY,
                    "Game-ID is required"
                )
            )
            val spectate = call.request.queryParameters["spectate"] == "true"
            val gameExists = newSuspendedTransaction {
                Game.findById(gameId) != null
            }
            if (!gameExists)
                return@webSocket this.close(
                    CloseReason(
                        CloseReason.Codes.NORMAL,
                        "Requested Game does not exist"
                    )
                )

            var playerId = ""
            server.connections.registerObserverConnection(this@webSocket, gameId, spectator = spectate)
            protocol {
                onMessage(RequestUpdatesMessage::class) {
                    if(spectate)
                        return@onMessage close(CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "Cannot track individual player progress on a spectating connection"
                        ))
                    if (this.playerId != playerId) {
                        server.connections.unregisterObserverConnection(this@webSocket, gameId, playerId)
                        playerId = this.playerId
                        server.connections.registerObserverConnection(this@webSocket, gameId, playerId)
                    }
                }
                onClose {
                    server.connections.unregisterAllObserverConnections(this@webSocket, gameId)
                }
                onError {
                    server.connections.unregisterAllObserverConnections(this@webSocket, gameId)
                }
                onMessage(Any::class) {
                    println("Incoming Message: $this")
                }
            }
        }
    }
}