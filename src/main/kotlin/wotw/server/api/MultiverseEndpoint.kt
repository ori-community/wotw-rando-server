package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.MultiverseCreationConfig
import wotw.io.messages.protobuf.*
import wotw.server.bingo.BingoBoardGenerator
import wotw.server.bingo.UberStateMap
import wotw.server.sync.multiStates
import wotw.server.sync.worldStateAggregationRegistry
import wotw.server.database.model.*
import wotw.server.exception.ConflictException
import wotw.server.io.handleClientSocket
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.server.util.rezero
import wotw.server.util.then
import wotw.server.util.zerore

class MultiverseEndpoint(server: WotwBackendServer) : Endpoint(server) {
    val logger = logger()
    override fun Route.initRouting() {
        post<UberStateUpdateMessage>("multiverses/{multiverse_id}/{player_id}/state") { message ->
            if (System.getenv("DEV").isNullOrBlank()) {
                throw BadRequestException("Only available in dev mode")
            }

            val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("")
            val playerId = call.parameters["player_id"]?.ifEmpty { null } ?: throw BadRequestException("")

            val result = newSuspendedTransaction {
                val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse not found")
                val world = World.find(multiverseId, playerId) ?: throw NotFoundException("World not found for player")
                val result = server.sync.aggregateState(world, message.uberId, message.value)
                multiverse.updateCompletions(world.universe)
                result
            }

            server.sync.syncStates(multiverseId, playerId, result)
            server.sync.syncMultiverseProgress(multiverseId)
            call.respond(HttpStatusCode.NoContent)
        }
        get("multiverses/{multiverse_id}/worlds/{world_id}") {
            val multiverseId =
                call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")
            val worldId =
                call.parameters["world_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable WorldID")
            val worldInfo = newSuspendedTransaction {
                val multiverse =
                    Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")
                val world = multiverse.worlds.firstOrNull { it.id.value == worldId }
                    ?: throw NotFoundException("World does not exist!")
                server.infoMessagesService.generateWorldInfo(world)
            }
            call.respond(worldInfo)
        }
        get("multiverses/{multiverse_id}") {
            val multiverseId =
                call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")
            call.respond(newSuspendedTransaction {
                val multiverse =
                    Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")
                server.infoMessagesService.generateMultiverseInfoMessage(multiverse)
            })
        }
        authenticate(JWT_AUTH) {
            post("multiverses") {
                wotwPrincipal().require(Scope.MULTIVERSE_CREATE)
                val props = call.receiveOrNull<MultiverseCreationConfig>()
                val multiverse = newSuspendedTransaction {
                    Multiverse.new {
                        if (props?.seedId != null)
                            seed = Seed.findById(props.seedId) ?: throw NotFoundException()
                        if (props?.bingo != null)
                            board = BingoBoardGenerator().generateBoard(props)
                    }
                }
                call.respondText("${multiverse.id.value}", status = HttpStatusCode.Created)
            }
            post("multiverses/{multiverse_id}/{universe_id?}/worlds") {
                val multiverseId =
                    call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("Unparsable MultiverseID")
                val universeId =
                    call.parameters["universe_id"]?.toLongOrNull()
                val multiverseInfo = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.WORLD_CREATE)

                    val multiverse =
                        Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")

                    if (multiverse.spectators.contains(player))
                        throw ConflictException("You cannot join this multiverse because you are spectating")

                    val world =
                        if (universeId != null) {
                            if (multiverse.seed != null)
                                throw ConflictException("Cannot manually create or remove worlds from seed-linked universes")
                            val universe =
                                Universe.findById(universeId) ?: throw NotFoundException("Universe does not exist!")

                            server.connections.toPlayers((multiverse.players - universe.members).map { it.id.value }, multiverseId, false, PrintTextMessage(
                                text = "${player.name} joined this game in another universe", frames = 180, ypos = -2f,
                            ))

                            server.connections.toPlayers(universe.members.map { it.id.value }, multiverseId, false, PrintTextMessage(
                                text = "${player.name} joined a new world in your universe", frames = 180, ypos = -2f,
                            ))

                            World.new(universe, player.name + "'s World")
                        } else {
                            val universe = Universe.new {
                                name = "${player.name}'s Universe"
                                this.multiverse = multiverse
                            }

                            server.connections.toPlayers(multiverse.players.map { it.id.value }, multiverseId, false, PrintTextMessage(
                                text = "${player.name} joined this game in a new universe", frames = 180, ypos = -2f,
                            ))

                            GameState.new {
                                this.multiverse = multiverse
                                this.universe = universe
                                this.uberStateData = UberStateMap()
                            }
                            if (multiverse.seed != null) {
                                val seedFiles =
                                    server.seedGeneratorService.filesForSeed(multiverse.seed?.id.toString() ?: "")
                                val first = seedFiles.firstOrNull()
                                if (first != null) {
                                    val world = World.new(universe, first.nameWithoutExtension, first.nameWithoutExtension)
                                    seedFiles.drop(1).forEach {
                                        World.new(universe, it.nameWithoutExtension, it.nameWithoutExtension)
                                    }
                                    world
                                } else {
                                    throw ConflictException("This seed cannot be attached to online games")
                                }
                            } else
                                World.new(universe, player.name + "'s World")
                        }

                    multiverse.removePlayerFromWorlds(player, world).filter { it != multiverseId }.forEach {
                        server.connections.broadcastMultiverseInfoMessage(it)
                    }
                    world.members = SizedCollection(player)

                    multiverse.refresh(true)

                    server.infoMessagesService.generateMultiverseInfoMessage(multiverse)
                }

                server.connections.toObservers(multiverseId, message = multiverseInfo)

                call.respond(HttpStatusCode.Created)
            }

