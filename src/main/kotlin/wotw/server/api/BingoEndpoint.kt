package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.BingoGenProperties
import wotw.io.messages.GameProperties
import wotw.io.messages.protobuf.BingoData
import wotw.server.bingo.BingoBoardGenerator
import wotw.server.database.model.Game
import wotw.server.database.model.Team
import wotw.server.database.model.User
import wotw.server.io.handleWebsocket
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger

class BingoEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        get("bingo/latest/{playerId?}") {
            val boardData = newSuspendedTransaction {
                val player =
                    call.parameters["playerId"]?.ifEmpty { null }?.let { User.findById(it) } ?: authenticatedUser()
                val game = player.latestBingoGame ?: throw NotFoundException()
                game.board ?: throw NotFoundException()
                val info = game.bingoTeamInfo()
                BingoData(game.createSyncableBoard(Team.find(game.id.value, player.id.value)), info)
            }
            call.respond(boardData)
        }
        userboardWebsocket()

        authenticate(SESSION_AUTH, JWT_AUTH) {
            get("bingo/{game_id}") {
                val gameId = call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Cannot parse game_id")

                val playerIsSpectator = newSuspendedTransaction {
                    val game = Game.findById(gameId)
                    val player = authenticatedUserOrNull()

                    println(player?.id?.value)

                    player != null && game?.spectators?.contains(player) ?: false
                }

                println(playerIsSpectator)

                val boardData = newSuspendedTransaction {
                    val player = authenticatedUserOrNull()
                    val team = player?.let { Team.find(gameId, player.id.value) }

                    val game = Game.findById(gameId) ?: throw NotFoundException()
                    game.board ?: throw NotFoundException()
                    val info = game.bingoTeamInfo()
                    BingoData(game.createSyncableBoard(team, playerIsSpectator), info)
                }
                call.respond(boardData)
            }
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
        webSocket(path = "/observers/latest/") {
            val playerId = call.wotwPrincipalOrNull()?.userId
                ?: return@webSocket this.close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No playerId!")
                )
            if (!call.wotwPrincipal().hasScope(Scope.BOARDS_READ)) return@webSocket this.close(
                CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No read access!")
            )

            handleWebsocket(needsAuthentication = true) {
                afterAuthenticated {
                    server.connections.registerObserverConnection(socketConnection, null, playerId)
                }
                onClose {
                    server.connections.unregisterObserverConnection(socketConnection, null, playerId)
                }
                onError {
                    logger().error(it)
                    server.connections.unregisterObserverConnection(socketConnection, null, playerId)
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

            handleWebsocket(needsAuthentication = true) {
                afterAuthenticated {
                    if (!principal.hasScope(Scope.BOARDS_READ)) return@afterAuthenticated this@webSocket.close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No read access!")
                    )

                    val playerId = principal.userId

                    val (gameExists, playerIsSpectator) = newSuspendedTransaction {
                        val game = Game.findById(gameId)
                        val player = User.findById(playerId)

                        (game != null) to (game?.spectators?.contains(player) ?: false)
                    }

                    if (!gameExists)
                        return@afterAuthenticated this@webSocket.close(
                            CloseReason(
                                CloseReason.Codes.NORMAL,
                                "Requested Game does not exist"
                            )
                        )

                    server.connections.registerObserverConnection(socketConnection, gameId, playerId, playerIsSpectator)
                }
                onClose {
                    server.connections.unregisterAllObserverConnections(socketConnection, gameId)
                }
                onError {
                    server.connections.unregisterAllObserverConnections(socketConnection, gameId)
                }
                onMessage(Any::class) {
                    println("Incoming Message: $this")
                }
            }
        }
    }
}