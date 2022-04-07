package wotw.server.game.handlers

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.json
import wotw.io.messages.protobuf.*
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.database.model.Multiverse
import wotw.server.database.model.World
import wotw.server.game.*
import wotw.server.main.WotwBackendServer
import wotw.server.sync.normalWorldSyncAggregationStrategy
import wotw.server.util.Every
import wotw.server.util.Scheduler
import wotw.server.util.logger
import java.util.concurrent.TimeUnit
import kotlin.math.pow


@Serializable
data class HideAndSeekGameHandlerState(
    var started: Boolean = false,
    var catchPhase: Boolean = false,
    var secondsUntilCatchPhase: Int = 15,
    var gameSecondsElapsed: Int = 0,
    var seekerWorlds: MutableMap<Long, SeekerWorldInfo> = mutableMapOf(),
)

@Serializable
data class SeekerWorldInfo(
    @ProtoNumber(1) val worldId: Long,
    @ProtoNumber(2) val radius: Float,
    @ProtoNumber(3) val cooldown: Float,
)

@Serializable
data class HideAndSeekGameHandlerClientInfo(
    @ProtoNumber(1) val seekerWorldInfos: List<SeekerWorldInfo>,
)

enum class PlayerType {
    Hider,
    Seeker,
}

data class PlayerInfo(
    var type: PlayerType,
    var position: Vector2 = Vector2(0f, 0f),
) {
    override fun toString(): String {
        return "$type at $position"
    }
}

