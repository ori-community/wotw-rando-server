package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.BingoGenProperties
import wotw.io.messages.GameProperties
import wotw.io.messages.protobuf.BingoData
import wotw.io.messages.protobuf.RequestUpdatesMessage
import wotw.io.messages.protobuf.SyncBoardMessage
import wotw.io.messages.sendMessage
import wotw.server.bingo.BingoBoardGenerator
import wotw.server.database.model.*
import wotw.server.exception.AlreadyExistsException
import wotw.server.io.protocol
import wotw.server.main.WotwBackendServer

class BingoEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        get("bingo/latest/{playerId?}") {
            val boardData = newSuspendedTransaction {
                val player = call.parameters["playerId"]?.toLongOrNull()?.let { User.findById(it) } ?: sessionInfo()
                val game = player.latestBingoGame ?: throw NotFoundException()
                game.board ?: throw NotFoundException()
                val info = game.playerInfo()
                BingoData(game.createSyncableBoard(Team.find(game.id.value, player.id.value)), info)
            }
            call.respond(boardData)
        }
        userboardWebsocket()

        get("bingo/{game_id}") {
            val gameId = call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Cannot parse game_id")
            val spectate = call.request.queryParameters["spectate"] == "true"
            val boardData = newSuspendedTransaction {
                val game = Game.findById(gameId) ?: throw NotFoundException()
                game.board ?: throw NotFoundException()
                val info = game.playerInfo()
                BingoData(game.createSyncableBoard(null, spectate), info)
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
        //FIXME
        post("bingo/{game_id}/players") {
            //FIXME
            val userId = call.receiveOrNull<Long>()
            val gameId = call.parameters["game_id"]?.toLongOrNull() ?: return@post call.respondText(
                "Cannot parse gameID",
                status = HttpStatusCode.BadRequest
            )
            val game = newSuspendedTransaction {
                val user = if (userId != null) {
                    User.findById(userId) ?: throw NotFoundException("user unknown?")
                } else {
                    //FIXME
                    val id = call.sessions.get<UserSession>()?.user ?: throw  NotFoundException("Id unknown?")
                    //FIXME
                    User.findById(id) ?: throw NotFoundException("user unknown??")
                }

                val existing = Team.find(gameId, user.id.value)
                if (existing != null)
                    throw AlreadyExistsException()

                val game = Game.findById(gameId) ?: throw NotFoundException("game not found??")

                Team.new(game, user)
                game.id.value
            }

            server.sync.syncGameProgress(game)

            call.respond(HttpStatusCode.OK)
        }
        observerWebsocket()
    }

    private fun Route.userboardWebsocket() {
        webSocket(path = "/observers/latest/{playerId?}") {
            val playerId = call.parameters["playerId"]?.toLongOrNull() ?: call.sessions.get<UserSession>()?.user
            ?: return@webSocket this.close(
                CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No playerId!")
            )
            server.connections.registerObserverConnection(this@webSocket, null, playerId)
            protocol {
                onClose {
                    server.connections.unregisterObserverConnection(this@webSocket, null, playerId)
                }
                onError {
                    server.connections.unregisterObserverConnection(this@webSocket, null, playerId)
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
            val (game, board) = newSuspendedTransaction {
                val game = Game.findById(gameId)
                game to game?.board
            }
            if (game == null || board == null)
                return@webSocket this.close(
                    CloseReason(
                        CloseReason.Codes.NORMAL,
                        "Requested Bingo-Game does not exist"
                    )
                )

            var playerId = -1L
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

                        val syncBoard = newSuspendedTransaction {
                            game.createSyncableBoard(Team.find(gameId, playerId))
                        }
                        outgoing.sendMessage(SyncBoardMessage(syncBoard, true))
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