package wotw.server.game.handlers

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.json
import wotw.io.messages.protobuf.*
import wotw.server.api.*
import wotw.server.bingo.Point
import wotw.server.database.model.*
import wotw.server.game.MultiverseEvent
import wotw.server.game.PlayerJoinedEvent
import wotw.server.game.PlayerLeftEvent
import wotw.server.game.PlayerMovedEvent
import wotw.server.game.inventory.WorldInventory
import wotw.server.main.WotwBackendServer
import wotw.server.sync.*
import wotw.server.util.Every
import wotw.server.util.Scheduler
import wotw.server.util.assertTransaction
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit


@Serializable
data class NormalGameHandlerState(
    @ProtoNumber(1) @Required @JsonNames("raceStartingAt", "startingAt") var raceStartingAt: Long? = null,
    @ProtoNumber(2) @Required var finishedTime: Float? = null,
    @ProtoNumber(3) var playerInGameTimes: MutableMap<String, Float> = mutableMapOf(),
    @ProtoNumber(4) var playerFinishedTimes: MutableMap<String, Float> = mutableMapOf(),
    @ProtoNumber(5) var worldFinishedTimes: MutableMap<Long, Float> = mutableMapOf(),
    @ProtoNumber(6) var universeFinishedTimes: MutableMap<Long, Float> = mutableMapOf(),
    @ProtoNumber(7) var raceModeEnabled: Boolean = false,
    @ProtoNumber(8) var raceStarted: Boolean = false,
    @ProtoNumber(9) var playerSaveGuids: MutableMap<String, MoodGuid> = mutableMapOf(),
)

