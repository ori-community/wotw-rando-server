package wotw.server.game.handlers

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.json
import wotw.io.messages.protobuf.PlayerPositionMessage
import wotw.io.messages.protobuf.PrintTextMessage
import wotw.io.messages.protobuf.UpdatePlayerPositionMessage
import wotw.io.messages.protobuf.Vector2
import wotw.server.api.*
import wotw.server.database.model.Multiverse
import wotw.server.game.*
import wotw.server.main.WotwBackendServer
import wotw.server.sync.*
import wotw.server.util.Every
import wotw.server.util.Scheduler
import wotw.server.util.logger
import java.util.Collections
import java.util.concurrent.TimeUnit


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

class HideAndSeekGameHandler(
    multiverseId: Long,
    server: WotwBackendServer,
) : GameHandler<HideAndSeekGameHandlerClientInfo>(multiverseId, server) {
    private val playerPositionMap: MutableMap<PlayerId, Vector2> = Collections.synchronizedMap(mutableMapOf())
    private var state = HideAndSeekGameHandlerState()
    private var seekerPlayerIdsCache = setOf<PlayerId>()
    private val scheduler = Scheduler {
        state.apply {
            if (started) {
                if (!catchPhase) {
                    secondsUntilCatchPhase--

                    var message: PrintTextMessage? = null

                    if (secondsUntilCatchPhase == 0) {
                        catchPhase = true

                        message = PrintTextMessage(
                            "GO!",
                            Vector2(0f, 1f),
                            0,
                            3f,
                            PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
                            queue = "hide_and_seek",
                        )
                    } else if (secondsUntilCatchPhase <= 10) {
                        message = PrintTextMessage(
                            "Catching starts in $secondsUntilCatchPhase seconds!",
                            Vector2(0f, 1f),
                            0,
                            3f,
                            PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
                            queue = "hide_and_seek",
                        )
                    }

                    message?.let {
                        server.connections.toPlayers(
                            playerPositionMap.keys,
                            null,
                            false,
                            it,
                        )
                    }
                } else {
                    gameSecondsElapsed++
                }

                logger().info("secondsUntilCatchPhase: $secondsUntilCatchPhase, gameSecondsElapsed: $gameSecondsElapsed")
            }
        }
    }

    override fun start() {
        messageEventBus.register(this, PlayerPositionMessage::class) { message, playerId ->
            playerPositionMap[playerId] = Vector2(message.x, message.y)

            server.populationCache.get(playerId).let { cache ->
                val targetPlayers = if (state.catchPhase) {
                    cache.universeMemberIds // Everyone can see everyone else
                } else if (state.seekerWorlds.containsKey(cache.worldId)) {
                    // Seekers can be seen by everyone else
                    cache.universeMemberIds
                } else {
                    // Hiders can't be seen before the catch phase
                    emptySet()
                }

                server.connections.toPlayers(
                    targetPlayers,
                    null,
                    true,
                    UpdatePlayerPositionMessage(playerId, message.x, message.y)
                )
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
            }
        }

        multiverseEventBus.register(this, WorldCreatedEvent::class) { message ->
            logger().info("world created: ${message.worldId}")

            if (state.seekerWorlds.isEmpty()) {
                state.seekerWorlds[message.worldId] = SeekerWorldInfo(
                    message.worldId,
                    12f,
                    3f,
                )
            }

            updateSeekerPlayerIdCache()
        }

        multiverseEventBus.register(this, WorldDeletedEvent::class) { message ->
            logger().info("world deleted: ${message.worldId}")

            state.seekerWorlds.remove(message.worldId)

            newSuspendedTransaction {
                Multiverse.findById(multiverseId)?.let { multiverse ->
                    multiverse.worlds.firstOrNull()?.let { world ->
                        state.seekerWorlds[world.id.value] = SeekerWorldInfo(
                            world.id.value,
                            12f,
                            3f,
                        )
                    }
                }
            }

            updateSeekerPlayerIdCache()
        }

        multiverseEventBus.register(this, PlayerJoinedEvent::class) { message ->
            logger().info("joined: ${message.worldId}")
            updateSeekerPlayerIdCache()
        }

        multiverseEventBus.register(this, PlayerLeftEvent::class) { message ->
            logger().info("left: ${message.worldId}")
            updateSeekerPlayerIdCache()
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

        updateSeekerPlayerIdCache()
    }

    override suspend fun generateStateAggregationRegistry(): AggregationStrategyRegistry {
        return normalWorldSyncAggregationStrategy
    }

    override fun getClientInfo(): HideAndSeekGameHandlerClientInfo {
        return HideAndSeekGameHandlerClientInfo(state.seekerWorlds.values.toList())
    }

    private suspend fun updateSeekerPlayerIdCache() {
        seekerPlayerIdsCache = newSuspendedTransaction {
            Multiverse.findById(multiverseId)?.let { multiverse ->
                val set = mutableSetOf<PlayerId>()
                multiverse.worlds.forEach { world ->
                    if (state.seekerWorlds.containsKey(world.id.value)) {
                        world.members.forEach { player ->
                            set.add(player.id.value)
                        }
                    }
                }
                set
            } ?: setOf()
        }
    }
}