class HideAndSeekGameHandler(
    multiverseId: Long,
    server: WotwBackendServer,
) : GameHandler<HideAndSeekGameHandlerClientInfo>(multiverseId, server) {
    private var state = HideAndSeekGameHandlerState()
    private val playerInfos = mutableMapOf<PlayerId, PlayerInfo>()

    private val scheduler = Scheduler {
        state.apply {
            if (started) {
                if (!catchPhase) {
                    secondsUntilCatchPhase--

                    var message: PrintTextMessage? = null

                    if (secondsUntilCatchPhase == 0) {
                        catchPhase = true

                        message = PrintTextMessage(
                            "<s_2>GO!</>",
                            Vector2(0f, 1f),
                            0,
                            3f,
                            PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
                            queue = "hide_and_seek",
                        )
                    } else if (secondsUntilCatchPhase <= 10) {
                        message = PrintTextMessage(
                            "Catching starts in $secondsUntilCatchPhase seconds!",
                            Vector2(0f, -0.2f),
                            0,
                            3f,
                            PrintTextMessage.SCREEN_POSITION_TOP_CENTER,
                            withBox = false,
                            withSound = false,
                            queue = "hide_and_seek",
                        )
                    }

                    message?.let {
                        server.connections.toPlayers(playerInfos.keys, it)
                    }
                } else {
                    gameSecondsElapsed++
                }
            }
        }
    }

    override fun isDisposable(): Boolean {
        return playerInfos.keys.isEmpty()
    }

    override suspend fun getAdditionalDebugInformation(): String {
        var debugInfo = ""

        debugInfo += "Player Infos:"
        playerInfos.forEach { (playerId, playerInfo) ->
            debugInfo += "\n  - $playerId: $playerInfo"
        }

        return debugInfo
    }

    override fun start() {
        messageEventBus.register(this, PlayerPositionMessage::class) { message, playerId ->
            playerInfos[playerId]?.let { playerInfo ->
                playerInfo.position = Vector2(message.x, message.y)

                val cache = server.populationCache.get(playerId)

                val targetPlayers = if (state.catchPhase) {
                    cache.universeMemberIds // Everyone can see everyone else
                } else if (playerInfo.type == PlayerType.Seeker) {
                    // Seekers can be seen by everyone else
                    cache.universeMemberIds
                } else {
                    // Hiders can't be seen before the catch phase
                    emptySet()
                }

                server.connections.toPlayers(
                    targetPlayers,
                    UpdatePlayerPositionMessage(playerId, message.x, message.y),
                    unreliable = true,
                )
            }
        }

        messageEventBus.register(this, PlayerUseCatchingAbilityMessage::class) { message, playerId ->
            val cache = server.populationCache.get(playerId)

            state.seekerWorlds[cache.worldId]?.let { seekerWorldInfo ->
                server.connections.toPlayers(
                    playerInfos.keys - playerId,
                    PlayerUsedCatchingAbilityMessage(playerId),
                )

                val caughtPlayers = mutableSetOf<PlayerId>()

                playerInfos[playerId]?.let { seekerInfo ->
                    playerInfos
                        .filterValues { it.type == PlayerType.Hider }
                        .forEach { (playerId, hiderInfo) ->
                            if (seekerInfo.position.distanceSquaredTo(hiderInfo.position) < seekerWorldInfo.radius.pow(2)) {
                                caughtPlayers.add(playerId)
                            }
                        }
                }

                for (caughtPlayer in caughtPlayers) {
                    server.connections.toPlayers(
                        playerInfos.keys,
                        PlayerCaughtMessage(caughtPlayer),
                    )
                }
            }
        }

        messageEventBus.register(this, UberStateUpdateMessage::class) { message, playerId ->
            logger().info("GETTING CACHE")
            server.populationCache.getOrNull(playerId)?.worldId?.let { worldId ->
                logger().info("WORLDID = $worldId")
                updateUberState(message, worldId, playerId)

//                if (
//                    pickupIds.containsValue(message.uberId) && // It's a pickup
//                    state.seekerWorlds.containsKey(worldId) // We are a seeker
//                ) {
//                    server.connections.toPlayers(
//                        listOf(playerId),
//                        PrintTextMessage(
//                            "?????????",
//                            (playerInfos[playerId]?.position ?: Vector2(0f, 0f)) + Vector2(0f, -1f),
//                            time = 3f,
//                            useInGameCoordinates = true,
//                        ),
//                    )
//                }
            }
        }

        messageEventBus.register(this, UberStateBatchUpdateMessage::class) { message, playerId ->
            server.populationCache.getOrNull(playerId)?.worldId?.let { worldId ->
                batchUpdateUberStates(message, worldId, playerId)
            }
        }

        multiverseEventBus.register(this, CustomEvent::class) { message ->
            when (message.event) {
                "start" -> {
                    state.started = true
                    newSuspendedTransaction {
                        Multiverse.findById(multiverseId)?.gameHandlerActive = true
                    }
                }
                "debugmsg" -> {
                    server.connections.toPlayers(playerInfos.keys, PrintTextMessage(
                        "This is a message!",
                            Vector2(0f, -0.2f),
                        0,
                        3f,
                        PrintTextMessage.SCREEN_POSITION_TOP_CENTER,
                        withBox = false,
                        withSound = false,
                        queue = "hide_and_seek",
                    ))
                }
            }
        }

        multiverseEventBus.register(this, WorldCreatedEvent::class) { message ->
            logger().info("world created: ${message.worldId}")

            updatePlayerInfoCache()
        }

        multiverseEventBus.register(this, WorldDeletedEvent::class) { message ->
            logger().info("world deleted: ${message.worldId}")

            state.seekerWorlds.remove(message.worldId)

            updatePlayerInfoCache()
        }

        multiverseEventBus.register(this, PlayerJoinedEvent::class) { message ->
            logger().info("joined: ${message.worldId}")
            updatePlayerInfoCache()
        }

        multiverseEventBus.register(this, PlayerLeftEvent::class) { message ->
            logger().info("left: ${message.worldId}")
            updatePlayerInfoCache()
        }

        scheduler.scheduleExecution(Every(1, TimeUnit.SECONDS))
    }

    override fun stop() {
        scheduler.stop()
    }

    override fun serializeState(): String {
        return json.encodeToString(HideAndSeekGameHandlerState.serializer(), state)
    }

    override suspend fun restoreState(serializedState: String?) {
        serializedState?.let {
            state = json.decodeFromString(HideAndSeekGameHandlerState.serializer(), it)
        }

        updatePlayerInfoCache()
    }

    override suspend fun generateStateAggregationRegistry(): AggregationStrategyRegistry {
        return normalWorldSyncAggregationStrategy
    }

    override fun getClientInfo(): HideAndSeekGameHandlerClientInfo {
        return HideAndSeekGameHandlerClientInfo(state.seekerWorlds.values.toList())
    }

    private suspend fun updatePlayerInfoCache() {
        val playerIds = mutableSetOf<PlayerId>()

        newSuspendedTransaction {
            Multiverse.findById(multiverseId)?.let { multiverse ->

                // TODO: Hack, remove later.
                multiverse.refresh()
                multiverse.worlds.firstOrNull()?.let { firstWorld ->
                    if (!state.seekerWorlds.containsKey(firstWorld.id.value)) {
                        state.seekerWorlds.clear()
                        state.seekerWorlds[firstWorld.id.value] = SeekerWorldInfo(
                            firstWorld.id.value,
                            8f,
                            6f,
                        )
                    }
                }

                multiverse.worlds.forEach { world ->
                    val type = if (state.seekerWorlds.containsKey(world.id.value))
                        PlayerType.Seeker else PlayerType.Hider

                    world.members.forEach { player ->
                        playerIds.add(player.id.value)
                        playerInfos.getOrPut(player.id.value) {
                            PlayerInfo(type)
                        }.type = type
                    }
                }
            }
        }

        playerInfos -= playerInfos.keys - playerIds
    }

    private suspend fun updateUberState(message: UberStateUpdateMessage, worldId: Long, playerId: String) =
        batchUpdateUberStates(UberStateBatchUpdateMessage(message), worldId, playerId)

    private suspend fun batchUpdateUberStates(message: UberStateBatchUpdateMessage, worldId: Long, playerId: String) {
        val updates = message.updates.map {
            it.uberId to it.value
        }.toMap()

        val results = newSuspendedTransaction {
            logger().info("AGGREGATE START $worldId")
            val world = World.findById(worldId) ?: error("Error: Requested uber state update on unknown world")
            val retval = server.sync.aggregateStates(world, updates)
            logger().info("AGGREGATE END $worldId")
            retval
        }

        server.sync.syncStates(playerId, results)
    }
}