class NormalGameHandler(multiverseId: Long, server: WotwBackendServer) :
    GameHandler<NormalGameHandlerState>(multiverseId, server) {
    private var state = NormalGameHandlerState()

    private var lazilyNotifyClientInfoChanged = false

    private val scheduler = Scheduler {
        if (!state.raceModeEnabled) {
            return@Scheduler
        }

        state.raceStartingAt?.let { startingAt ->
            // In-game countdown
            val startingAtInstant = Instant.ofEpochMilli(startingAt)
            if (startingAtInstant.isAfter(Instant.now())) {
                val secondsUntilStart = Instant.now().until(startingAtInstant, ChronoUnit.SECONDS)

                val message = PrintTextMessage(
                    if (secondsUntilStart <= 0) "<s_4>Go!</>" else "<s_2>Race starting in $secondsUntilStart</>",
                    Vector2(0f, -2.2f),
                    0,
                    if (secondsUntilStart <= 0) 1f else 3f,
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
            } else if (startingAtInstant.isBefore(Instant.now()) && !state.raceStarted) {
                state.raceStarted = true

                newSuspendedTransaction {
                    val multiverse = getMultiverse()
                    multiverse.locked = true
                    multiverse.isLockable = false

                    getMultiverse().players.forEach { user ->
                        server.connections.playerMultiverseConnections[user.id.value]?.raceReady = false
                    }
                }

                notifyMultiverseOrClientInfoChanged()
                notifyShouldBlockStartingGameChanged()
            }

            if (state.finishedTime == null && startingAtInstant.until(Instant.now(), ChronoUnit.HOURS) > 24) {
                state.finishedTime = 0f
                lazilyNotifyClientInfoChanged = true
            }
        }

        if (lazilyNotifyClientInfoChanged) {
            notifyMultiverseOrClientInfoChanged()
            lazilyNotifyClientInfoChanged = false
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

        messageEventBus.register(this, SetPlayerSaveGuidMessage::class) { message, playerId ->
            state.playerSaveGuids[playerId] = message.playerSaveGuid

            server.connections.toPlayers(
                listOf(playerId),
                SetSaveGuidRestrictionsMessage(
                    message.playerSaveGuid,
                    true,
                )
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

        messageEventBus.register(this, ReportInGameTimeMessage::class) { message, playerId ->
            state.playerInGameTimes[playerId] = message.inGameTime

            if (message.isFinished) {
                if (!state.playerFinishedTimes.containsKey(playerId)) {
                    state.playerFinishedTimes[playerId] = message.inGameTime

                    newSuspendedTransaction {
                        val player = User.findById(playerId) ?: error("Error: Reported time for unknown user $playerId")
                        val world = player.currentWorld ?: error("Error: Player $playerId is not in a world")
                        checkWorldAndUniverseFinished(world)
                    }
                }
            }

            lazilyNotifyClientInfoChanged = true
        }

        messageEventBus.register(this, ReportPlayerRaceReadyMessage::class) { message, playerId ->
            server.connections.playerMultiverseConnections[playerId]?.raceReady = message.raceReady
            notifyMultiverseOrClientInfoChanged()
            checkRaceStartCondition()
        }

        multiverseEventBus.register(this, MultiverseEvent::class) { message ->
            when (message.event) {
                "enableRaceMode" -> {
                    state.raceModeEnabled = true
                    notifyMultiverseOrClientInfoChanged()
                    checkRaceStartCondition()
                    notifyShouldBlockStartingGameChanged()
                }
                "forfeit" -> {
                    if (state.raceStarted) {
                        newSuspendedTransaction {
                            if (getMultiverse().players.any { p -> p.id.value == message.sender.id.value } && !state.playerFinishedTimes.containsKey(message.sender.id.value)) {
                                state.playerFinishedTimes[message.sender.id.value] = 0f

                                message.sender.currentWorld?.let { world ->
                                    checkWorldAndUniverseFinished(world)
                                }
                            }
                        }
                    }
                }
            }
        }

        multiverseEventBus.register(this, PlayerJoinedEvent::class) {
            checkRaceStartCondition()
        }

        multiverseEventBus.register(this, PlayerMovedEvent::class) {
            checkRaceStartCondition()
        }

        multiverseEventBus.register(this, PlayerLeftEvent::class) {
            if (state.finishedTime == null) {
                newSuspendedTransaction {
                    checkAllUniversesFinished()
                    checkRaceStartCondition()
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

    /**
     * Check whether race mode is enabled and all players are ready.
     * If yes, start the race.
     */
    suspend fun checkRaceStartCondition() {
        // Check if race mode is enabled and the race hasn't started yet
        if (!state.raceModeEnabled && state.raceStartingAt == null) {
            return
        }

        val allPlayersReady = newSuspendedTransaction {
            getMultiverse().players.all { user ->
                server.connections.playerMultiverseConnections[user.id.value]?.raceReady ?: false
            }
        }

        if (allPlayersReady) {
            startRace()
        } else if (!state.raceStarted && state.raceStartingAt != null) {
            state.raceStartingAt = null

            val message = PrintTextMessage(
                "Countdown cancelled",
                Vector2(0f, -2.2f),
                0,
                4f,
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

            notifyMultiverseOrClientInfoChanged()
        }
    }

    suspend fun startRace() {
        state.raceStartingAt = (Instant.now().epochSecond + 5) * 1000

        newSuspendedTransaction {
            getMultiverse().gameHandlerActive = true
        }

        notifyMultiverseOrClientInfoChanged()
    }

    suspend fun endRace(finishedTime: Float) {
        state.finishedTime = finishedTime

        newSuspendedTransaction {
            val multiverse = getMultiverse()
            multiverse.gameHandlerActive = false

            // Record race result
            val race = Race.new {
                this.finishedTime = finishedTime
            }

            val universeRanks = state.universeFinishedTimes
                .toList()
                .filter { (universeId, finishedTime) -> finishedTime != 0f && Universe.findById(universeId) != null }
                .sortedBy { (_, finishedTime) -> finishedTime }
                .mapIndexed { index, (universeId, _) -> Pair(universeId, index) }
                .toMap()

            multiverse.universes.forEach() { universe ->
                val team = RaceTeam.new {
                    this.race = race
                    this.finishedTime = state.universeFinishedTimes[universe.id.value]

                    // Only distribute points if there are 2+ entrants
                    if (state.universeFinishedTimes.size >= 2) {
                        this.points = universeRanks[universe.id.value]?.let { rank ->
                            universeRanks.size - rank
                        } ?: 0
                    }
                }

                universe.members.forEach() { player ->
                    RaceTeamMember.new {
                        this.raceTeam = team
                        this.user = player
                        this.finishedTime = state.playerFinishedTimes[player.id.value]
                    }

                    player.points += team.points
                }
            }

            race.flush()

            multiverse.race = race
            multiverse.refresh(true)
        }

        notifyMultiverseOrClientInfoChanged()
    }

    override fun getClientInfo(): NormalGameHandlerState {
        return state
    }

    override suspend fun shouldBlockStartingNewGame(): Boolean {
        if (!state.raceModeEnabled) {
            return false
        }

        if (state.raceStartingAt == null) {
            return true
        }

        return !state.raceStarted
    }

    private suspend fun updateUberState(message: UberStateUpdateMessage, playerId: String) =
        batchUpdateUberStates(UberStateBatchUpdateMessage(message), playerId)

    private suspend fun checkWorldAndUniverseFinished(world: World) {
        assertTransaction()

        if (!state.worldFinishedTimes.containsKey(world.id.value)) {
            // All players finished
            if (world.members.all { player -> state.playerFinishedTimes.containsKey(player.id.value) }) {
                if (world.members.any { player -> state.playerFinishedTimes[player.id.value] == 0f }) { // If any player DNF'd...
                    state.worldFinishedTimes[world.id.value] = 0f // ...DNF the world
                } else {
                    state.worldFinishedTimes[world.id.value] =
                        world.members.maxOf { player -> state.playerFinishedTimes.getOrDefault(player.id.value, 0f) }
                }

                lazilyNotifyClientInfoChanged = true
            }
        }

        if (!state.universeFinishedTimes.containsKey(world.universe.id.value)) {
            // All worlds finished
            if (world.universe.worlds.all { w -> state.worldFinishedTimes.containsKey(w.id.value) }) {
                if (world.universe.worlds.any { w -> state.worldFinishedTimes[w.id.value] == 0f }) { // If any world DNF'd...
                    state.universeFinishedTimes[world.universe.id.value] = 0f // DNF the universe
                } else {
                    state.universeFinishedTimes[world.universe.id.value] =
                        world.universe.worlds.maxOf { w -> state.worldFinishedTimes.getOrDefault(w.id.value, 0f) }
                }

                lazilyNotifyClientInfoChanged = true
            }
        }

        if (state.finishedTime == null) {
            checkAllUniversesFinished()
        }
    }

    private suspend fun batchUpdateUberStates(message: UberStateBatchUpdateMessage, playerId: String) {
        val uberStates = message.updates.associate {
            it.uberId to it.value
        }

        val results = newSuspendedTransaction {
            val player = User.findById(playerId) ?: error("Error: Requested uber state update on unknown user")
            val world =
                player.currentWorld ?: error("Error: Requested uber state update for user that is not in a world")

            val results = server.sync.aggregateStates(player, uberStates)

            getMultiverse().board?.let { board ->
                val newBingoCardClaims = world.universe.multiverse.getNewBingoCardClaims(world.universe)

                if (board.config.lockout) {
                    newBingoCardClaims
                        .filter { claim -> getMultiverse().getLockoutGoalOwnerMap()[claim.x to claim.y]?.id?.value == world.universe.id.value }
                        .forEach { claim ->
                            board.goals[Point(claim.x, claim.y)]?.let { goal ->
                                server.multiverseMemberCache.getOrNull(multiverseId)?.memberIds?.let { multiverseMembers ->
                                    val playerEnvironmentCache = server.playerEnvironmentCache.get(playerId)
                                    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

                                    server.connections.toPlayers(
                                        multiverseMembers - playerEnvironmentCache.universeMemberIds,
                                        PrintTextMessage(
                                            "<s_0.8>${world.universe.name} completed ${alphabet[claim.x - 1]}${claim.y}:</>\n${goal.title}",
                                            Vector2(0f, -1.3f),
                                            null,
                                            3f,
                                        ),
                                    )
                                }
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

    private suspend fun checkAllUniversesFinished() {
        assertTransaction()

        if (!state.raceStarted) {
            return
        }

        val multiverse = getMultiverse()
        if (multiverse.universes.all { universe -> state.universeFinishedTimes.containsKey(universe.id.value) }) {
            // All universes finished
            endRace(multiverse.universes.maxOfOrNull { universe -> state.universeFinishedTimes.getOrDefault(universe.id.value, 0f) } ?: 0f)
        }
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

    override suspend fun getPlayerSaveGuid(playerId: PlayerId): MoodGuid? {
        return state.playerSaveGuids[playerId]
    }
}
