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
import io.ktor.sessions.*
import io.ktor.websocket.webSocket
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.GameProperties
import wotw.io.messages.protobuf.*
import wotw.io.messages.sendMessage
import wotw.server.bingo.coopStates
import wotw.server.bingo.multiStates
import wotw.server.database.model.*
import wotw.server.io.protocol
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

                    val existingTeam = Team.find(gameId, player.id.value)
                    if (existingTeam != null) {
                        existingTeam.members = SizedCollection(existingTeam.members.minus(player))

                        if (existingTeam.members.count() == 0L) {
                            existingTeam.delete()
                        }
                    }

                    Team.new(game, player)
                    game.gameInfo
                }

                server.connections.toObservers(gameId) {
                    sendMessage(gameInfo)
                }

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

                    val existingTeam = Team.find(gameId, player.id.value)
                    if (existingTeam != null) {
                        existingTeam.members = SizedCollection(existingTeam.members.minus(player))

                        if (existingTeam.members.count() == 0L) {
                            existingTeam.delete()
                        }
                    }

                    val team = game.teams.firstOrNull { it.id.value == teamId }
                        ?: throw NotFoundException("Team does not exist!")
                    team.members = SizedCollection(team.members + player)
                    game.gameInfo
                }

                server.sync.aggregationStrategies.remove(gameId)
                server.connections.toObservers(gameId) {
                    sendMessage(gameInfo)
                }

                call.respond(HttpStatusCode.OK)
            }

            webSocket("game_sync/") {
                val playerId = call.wotwPrincipalOrNull()?.discordId ?: return@webSocket this.close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session active!")
                )
                if (!call.wotwPrincipal().hasScope(Scope.GAME_CONNECTION))
                    this.close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "You are not allowed to connect with these credentials!"
                        )
                    )

                val (gameStateId, gameId, teamName, teamMembers) = newSuspendedTransaction {
                    val team = TeamMembership.find {
                        TeamMemberships.playerId eq playerId
                    }.sortedByDescending { it.id.value }.firstOrNull()?.team

                    team?.game?.teamStates?.get(team)?.id?.value then team?.game?.id?.value then team?.name then team?.members?.map { it.name }
                }

                if (gameId == null || gameStateId == null) {
                    return@webSocket this.close(
                        CloseReason(CloseReason.Codes.NORMAL, "Player is not part of an active game")
                    )
                }

                server.connections.registerGameConn(this, playerId, gameId)

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
                outgoing.sendMessage(InitGameSyncMessage(states.map {
                    UberId(zerore(it.group), zerore(it.state))
                }))

                var greeting = "$user - Connected to game $gameId"

                if (teamName != null) {
                    greeting += "\nTeam: $teamName\n" + teamMembers?.joinToString()
                }

                outgoing.sendMessage(PrintTextMessage(text = greeting, frames = 240, ypos = 3f))

                protocol {
                    onMessage(UberStateUpdateMessage::class) {
                        updateUberState(gameStateId, playerId)
                    }
                    onMessage(UberStateBatchUpdateMessage::class) {
                        updateUberStates(gameStateId, playerId)
                    }
                }
            }
        }
    }

    private suspend fun UberStateUpdateMessage.updateUberState(gameStateId: Long, playerId: String) {
        val uberGroup = rezero(uberId.group)
        val uberState = rezero(uberId.state)
        val sentValue = rezero(value)
        val (result, game) = newSuspendedTransaction {
            logger.debug("($uberGroup, $uberState) -> $sentValue")
            val playerData = GameState.findById(gameStateId) ?: error("Inconsistent game state")
            val result = server.sync.aggregateState(playerData, UberId(uberGroup, uberState), sentValue) to
                    playerData.game.id.value
            playerData.game.updateCompletions(playerData.team)
            result
        }
        val pc = server.connections.playerGameConnections[playerId]!!
        if (pc.gameId != game) {
            server.connections.unregisterGameConn(playerId)
            server.connections.registerGameConn(pc.socket, playerId, game)
        }
        server.sync.syncGameProgress(game)
        server.sync.syncState(game, playerId, UberId(uberGroup, uberState), result)
    }

    private suspend fun UberStateBatchUpdateMessage.updateUberStates(gameStateId: Long, playerId: String) {
        val updates = updates.map {
            UberId(rezero(it.uberId.group), rezero(it.uberId.state)) to if (it.value == -1.0) 0.0 else it.value
        }.toMap()

        val (results, game) = newSuspendedTransaction {
            val playerData = GameState.findById(gameStateId) ?: error("Inconsistent game state")
            val result = updates.mapValues { (uberId, value) ->
                server.sync.aggregateState(playerData, uberId, value)
            } to playerData.game.id.value
            playerData.game.updateCompletions(playerData.team)
            result
        }

        val pc = server.connections.playerGameConnections[playerId]!!
        if (pc.gameId != game) {
            server.connections.unregisterGameConn(playerId)
            server.connections.registerGameConn(pc.socket, playerId, game)
        }
        server.sync.syncGameProgress(game)
        server.sync.syncStates(game, playerId, results)
    }
}
