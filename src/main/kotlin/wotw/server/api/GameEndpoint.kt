package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.close
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.GameProperties
import wotw.io.messages.protobuf.*
import wotw.server.bingo.coopStates
import wotw.server.bingo.multiStates
import wotw.server.database.model.*
import wotw.server.exception.ConflictException
import wotw.server.io.handleWebsocket
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.server.util.rezero
import wotw.server.util.then
import wotw.server.util.zerore
import kotlin.to

class GameEndpoint(server: WotwBackendServer) : Endpoint(server) {
    val logger = logger()
    override fun Route.initRouting() {
        post<UberStateUpdateMessage>("games/{game_id}/{player_id}/state") { message ->
            if (System.getenv("DEV").isNullOrBlank()) {
                throw BadRequestException("Only available in dev mode")
            }

            val gameId = call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("")
            val playerId = call.parameters["player_id"]?.ifEmpty { null } ?: throw BadRequestException("")

            val result = newSuspendedTransaction {
                val game = Game.findById(gameId) ?: throw NotFoundException()
                val team = Team.find(gameId, playerId) ?: throw NotFoundException()
                val state = game.teamStates[team] ?: throw NotFoundException()
                val result = server.sync.aggregateState(state, message.uberId, message.value)
                game.updateCompletions(team)
                result
            }

            server.sync.syncState(gameId, playerId, message.uberId, result)
            server.sync.syncGameProgress(gameId)
            call.respond(HttpStatusCode.NoContent)
        }
        get("games/{game_id}/teams/{team_id}") {
            val gameId =
                call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable GameID")
            val teamId =
                call.parameters["team_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable TeamID")
            val (team, members) = newSuspendedTransaction {
                val game = Game.findById(gameId) ?: throw NotFoundException("Game does not exist!")
                val team = game.teams.firstOrNull { it.id.value == teamId }
                    ?: throw NotFoundException("Team does not exist!")
                team to team.members.map { UserInfo(it.id.value, it.name, it.avatarId) }
            }
            println(members)
            call.respond(TeamInfo(teamId, team.name, members))
        }
        get("games/{game_id}") {
            val gameId =
                call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable GameID")
            call.respond(newSuspendedTransaction {
                val game = Game.findById(gameId) ?: throw NotFoundException("Game does not exist!")
                game.gameInfo
            })
        }
        authenticate(SESSION_AUTH, JWT_AUTH) {
            post("games") {
                wotwPrincipal().require(Scope.GAME_CREATE)
                val propsIn = call.receiveOrNull<GameProperties>() ?: GameProperties()
                val game = newSuspendedTransaction {
                    Game.new {
                        props = propsIn
                    }
                }
                call.respondText("${game.id.value}", status = HttpStatusCode.Created)
            }

            post("games/{game_id}/teams") {
                val gameId =
                    call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable GameID")
                val gameInfo = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.TEAM_CREATE)

                    val game = Game.findById(gameId) ?: throw NotFoundException("Game does not exist!")

                    if (game.spectators.contains(player)) {
                        throw ConflictException("You cannot join this game because you are spectating")
                    }

                    game.removePlayerFromTeams(player)

                    Team.new(game, player)
                    game.gameInfo
                }

                server.connections.toObservers(gameId, message = gameInfo)

                call.respond(HttpStatusCode.Created)
            }

            post("games/{game_id}/teams/{team_id}") {
                val gameId =
                    call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable GameID")
                val teamId =
                    call.parameters["team_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable TeamID")

                val gameInfo = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.TEAM_JOIN)

                    val game = Game.findById(gameId) ?: throw NotFoundException("Game does not exist!")

                    if (game.spectators.contains(player)) {
                        throw ConflictException("You cannot join this game because you are spectating")
                    }

                    game.removePlayerFromTeams(player)