            post("multiverses/{multiverse_id}/worlds/{world_id}") {
                val multiverseId =
                    call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("Unparsable MultiverseID")
                val worldId =
                    call.parameters["world_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable WorldID")

                val multiverseInfo = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.WORLD_JOIN)

                    val multiverse =
                        Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")

                    if (multiverse.spectators.contains(player)) {
                        throw ConflictException("You cannot join this multiverse because you are spectating")
                    }

                    val world = multiverse.worlds.firstOrNull { it.id.value == worldId }
                        ?: throw NotFoundException("World does not exist!")

                    if (!world.members.contains(player)) {
                        val affectedMultiverseIds = multiverse.removePlayerFromWorlds(player, world)
                        world.members = SizedCollection(world.members + player)

                        affectedMultiverseIds.filter { it != multiverseId }.forEach {
                            server.connections.broadcastMultiverseInfoMessage(it)
                        }

                        server.connections.toPlayers((multiverse.players - world.universe.members).map { it.id.value }, multiverseId, false, PrintTextMessage(
                            text = "${player.name} joined this game in another universe", frames = 180, ypos = -2f,
                        ))

                        server.connections.toPlayers((world.universe.members - world.members).map { it.id.value }, multiverseId, false, PrintTextMessage(
                            text = "${player.name} joined your universe in another world", frames = 180, ypos = -2f,
                        ))

                        server.connections.toPlayers(world.members.map { it.id.value }, multiverseId, false, PrintTextMessage(
                            text = "${player.name} joined your world", frames = 180, ypos = -2f,
                        ))
                    }

