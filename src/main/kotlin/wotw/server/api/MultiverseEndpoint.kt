package wotw.server.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.MultiverseCreationConfig
import wotw.io.messages.protobuf.*
import wotw.server.bingo.BingoBoardGenerator
import wotw.server.database.model.*
import wotw.server.exception.ConflictException
import wotw.server.exception.ForbiddenException
import wotw.server.game.DebugEvent
import wotw.server.game.GameConnectionHandler
import wotw.server.game.GameDisconnectedEvent
import wotw.server.game.MultiverseEvent
import wotw.server.game.handlers.GameHandlerType
import wotw.server.io.handleClientSocket
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.server.util.makeServerTextMessage

class MultiverseEndpoint(server: WotwBackendServer) : Endpoint(server) {
    val logger = logger()

    override fun Route.initRouting() {
//        post<UberStateUpdateMessage>("multiverses/{multiverse_id}/{player_id}/state") { message ->
//            if (System.getenv("DEV").isNullOrBlank()) {
//                throw BadRequestException("Only available in dev mode")
//            }
//
//            val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("")
//            val playerId = call.parameters["player_id"]?.ifEmpty { null } ?: throw BadRequestException("")
//
//            val result = newSuspendedTransaction {
//                val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse not found")
//                val world = World.find(multiverseId, playerId) ?: throw NotFoundException("World not found for player")
//                val result = server.sync.aggregateState(world, message.uberId, message.value)
//                multiverse.updateCompletions(world.universe)
//                result
//            }
//
//            server.sync.syncStates(playerId, result)
//            server.sync.syncMultiverseProgress(multiverseId)
//            call.respond(HttpStatusCode.NoContent)
//        }
        get("multiverses/{multiverse_id}/worlds/{world_id}") {
            val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")
            val worldId = call.parameters["world_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable WorldID")
            val worldInfo = newSuspendedTransaction {
                val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")
                val world = multiverse.worlds.firstOrNull { it.id.value == worldId } ?: throw NotFoundException("World does not exist!")
                server.infoMessagesService.generateWorldInfo(world)
            }
            call.respond(worldInfo)
        }
        get("multiverses/{multiverse_id}") {
            val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")
            call.respond(newSuspendedTransaction {
                val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")
                server.infoMessagesService.generateMultiverseInfoMessage(multiverse)
            })
        }

        authenticate(JWT_AUTH) {
            post("multiverses") {
                wotwPrincipal().require(Scope.MULTIVERSE_CREATE)
                val props = kotlin.runCatching { call.receiveNullable<MultiverseCreationConfig>() }.getOrNull()

                val multiverse = newSuspendedTransaction {
                    Multiverse.new {
                        if (props?.seedId != null) seed = Seed.findById(props.seedId) ?: throw NotFoundException()
                        if (props?.bingoConfig != null) board = BingoBoardGenerator().generateBoard(props)
                    }
                }
                call.respondText("${multiverse.id.value}", status = HttpStatusCode.Created)
            }

            post("multiverses/{multiverse_id}/{universe_id?}/worlds") {
                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")
                val universeId = call.parameters["universe_id"]?.toLongOrNull()
                val multiverseInfo = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.WORLD_CREATE)

                    val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")

                    if (multiverse.spectators.contains(player)) {
                        throw ConflictException("You cannot join this multiverse because you are spectating")
                    }

                    if (multiverse.locked) {
                        throw ConflictException("You cannot join this multiverse because it is locked")
                    }

                    val handler = server.gameHandlerRegistry.getHandler(multiverse)

                    val universe = universeId?.let { multiverse.universes.firstOrNull { it.id.value == universeId } ?: throw NotFoundException("Universe not found") }

                    if (universe == null) {
                        handler.onPlayerCreateUniverseRequest(player)
                    } else {
                        handler.onPlayerCreateWorldRequest(player, universe)
                    }

                    multiverse.refresh(true)
                    server.infoMessagesService.generateMultiverseInfoMessage(multiverse)
                }

                server.connections.toObservers(multiverseId, message = multiverseInfo)

                call.respond(HttpStatusCode.Created)
            }

            post("multiverses/{multiverse_id}/worlds/{world_id}") {
                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")
                val worldId = call.parameters["world_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable WorldID")

                val multiverseInfo = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.WORLD_JOIN)

                    val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")

                    if (multiverse.spectators.contains(player)) {
                        throw ConflictException("You cannot join this multiverse because you are spectating")
                    }

