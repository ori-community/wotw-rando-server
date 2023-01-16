package wotw.server.game.handlers

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.json
import wotw.io.messages.protobuf.*
import wotw.server.api.*
import wotw.server.database.model.GameState
import wotw.server.database.model.Multiverse
import wotw.server.database.model.User
import wotw.server.database.model.World
import wotw.server.game.MultiverseEvent
import wotw.server.game.inventory.WorldInventory
import wotw.server.main.WotwBackendServer
import wotw.server.sync.*
import wotw.server.util.Every
import wotw.server.util.Scheduler
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit


@Serializable
data class NormalGameHandlerState(
    @ProtoNumber(1) @Required var startingAt: Long? = null,
    @ProtoNumber(2) var playerLoadingTimes: MutableMap<String, Float> = mutableMapOf(),
    @ProtoNumber(3) var playerFinishedTimes: MutableMap<String, Float> = mutableMapOf(),
    @ProtoNumber(4) var worldFinishedTimes: MutableMap<Long, Float> = mutableMapOf(),
    @ProtoNumber(5) var universeFinishedTimes: MutableMap<Long, Float> = mutableMapOf(),
)

class NormalGameHandler(multiverseId: Long, server: WotwBackendServer) :
    GameHandler<NormalGameHandlerState>(multiverseId, server) {
    private var state = NormalGameHandlerState()

    private var lazilyNotifyClientInfoChanged = false

    private val scheduler = Scheduler {
        if (lazilyNotifyClientInfoChanged) {
            notifyMultiverseOrClientInfoChanged()
            lazilyNotifyClientInfoChanged = false
        }

        state.startingAt?.let { startingAt ->
            // In-game countdown
            val startingAtInstant = Instant.ofEpochMilli(startingAt)
            if (startingAtInstant.isAfter(Instant.now())) {
                val seconds = Instant.now().until(startingAtInstant, ChronoUnit.SECONDS)

                val message = PrintTextMessage(
                    if (seconds <= 0) "Go!" else "Race starting in $seconds",
                    Vector2(0f, -1f),
                    0,
                    3f,
                    screenPosition = PrintTextMessage.SCREEN_POSITION_TOP_CENTER,
                    horizontalAnchor = PrintTextMessage.HORIZONTAL_ANCHOR_CENTER,
                    alignment = PrintTextMessage.ALIGNMENT_CENTER,
                    withBox = true,
                    withSound = true,
                    queue = "race_timer",
                )

                server.multiverseMemberCache.getOrNull(multiverseId)?.memberIds?.let { multiverseMembers ->
                    server.connections.toPlayers(multiverseMembers, message)
                }
            }
        }
    }

    init {
        messageEventBus.register(this, UberStateUpdateMessage::class) { message, playerId ->
            updateUberState(message, playerId)
        }

        messageEventBus.register(this, UberStateBatchUpdateMessage::class) { message, playerId ->
            batchUpdateUberStates(message, playerId)
        }

        messageEventBus.register(this, PlayerPositionMessage::class) { message, playerId ->
            val targetPlayers = server.playerEnvironmentCache.get(playerId).universeMemberIds - playerId

            server.connections.toPlayers(
                targetPlayers,
                UpdatePlayerPositionMessage(playerId, message.x, message.y, message.ghostFrameData),
                unreliable = true,
            )
        }

        messageEventBus.register(this, ResourceRequestMessage::class) { message, playerId ->
            val playerPopulationCache = server.playerEnvironmentCache.get(playerId)

            newSuspendedTransaction {
                playerPopulationCache.worldId?.let { worldId ->
                    GameState.findWorldState(worldId)?.let { gameState ->
                        val inventory = WorldInventory(gameState)

                        val targetPlayers = playerPopulationCache.worldMemberIds
                        val uberStateUpdateMessage = inventory.handleRequest(message)

                        if (uberStateUpdateMessage != null) {
                            server.connections.toPlayers(
                                targetPlayers,
                                uberStateUpdateMessage,
                            )
                        }
                    }
                }
            }
        }

        messageEventBus.register(this, ReportLoadingTimeMessage::class) { message, playerId ->
            state.playerLoadingTimes[playerId] = message.loadingTime
            lazilyNotifyClientInfoChanged = true
        }

        multiverseEventBus.register(this, MultiverseEvent::class) { message ->
            when (message.event) {
                "startTimer" -> {
                    state.startingAt = Instant.now().plusSeconds(20).toEpochMilli()

                    newSuspendedTransaction {
                        Multiverse.findById(multiverseId)?.let { multiverse ->
                            multiverse.gameHandlerActive = true
                            multiverse.locked = true
                        }
                    }

                    notifyMultiverseOrClientInfoChanged()
                }
            }
        }

        scheduler.scheduleExecution(Every(1, TimeUnit.SECONDS), true)
    }

    override fun serializeState(): String {
        return json.encodeToString(NormalGameHandlerState.serializer(), state)
    }

    override suspend fun restoreState(serializedState: String?) {
        serializedState?.let {
            state = json.decodeFromString(NormalGameHandlerState.serializer(), it)
        }
    }

    override fun getClientInfo(): NormalGameHandlerState? {
        return state
    }

    private suspend fun updateUberState(message: UberStateUpdateMessage, playerId: String) =
        batchUpdateUberStates(UberStateBatchUpdateMessage(message), playerId)

    private suspend fun batchUpdateUberStates(message: UberStateBatchUpdateMessage, playerId: String) {
        val uberStates = message.updates.associate {
            it.uberId to it.value
        }

        val results = newSuspendedTransaction {
            val player = User.findById(playerId) ?: error("Error: Requested uber state update on unknown user")
            val world =
                player.currentWorld ?: error("Error: Requested uber state update for user that is not in a world")

            val results = server.sync.aggregateStates(player, uberStates)
            world.universe.multiverse.updateCompletions(world.universe)

            state.startingAt?.let { startingAt ->
                if (results.containsKey(gameFinished)) {
                    if (!state.playerFinishedTimes.containsKey(playerId) && results[gameFinished]!!.sentValue > 0.5) {
                        val realTimeMillis =
                            Instant.ofEpochMilli(startingAt).until(Instant.now(), ChronoUnit.MILLIS)
                        state.playerFinishedTimes[playerId] = (realTimeMillis.toFloat() / 1000f) - (state.playerLoadingTimes[playerId] ?: 0f)
                        lazilyNotifyClientInfoChanged = true
                    }

                    if (!state.worldFinishedTimes.containsKey(world.id.value)) {
                        // All players finished
                        if (world.members.all { player -> state.playerFinishedTimes.containsKey(player.id.value) }) {
                            state.worldFinishedTimes[world.id.value] = world.members.maxOf { player -> state.playerFinishedTimes[player.id.value] ?: 0f }
                            lazilyNotifyClientInfoChanged = true
                        }
                    }

                    if (!state.universeFinishedTimes.containsKey(world.universe.id.value)) {
                        // All worlds finished
                        if (world.universe.worlds.all { world -> state.worldFinishedTimes.containsKey(world.id.value) }) {
                            state.universeFinishedTimes[world.universe.id.value] = world.universe.worlds.maxOf { world -> state.worldFinishedTimes[world.id.value] ?: 0f }
                            lazilyNotifyClientInfoChanged = true
                        }
                    }
                }
            }

            results
        }

        // Don't think this is needed?
        // val pc = server.connections.playerMultiverseConnections[playerId]!!
        // if (pc.multiverseId != multiverseId) {
        //     server.connections.unregisterMultiverseConnection(playerId)
        //     server.connections.registerMultiverseConnection(pc.clientConnection, playerId, multiverseId)
        // }

        server.sync.syncMultiverseProgress(multiverseId)
        server.sync.syncStates(playerId, results)
    }

    override suspend fun generateStateAggregationRegistry(world: World): AggregationStrategyRegistry {
        var aggregationRegistry = AggregationStrategyRegistry()

        // Add bingo states if we have a bingo game
        newSuspendedTransaction {
            Multiverse.findById(multiverseId)?.board?.goals?.flatMap { it.value.keys }
        }?.let {
            aggregationRegistry += AggregationStrategyRegistry().apply {
                register(
                    sync(it).notify(UberStateSyncStrategy.NotificationGroup.NONE).across(ShareScope.UNIVERSE)
                )
            }
        }

        aggregationRegistry += normalWorldSyncAggregationStrategy

        aggregationRegistry += AggregationStrategyRegistry().apply {
            register(
                sync(multiStates()).across(ShareScope.UNIVERSE),
            )
        }

        return aggregationRegistry
    }
}