                    val team = game.teams.firstOrNull { it.id.value == teamId }
                        ?: throw NotFoundException("Team does not exist!")
                    team.members = SizedCollection(team.members + player)
                    game.gameInfo
                }

                server.sync.aggregationStrategies.remove(gameId)
                server.connections.toObservers(gameId, message = gameInfo)

                call.respond(HttpStatusCode.OK)
            }

            post("games/{game_id}/spectate") {
                val gameId =
                    call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable GameID")

                val (gameInfo, playerId) = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.GAME_SPECTATE)

                    val game = Game.findById(gameId) ?: throw NotFoundException("Game does not exist!")
                    game.removePlayerFromTeams(player)

                    if (!game.spectators.contains(player)) {
                        game.spectators = SizedCollection(game.spectators + player)
                    }

                    game.gameInfo to player.id.value
                }

                server.connections.setSpectating(gameInfo.id, playerId, true)
                server.connections.toObservers(gameId, message = gameInfo)

                call.respond(HttpStatusCode.Created)
            }

            webSocket("game_sync/") {
                handleWebsocket {
                    var playerId = ""
                    var gameStateId = 0L

                    afterAuthenticated {
                        playerId = principalOrNull?.userId ?: return@afterAuthenticated this@webSocket.close(
                            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session active!")
                        )
                        principalOrNull?.hasScope(Scope.GAME_CONNECTION) ?: this@webSocket.close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY,
                                "You are not allowed to connect with these credentials!"
                            )
                        )

                        val (_gameStateId, gameId, teamName, teamMembers) = newSuspendedTransaction {
                            val team = TeamMembership.find {
                                TeamMemberships.playerId eq playerId
                            }.sortedByDescending { it.id.value }.firstOrNull()?.team

                            team?.game?.teamStates?.get(team)?.id?.value then team?.game?.id?.value then team?.name then team?.members?.map { it.name }
                        }

                        if (gameId == null || _gameStateId == null) {
                            return@afterAuthenticated this@webSocket.close(
                                CloseReason(CloseReason.Codes.NORMAL, "Player is not part of an active game")
                            )
                        }

                        gameStateId = _gameStateId!!
                        server.connections.registerGameConn(socketConnection, playerId!!, gameId)

                        val initData = newSuspendedTransaction {
                            GameState.findById(gameStateId)?.game?.board?.goals?.flatMap { it.value.keys }
                                ?.map { UberId(it.first, it.second) }
                        }.orEmpty()
                        val gameProps = newSuspendedTransaction {
                            GameState.findById(gameStateId)?.game?.props ?: GameProperties()
                        }
                        val user = newSuspendedTransaction {
                            User.find {
                                Users.id eq playerId
                            }.firstOrNull()?.name
                        } ?: "Mystery User"

                        val states = (if (gameProps.isMulti) multiStates() else emptyList())
                            .plus(if (gameProps.isCoop) coopStates() else emptyList())
                            .plus(initData)  // don't sync new data
                        socketConnection.sendMessage(InitGameSyncMessage(states.map {
                            UberId(zerore(it.group), zerore(it.state))
                        }))

                        var greeting = "$user - Connected to game $gameId"

                        if (teamName != null) {
                            greeting += "\nTeam: $teamName\n" + teamMembers?.joinToString()
                        }

                        socketConnection.sendMessage(PrintTextMessage(text = greeting, frames = 240, ypos = 3f))
                    }
                    onMessage(UberStateUpdateMessage::class) {
                        if (gameStateId != 0L && playerId.isNotEmpty()) {
                            updateUberState(gameStateId, playerId)
                        }
                    }
                    onMessage(UberStateBatchUpdateMessage::class) {
                        if (gameStateId != 0L && playerId.isNotEmpty()) {
                            updateUberStates(gameStateId, playerId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun UberStateUpdateMessage.updateUberState(gameStateId: Long, playerId: String) {
        val uberGroup = rezero(uberId.group)
        val uberState = rezero(uberId.state)
        val sentValue = rezero(value)
        val (result, gameId) = newSuspendedTransaction {
            logger.debug("($uberGroup, $uberState) -> $sentValue")
            val playerData = GameState.findById(gameStateId) ?: error("Inconsistent game state")
            val result = server.sync.aggregateState(playerData, UberId(uberGroup, uberState), sentValue) to
                    playerData.game.id.value
            playerData.game.updateCompletions(playerData.team)
            result
        }
        val pc = server.connections.playerGameConnections[playerId]!!
        if (pc.gameId != gameId) {
            server.connections.unregisterGameConn(playerId)
            server.connections.registerGameConn(pc.socketConnection, playerId, gameId)
        }
        server.sync.syncGameProgress(gameId)
        server.sync.syncState(gameId, playerId, UberId(uberGroup, uberState), result)
    }

    private suspend fun UberStateBatchUpdateMessage.updateUberStates(gameStateId: Long, playerId: String) {
        val updates = updates.map {
            UberId(rezero(it.uberId.group), rezero(it.uberId.state)) to if (it.value == -1.0) 0.0 else it.value
        }.toMap()

        val (results, gameId) = newSuspendedTransaction {
            val playerData = GameState.findById(gameStateId) ?: error("Inconsistent game state")
            val result = updates.mapValues { (uberId, value) ->
                server.sync.aggregateState(playerData, uberId, value)
            } to playerData.game.id.value
            playerData.game.updateCompletions(playerData.team)
            result
        }

        val pc = server.connections.playerGameConnections[playerId]!!
        if (pc.gameId != gameId) {
            server.connections.unregisterGameConn(playerId)
            server.connections.registerGameConn(pc.socketConnection, playerId, gameId)
        }
        server.sync.syncGameProgress(gameId)
        server.sync.syncStates(gameId, playerId, results)
    }
}
