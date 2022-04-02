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
import kotlinx.coroutines.launch
import org.jetbrains.exposed.dao.EntityChange
import org.jetbrains.exposed.dao.EntityHook
import org.jetbrains.exposed.dao.toEntity
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.MultiverseCreationConfig
import wotw.io.messages.protobuf.*
import wotw.server.bingo.BingoBoardGenerator
import wotw.server.bingo.UberStateMap
import wotw.server.database.model.*
import wotw.server.exception.ConflictException
import wotw.server.game.CustomEvent
import wotw.server.game.GameConnectionHandler
import wotw.server.game.handlers.GameHandlerType
import wotw.server.io.handleClientSocket
import wotw.server.main.WotwBackendServer
import wotw.server.util.*

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

                        gameHandlerType = GameHandlerType.HIDE_AND_SEEK
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

                            server.connections.toPlayers(
                                (multiverse.players - universe.members).map { it.id.value },
                                multiverseId,
                                false,
                                makeServerTextMessage(
                                    "${player.name} joined this game in another universe",
                                )
                            )

                            server.connections.toPlayers(
                                universe.members.map { it.id.value }, multiverseId, false, makeServerTextMessage(
                                    "${player.name} joined a new world in your universe",
                                )
                            )

                            World.new(universe, player.name + "'s World")
                        } else {
                            val universe = Universe.new {
                                name = "${player.name}'s Universe"
                                this.multiverse = multiverse
                            }

                            server.connections.toPlayers(
                                multiverse.players.map { it.id.value }, multiverseId, false, makeServerTextMessage(
                                    "${player.name} joined this game in a new universe",
                                )
                            )

                            GameState.new {
                                this.multiverse = multiverse
                                this.universe = universe
                                this.uberStateData = UberStateMap()
                            }

                            if (multiverse.seed != null) {
                                val seedFiles =
                                    server.seedGeneratorService.filesForSeed(multiverse.seed?.id?.toString() ?: "")
                                val first = seedFiles.firstOrNull()
                                if (first != null) {
                                    val world =
                                        World.new(universe, first.nameWithoutExtension, first.nameWithoutExtension)
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

                        server.connections.toPlayers(
                            (multiverse.players - world.universe.members).map { it.id.value },
                            multiverseId,
                            false,
                            makeServerTextMessage(
                                "${player.name} joined this game in another universe",
                            )
                        )

                        server.connections.toPlayers(
                            (world.universe.members - world.members).map { it.id.value },
                            multiverseId,
                            false,
                            makeServerTextMessage(
                                "${player.name} joined your universe in another world",
                            )
                        )

                        server.connections.toPlayers(
                            world.members.map { it.id.value }, multiverseId, false, makeServerTextMessage(
                                "${player.name} joined your world",
                            )
                        )
                    }

                    server.infoMessagesService.generateMultiverseInfoMessage(multiverse)
                }

                server.sync.aggregationStrategiesCache.remove(multiverseId)
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

                        server.connections.toPlayers(
                            multiverse.players.map { it.id.value }, multiverseId, false, makeServerTextMessage(
                                "${player.name} is now spectating this game",
                            )
                        )
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

            post("multiverses/{multiverse_id}/event/{event}") {
                val multiverseId =
                    call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("Unparsable MultiverseID")

                call.parameters["event"]?.let { event ->
                    newSuspendedTransaction {
                        val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse not found")

                        if (!multiverse.members.contains(authenticatedUser())) {
                            throw BadRequestException("You cannot trigger custom events on this multiverse since you are not part of it")
                        }

                        server.gameHandlerRegistry.getHandler(multiverse).onMultiverseEvent(
                            CustomEvent(event),
                        )
                    }

                    call.respond(HttpStatusCode.Created)
                } ?: throw BadRequestException("No event given")
            }
        }

        webSocket("game_sync/") {
            handleClientSocket() {
                var playerId = ""
                var connectionHandler: GameConnectionHandler? = null

                suspend fun setupGameSync() {
                    connectionHandler = GameConnectionHandler(playerId, socketConnection, server)

                    val setupResult = newSuspendedTransaction { connectionHandler!!.setup() }

                    if (setupResult == null) {
                        logger.info("MultiverseEndpoint: game_sync: Player $playerId is not part of an active multiverse")
                        return this@webSocket.close(
                            CloseReason(CloseReason.Codes.NORMAL, "Player is not part of an active multiverse")
                        )
                    }

                    val multiversePlayerIds = newSuspendedTransaction {
                        val world = World.findById(setupResult.worldId)
                        world?.universe?.multiverse?.players?.map { it.id.value } ?: emptyList()
                    }

                    server.connections.registerMultiverseConnection(socketConnection, playerId, setupResult.multiverseId)

                    // Check if all players are online
                    val allPlayersOnline = multiversePlayerIds.all {
                        server.connections.playerMultiverseConnections[it]?.multiverseId == setupResult.multiverseId
                    }
                    if (allPlayersOnline && multiversePlayerIds.count() >= 2) {
                        server.connections.toPlayers(
                            multiversePlayerIds, setupResult.multiverseId, false, makeServerTextMessage(
                                "All ${multiversePlayerIds.count()} players are connected!",
                            )
                        )
                    }
                }

                val entityChangeHandler: (EntityChange) -> Unit = {
                    it.toEntity(WorldMembership.Companion)?.player?.let { player ->
                        if (player.id.value == playerId) {
                            launch {
                                setupGameSync()
                            }
                        }
                    }
                }

                afterAuthenticated {
                    playerId = principal.userId

                    principalOrNull?.hasScope(Scope.MULTIVERSE_CONNECT) ?: this@webSocket.close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "You are not allowed to connect with these credentials!"
                        )
                    )

                    EntityHook.subscribe(entityChangeHandler)

                    setupGameSync()
                }

                onMessage(UberStateUpdateMessage::class) { connectionHandler?.onMessage(this) }
                onMessage(UberStateBatchUpdateMessage::class) { connectionHandler?.onMessage(this) }
                onMessage(PlayerPositionMessage::class) { connectionHandler?.onMessage(this) }

                onClose {
                    logger.info("WebSocket for player $playerId disconnected (close, ${closeReason.await()})")

                    if (playerId != "") {
                        server.connections.unregisterMultiverseConnection(playerId)
                    }

                    EntityHook.unsubscribe(entityChangeHandler)
                }

                onError {
                    logger.info("WebSocket for player $playerId disconnected (error, ${closeReason.await()})")
                    if (playerId != "") {
                        server.connections.unregisterMultiverseConnection(playerId)
                    }

                    EntityHook.unsubscribe(entityChangeHandler)
                }
            }
        }
    }
}