                    server.infoMessagesService.generateMultiverseInfoMessage(multiverse)
                }

                server.sync.aggregationStrategies.remove(multiverseId)
                server.connections.toObservers(multiverseId, message = multiverseInfo)

                call.respond(HttpStatusCode.OK)
            }

            post("multiverses/{multiverse_id}/spectators") {
                val multiverseId =
                    call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("Unparsable MultiverseID")

                val (multiverseInfo, playerId) = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.MULTIVERSE_SPECTATE)

                    val multiverse =
                        Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")
                    val affectedMultiverseIds = multiverse.removePlayerFromWorlds(player)

                    if (!multiverse.spectators.contains(player)) {
                        multiverse.spectators = SizedCollection(multiverse.spectators + player)

                        server.connections.toPlayers(multiverse.players.map { it.id.value }, multiverseId, false, PrintTextMessage(
                            text = "${player.name} is now spectating this game", frames = 180, ypos = -2f,
                        ))
                    }

                    affectedMultiverseIds.filter { it != multiverseId }.forEach {
                        server.connections.broadcastMultiverseInfoMessage(it)
                    }

                    server.infoMessagesService.generateMultiverseInfoMessage(multiverse) to player.id.value
                }

                server.connections.setSpectating(multiverseInfo.id, playerId, true)
                server.connections.toObservers(multiverseId, message = multiverseInfo)

                call.respond(HttpStatusCode.Created)
            }
        }

        webSocket("game_sync/") {
            handleClientSocket {
                var playerId = ""
                var worldId = 0L

                afterAuthenticated {
                    playerId = principalOrNull?.userId ?: return@afterAuthenticated this@webSocket.close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session active!")
                    )
                    principalOrNull?.hasScope(Scope.MULTIVERSE_CONNECT) ?: this@webSocket.close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "You are not allowed to connect with these credentials!"
                        )
                    )

                    val (_worldId, multiverseId, worldName, worldMembers, multiverseInfoMessage, multiversePlayerIds) = newSuspendedTransaction {
                        val world = WorldMembership.find {
                            WorldMemberships.playerId eq playerId
                        }.firstOrNull()?.world

                        world?.id?.value then world?.universe?.multiverse?.id?.value then world?.name then world?.members?.map { it.name } then world?.universe?.multiverse?.let {
                            server.infoMessagesService.generateMultiverseInfoMessage(
                                it
                            )
                        } then (world?.universe?.multiverse?.players?.map { it.id.value } ?: emptyList())
                    }

                    if (multiverseId == null || _worldId == null) {
                        logger.info("MultiverseEndpoint: game_sync: Player $playerId is not part of an active multiverse")
                        return@afterAuthenticated this@webSocket.close(
                            CloseReason(CloseReason.Codes.NORMAL, "Player is not part of an active multiverse")
                        )
                    }

                    worldId = _worldId
                    server.connections.registerMultiverseConn(socketConnection, playerId, multiverseId)

                    val initData = newSuspendedTransaction {
                        World.findById(worldId)?.universe?.multiverse?.board?.goals?.flatMap { it.value.keys }
                            ?.map { UberId(it.first, it.second) }
                    }.orEmpty()
                    val userName = newSuspendedTransaction {
                        User.find {
                            Users.id eq playerId
                        }.firstOrNull()?.name
                    } ?: "Mystery User"

                    val states = multiStates()
                        .plus(worldStateAggregationRegistry.getSyncedStates())
                        .plus(initData)  // don't sync new data
                    socketConnection.sendMessage(InitGameSyncMessage(states.map {
                        UberId(zerore(it.group), zerore(it.state))
                    }))

                    var greeting = "$userName - Connected to multiverse $multiverseId"

                    if (worldName != null) {
                        greeting += "\nWorld: $worldName\n" + worldMembers?.joinToString()
                    }

                    socketConnection.sendMessage(PrintTextMessage(text = greeting, frames = 240, ypos = 3f))

                    if (multiverseInfoMessage != null) {
                        socketConnection.sendMessage(multiverseInfoMessage)
                    }

                    // Check if all players are online
                    val allPlayersOnline = multiversePlayerIds.all {
                        server.connections.playerMultiverseConnections[it]?.multiverseId == multiverseId
                    }
                    if (allPlayersOnline && multiversePlayerIds.count() >= 2) {
                        server.connections.toPlayers(multiversePlayerIds, multiverseId, false, PrintTextMessage(
                            text = "All ${multiversePlayerIds.count()} players are connected!", frames = 240, ypos = -2f
                        ))
                    }
                }
                onMessage(UberStateUpdateMessage::class) {
                    if (worldId != 0L && playerId.isNotEmpty()) {
                        updateUberState(worldId, playerId)
                    }
                }
                onMessage(UberStateBatchUpdateMessage::class) {
                    if (worldId != 0L && playerId.isNotEmpty()) {
                        updateUberStates(worldId, playerId)
                    }
                }
                onMessage(PlayerPositionMessage::class) {
                    val targetPlayers = server.populationCache.get(playerId, worldId) - playerId

                    logger.debug(targetPlayers.joinToString(" "))

                    server.connections.toPlayers(
                        targetPlayers,
                        null,
                        true,
                        UpdatePlayerPositionMessage(playerId, x, y)
                    )
                }

                onClose {
                    logger.info("WebSocket for player $playerId disconnected (close, ${closeReason.await()})")
                    if (playerId != "") {
                        server.connections.unregisterMultiverseConn(playerId)
                    }
                }
                onError {
                    logger.info("WebSocket for player $playerId disconnected (error, ${closeReason.await()})")
                    if (playerId != "") {
                        server.connections.unregisterMultiverseConn(playerId)
                    }
                }
            }
        }
    }

    private suspend fun UberStateUpdateMessage.updateUberState(worldId: Long, playerId: String) =
        UberStateBatchUpdateMessage(this).updateUberStates(worldId, playerId)

    private suspend fun UberStateBatchUpdateMessage.updateUberStates(worldId: Long, playerId: String) {
        val updates = updates.map {
            UberId(rezero(it.uberId.group), rezero(it.uberId.state)) to rezero(it.value)
        }.toMap()

        val (results, multiverseId) = newSuspendedTransaction {
            val world = World.findById(worldId) ?: error("Inconsistent multiverse state")
            val result = server.sync.aggregateStates(world, updates) to world.universe.multiverse.id.value
            world.universe.multiverse.updateCompletions(world.universe)
            result
        }

        val pc = server.connections.playerMultiverseConnections[playerId]!!
        if (pc.multiverseId != multiverseId) {
            server.connections.unregisterMultiverseConn(playerId)
            server.connections.registerMultiverseConn(pc.clientConnection, playerId, multiverseId)
        }
        server.sync.syncMultiverseProgress(multiverseId)
        server.sync.syncStates(multiverseId, playerId, results)
    }
}