                    if (multiverse.locked) {
                        throw ConflictException("You cannot join this multiverse because it is locked")
                    }

                    val handler = server.gameHandlerRegistry.getHandler(multiverse)

                    val world = multiverse.worlds.firstOrNull { it.id.value == worldId } ?: throw NotFoundException("World does not exist!")
                    handler.onPlayerJoinWorldRequest(player, world)

                    multiverse.refresh()
                    server.infoMessagesService.generateMultiverseInfoMessage(multiverse)
                }

                server.sync.aggregationStrategiesCache.remove(worldId)
                server.connections.toObservers(multiverseId, message = multiverseInfo)

                call.respond(HttpStatusCode.OK)
            }

            post("multiverses/{multiverse_id}/spectators") {
                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

                val (multiverseInfo, playerId) = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.MULTIVERSE_SPECTATE)

                    val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")

                    if (!server.gameHandlerRegistry.getHandler(multiverse).canSpectateMultiverse(player)) {
                        throw ForbiddenException("You cannot spectate this game because the game handler does not allow it")
                    }

                    multiverse.memberships.firstOrNull { it.user.id == player.id }?.let { worldMembership ->
                        server.multiverseUtil.leaveMultiverse(worldMembership)
                    }

                    if (!multiverse.spectators.contains(player)) {
                        multiverse.spectators = SizedCollection(multiverse.spectators + player)

                        if (!multiverse.locked) {
                            server.connections.toPlayers(
                                multiverse.memberships.map { it.id.value }, makeServerTextMessage(
                                    "${player.name} is now spectating this game",
                                )
                            )
                        }
                    }

                    server.infoMessagesService.generateMultiverseInfoMessage(multiverse) to player.id.value
                }

                server.connections.setSpectating(multiverseInfo.id, playerId, true)
                server.connections.toObservers(multiverseId, message = multiverseInfo)

                call.respond(HttpStatusCode.Created)
            }

            post("multiverses/{multiverse_id}/toggle-lock") {
                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

                val multiverseInfo = newSuspendedTransaction {
                    val player = authenticatedUser()
                    wotwPrincipal().require(Scope.MULTIVERSE_LOCK)

                    val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse does not exist!")

                    if (!multiverse.players.contains(player)) {
                        throw ForbiddenException("You cannot lock/unlock this multiverse since you are not an active player in it")
                    }

                    if (!multiverse.isLockable) {
                        throw ForbiddenException("You cannot lock/unlock this multiverse because it was locked by the server")
                    }

                    multiverse.locked = !multiverse.locked

                    server.connections.toPlayers(
                        multiverse.memberships.map { it.id.value }, makeServerTextMessage(
                            "${player.name} ${if (multiverse.locked) "locked" else "unlocked"} this game",
                        )
                    )

                    server.infoMessagesService.generateMultiverseInfoMessage(multiverse)
                }

                server.connections.toObservers(multiverseId, message = multiverseInfo)

                call.respond(HttpStatusCode.OK)
            }

            post("multiverses/{multiverse_id}/event/{event}") {
                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

                call.parameters["event"]?.let { event ->
                    newSuspendedTransaction {
                        val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse not found")

                        val worldMembership = WorldMembership.find {
                            (WorldMemberships.multiverseId eq multiverseId) and (WorldMemberships.userId eq wotwPrincipal().userId)
                        }.firstOrNull() ?: throw BadRequestException("You cannot trigger custom events on this multiverse since you are not part of it")

                        server.gameHandlerRegistry.getHandler(multiverse).onMultiverseEvent(
                            MultiverseEvent(event, worldMembership),
                        )
                    }

                    call.respond(HttpStatusCode.Created)
                } ?: throw BadRequestException("No event given")
            }

            post("multiverses/{multiverse_id}/debug-event/{event}") {
                requireDeveloper()

                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

                call.parameters["event"]?.let { event ->
                    newSuspendedTransaction {
                        val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse not found")

                        server.gameHandlerRegistry.getHandler(multiverse).onMultiverseEvent(
                            DebugEvent(event),
                        )
                    }

                    call.respond(HttpStatusCode.Created)
                } ?: throw BadRequestException("No event given")
            }

            post("multiverses/{multiverse_id}/duplicate") {
                wotwPrincipal().require(Scope.MULTIVERSE_CREATE)

                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

                val newMultiverse = newSuspendedTransaction {
                    val existingMultiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException("Multiverse not found")

                    val handler = server.gameHandlerRegistry.getHandler(existingMultiverse)

                    if (!handler.canDuplicateMultiverse()) {
                        throw ForbiddenException("You cannot duplicate this Multiverse")
                    }

                    Multiverse.new {
                        this.gameHandlerType = existingMultiverse.gameHandlerType
                        this.seed = existingMultiverse.seed
                        this.board = existingMultiverse.board
                    }
                }

                call.respondText("${newMultiverse.id.value}", status = HttpStatusCode.Created)
            }

            get("multiverses/own") {
                call.respond(
                    newSuspendedTransaction {
                        val user = authenticatedUser()

                        val recentMultiverses = Multiverse.wrapRows(
                            Multiverses
                                .innerJoin(WorldMemberships)
                                .selectAll()
                                .where {
                                    (WorldMemberships.multiverseId eq Multiverses.id) and
                                            (Multiverses.gameHandlerType eq GameHandlerType.NORMAL) and
                                            (WorldMemberships.userId eq user.id)
                                }
                        )

                        recentMultiverses
                            .orderBy(Multiverses.createdAt to SortOrder.DESC)
                            .limit(16)
                            .map(server.infoMessagesService::generateMultiverseMetadataInfoMessage)
                    }
                )
            }
        }

        webSocket("client-websocket/{multiverse_id}/{game_type}") {
            val oriType = when (call.parameters["game_type"]) {
                "wotw" -> ConnectionRegistry.Companion.OriType.WillOfTheWisps
                "bf" -> ConnectionRegistry.Companion.OriType.BlindForest
                else -> throw BadRequestException("Invalid game socket name. Must be 'wotw' or 'bf'.")
            }

            val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

            handleClientSocket {
                var worldMembershipId: Long? = null
                var connectionHandler: GameConnectionHandler? = null

                afterAuthenticated {
                    val playerId = principal.userId

                    principalOrNull?.hasScope(Scope.MULTIVERSE_CONNECT) ?: this@webSocket.close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY, "You are not allowed to connect with these credentials!"
                        )
                    )

                    connectionHandler = GameConnectionHandler(playerId, multiverseId, socketConnection, server)
                    val setupResult = connectionHandler.setup()

                    if (setupResult == null) {
                        socketConnection.sendMessage(
                            makeServerTextMessage(
                                "You are not part of multiverse $multiverseId.",
                            )
                        )

                        this@webSocket.flush()
                        this@webSocket.close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY, "GameConnectionHandler could not be set up"
                            )
                        )
                        return@afterAuthenticated
                    }

                    worldMembershipId = setupResult.worldMembershipId

                    server.connections.registerMultiverseConnection(
                        socketConnection,
                        setupResult.worldMembershipId,
                        oriType,
                    )

                    val handler = server.gameHandlerRegistry.getHandler(setupResult.multiverseId)
                    handler.onGameConnectionSetup(connectionHandler!!, setupResult)
                }

                onMessage(UberStateUpdateMessage::class) { connectionHandler?.onMessage(this) }
                onMessage(UberStateBatchUpdateMessage::class) { connectionHandler?.onMessage(this) }
                onMessage(PlayerPositionMessage::class) { connectionHandler?.onMessage(this) }
                onMessage(PlayerUseCatchingAbilityMessage::class) { connectionHandler?.onMessage(this) }
                onMessage(ReportInGameTimeMessage::class) { connectionHandler?.onMessage(this) }
                onMessage(ReportPlayerRaceReadyMessage::class) { connectionHandler?.onMessage(this) }
                onMessage(SetPlayerSaveGuidMessage::class) { connectionHandler?.onMessage(this) }

                onClose {
                    logger.info("WebSocket for World Membership $worldMembershipId disconnected (close, ${closeReason.await()})")

                    worldMembershipId?.let {
                        connectionHandler?.onMultiverseEvent(GameDisconnectedEvent(it))
                        server.connections.unregisterMultiverseConnection(it)
                    }
                }

                onError {
                    logger.info("WebSocket for World Membership $worldMembershipId disconnected (error, ${closeReason.await()}, $it)")

                    worldMembershipId?.let {
                        connectionHandler?.onMultiverseEvent(GameDisconnectedEvent(it))
                        server.connections.unregisterMultiverseConnection(it)
                    }
                }
            }
        }
    }
}
