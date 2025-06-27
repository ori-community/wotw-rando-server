@file:OptIn(ExperimentalSerializationApi::class)

package wotw.server.game.handlers

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.json
import wotw.io.messages.protobuf.*
import wotw.server.api.*
import wotw.server.bingo.Point
import wotw.server.bingo.UberStateMap
import wotw.server.database.model.*
import wotw.server.exception.ConflictException
import wotw.server.game.*
import wotw.server.game.inventory.WorldInventory
import wotw.server.main.WotwBackendServer
import wotw.server.sync.ShareScope
import wotw.server.sync.multiStates
import wotw.server.sync.normalWorldSyncAggregationStrategy
import wotw.server.util.Every
import wotw.server.util.Scheduler
import wotw.server.util.assertTransaction
import wotw.server.util.makeServerTextMessage
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit


@Serializable
data class NormalGameHandlerState(
    @ProtoNumber(1) @Required @JsonNames("raceStartingAt", "startingAt") var raceStartingAt: Long? = null,
    @ProtoNumber(2) @Required var finishedTime: Float? = null,
    @ProtoNumber(3) var playerInGameTimes: MutableMap<WorldMembershipId, Float> = mutableMapOf(),
    @ProtoNumber(4) var playerFinishedTimes: MutableMap<WorldMembershipId, Float> = mutableMapOf(),
    @ProtoNumber(5) var worldFinishedTimes: MutableMap<Long, Float> = mutableMapOf(),
    @ProtoNumber(6) var universeFinishedTimes: MutableMap<Long, Float> = mutableMapOf(),
    @ProtoNumber(7) var raceModeEnabled: Boolean = false,
    @ProtoNumber(8) var raceStarted: Boolean = false,
    @ProtoNumber(9) var playerSaveGuids: MutableMap<WorldMembershipId, MoodGuid> = mutableMapOf(),
)

class NormalGameHandler(multiverseId: Long, server: WotwBackendServer) : GameHandler<NormalGameHandlerState>(multiverseId, server) {
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

                server.multiverseMemberCache.getOrNull(multiverseId)?.worldMembershipIds?.let { worldMembershipIds ->
                    server.connections.toPlayers(worldMembershipIds, message)
                }
            } else if (startingAtInstant.isBefore(Instant.now()) && !state.raceStarted) {
                state.raceStarted = true

                newSuspendedTransaction {
                    val multiverse = getMultiverse()
                    multiverse.locked = true
                    multiverse.isLockable = false

                    getMultiverse().memberships.forEach { worldMembership ->
                        server.connections.playerMultiverseConnections[worldMembership.id.value]?.raceReady = false
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
        messageEventBus.register(this, UberStateUpdateMessage::class) { message, worldMembershipId ->
            updateUberState(message, worldMembershipId)
        }

        messageEventBus.register(this, UberStateBatchUpdateMessage::class) { message, worldMembershipId ->
            batchUpdateUberStates(message, worldMembershipId)
        }

        messageEventBus.register(this, PlayerPositionMessage::class) { message, worldMembershipId ->
            val cacheEntry = server.worldMembershipEnvironmentCache.get(worldMembershipId)
            val targetPlayers = cacheEntry.universeWorldMembershipIds - worldMembershipId

            server.connections.toPlayers(
                targetPlayers,
                UpdatePlayerPositionMessage(cacheEntry.playerId, message.x, message.y, message.ghostFrameData),
                unreliable = true,
            )
        }

        messageEventBus.register(this, SetPlayerSaveGuidMessage::class) { message, worldMembershipId ->
            state.playerSaveGuids[worldMembershipId] = message.playerSaveGuid

            server.connections.toPlayers(
                listOf(worldMembershipId), SetSaveGuidRestrictionsMessage(
                    message.playerSaveGuid,
                    true,
                )
            )
        }

        // TODO: Implement in client
        messageEventBus.register(this, ResourceRequestMessage::class) { message, worldMembershipId ->
            val worldMembershipCache = server.worldMembershipEnvironmentCache.get(worldMembershipId)

            newSuspendedTransaction {
                worldMembershipCache.worldId?.let { worldId ->
                    GameState.findWorldState(worldId)?.let { gameState ->
                        val inventory = WorldInventory(gameState)

                        val targetWorldMemberships = worldMembershipCache.worldWorldMembershipIds
                        val uberStateUpdateMessage = inventory.handleRequest(message)

                        if (uberStateUpdateMessage != null) {
                            server.connections.toPlayers(
                                targetWorldMemberships,
                                uberStateUpdateMessage,
                            )
                        }
                    }
                }
            }
        }

        messageEventBus.register(this, ReportInGameTimeMessage::class) { message, worldMembershipId ->
            if (!state.playerSaveGuids.containsKey(worldMembershipId)) {
                return@register
            }

            val currentInGameTime = state.playerInGameTimes[worldMembershipId] ?: 0f

            if (currentInGameTime > message.inGameTime) {
                server.connections.toPlayers(listOf(worldMembershipId), OverrideInGameTimeMessage(currentInGameTime))
                return@register
            } else {
                lazilyNotifyClientInfoChangedIf {
                    state.playerInGameTimes.put(worldMembershipId, message.inGameTime) != message.inGameTime
                }
            }

            if (message.isFinished) {
                if (!state.playerFinishedTimes.containsKey(worldMembershipId)) {
                    lazilyNotifyClientInfoChangedIf {
                        state.playerFinishedTimes.put(worldMembershipId, message.inGameTime) != message.inGameTime
                    }

                    newSuspendedTransaction {
                        val worldMembership = WorldMembership.findById(worldMembershipId) ?: error("Error: Reported time for unknown world membership $worldMembershipId")
                        checkWorldAndUniverseFinished(worldMembership.world)
                    }
                }
            }
        }

        messageEventBus.register(this, ReportPlayerRaceReadyMessage::class) { message, worldMembershipId ->
            server.connections.playerMultiverseConnections[worldMembershipId]?.raceReady = message.raceReady
            notifyMultiverseOrClientInfoChanged()
            checkRaceStartCondition()
        }

        multiverseEventBus.register(this, MultiverseEvent::class) { message ->
            when (message.event) {
                "enableRaceMode" -> {
                    state.raceModeEnabled = true

                    newSuspendedTransaction {
                        val worlds = getMultiverse().worlds

                        worlds.forEach { world ->
                            world.seed?.let { worldSeed ->
                                server.connections.toPlayers(
                                    world.memberships.map { it.id.value },
                                    SetGameDifficultySettingsOverridesMessage(
                                        worldSeed.inferGameDifficultySettingsOverrides()
                                    ),
                                )
                            }
                        }
                    }

                    notifyMultiverseOrClientInfoChanged()
                    checkRaceStartCondition()
                    notifyShouldBlockStartingGameChanged()
                }

                "forfeit" -> {
                    if (state.raceStarted) {
                        newSuspendedTransaction {
                            if (getMultiverse().players.any { p -> p.id.value == message.sender.user.id.value } && !state.playerFinishedTimes.containsKey(message.sender.id.value)) {
                                state.playerFinishedTimes[message.sender.id.value] = 0f

                                checkWorldAndUniverseFinished(message.sender.world)

                                server.multiverseMemberCache.getOrNull(multiverseId)?.worldMembershipIds?.let { worldMembershipIds ->
                                    val playerEnvironmentCache = server.worldMembershipEnvironmentCache.get(message.sender.id.value)

                                    server.connections.toPlayers(
                                        worldMembershipIds - playerEnvironmentCache.universeWorldMembershipIds,
                                        makeServerTextMessage("${message.sender.user.name} forfeited from the race"),
                                    )

                                    server.connections.toPlayers(
                                        playerEnvironmentCache.universeWorldMembershipIds,
                                        makeServerTextMessage("You forfeited from the race"),
                                    )
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

    fun lazilyNotifyClientInfoChangedIf(block: () -> Boolean) {
        lazilyNotifyClientInfoChanged = block.invoke() || lazilyNotifyClientInfoChanged
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
            getMultiverse().memberships.all { worldMembership ->
                server.connections.playerMultiverseConnections[worldMembership.id.value]?.raceReady ?: false
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

            server.multiverseMemberCache.getOrNull(multiverseId)?.worldMembershipIds?.let { worldMembershipIds ->
                server.connections.toPlayers(worldMembershipIds, message)
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

            multiverse.universes.forEach { universe ->
                val team = RaceTeam.new {
                    this.race = race
                    this.finishedTime = state.universeFinishedTimes[universe.id.value]
                }

                universe.memberships.forEach { worldMembership ->
                    RaceTeamMember.new {
                        this.raceTeam = team
                        this.user = worldMembership.user
                        this.finishedTime = state.playerFinishedTimes[worldMembership.id.value]
                    }
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

    private suspend fun updateUberState(message: UberStateUpdateMessage, worldMembershipId: Long) = batchUpdateUberStates(UberStateBatchUpdateMessage(message), worldMembershipId)

    private suspend fun checkWorldAndUniverseFinished(world: World) {
        assertTransaction()

        if (!state.worldFinishedTimes.containsKey(world.id.value)) {
            // All players finished
            if (world.memberships.all { state.playerFinishedTimes.containsKey(it.id.value) }) {
                if (world.memberships.any { state.playerFinishedTimes[it.id.value] == 0f }) { // If any player DNF'd...
                    state.worldFinishedTimes[world.id.value] = 0f // ...DNF the world
                } else {
                    state.worldFinishedTimes[world.id.value] = world.memberships.maxOf { state.playerFinishedTimes.getOrDefault(it.id.value, 0f) }
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
                    state.universeFinishedTimes[world.universe.id.value] = world.universe.worlds.maxOf { w -> state.worldFinishedTimes.getOrDefault(w.id.value, 0f) }
                }

                lazilyNotifyClientInfoChanged = true
            }
        }

        if (state.finishedTime == null) {
            checkAllUniversesFinished()
        }
    }

    private suspend fun batchUpdateUberStates(message: UberStateBatchUpdateMessage, worldMembershipId: Long) {
        val uberStates = message.updates.associate {
            it.uberId to it.value
        }

        val results = newSuspendedTransaction {
            val worldMembership = WorldMembership.findById(worldMembershipId) ?: error("Error: Requested uber state update on unknown world membership")
            val world = worldMembership.world

            val results = server.sync.aggregateStates(worldMembership, uberStates)

            getMultiverse().cachedBoard?.let { board ->
                val newBingoCardClaims = world.universe.multiverse.getNewBingoCardClaims(world.universe)

                if (board.config.lockout) {
                    newBingoCardClaims.filter { claim -> getMultiverse().getLockoutGoalOwnerMap()[claim.x to claim.y]?.id?.value == world.universe.id.value }.forEach { claim ->
                            board.goals[Point(claim.x, claim.y)]?.let { goal ->
                                server.multiverseMemberCache.getOrNull(multiverseId)?.worldMembershipIds?.let { worldMembershipIds ->
                                    val playerEnvironmentCache = server.worldMembershipEnvironmentCache.get(worldMembershipId)
                                    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

                                    server.connections.toPlayers(
                                        worldMembershipIds - playerEnvironmentCache.universeWorldMembershipIds,
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
        server.sync.sendUberStateUpdates(worldMembershipId, results)
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
            Multiverse.findById(multiverseId)?.cachedBoard?.goals?.flatMap { it.value.keys }
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

    override suspend fun getPlayerSaveGuid(worldMembership: WorldMembership): MoodGuid? {
        return state.playerSaveGuids[worldMembership.id.value]
    }

    private suspend fun movePlayerToWorld(user: User, world: World): WorldMembership {
        val worldMembership = server.multiverseUtil.movePlayerToWorld(user, world)
        state.playerSaveGuids.remove(worldMembership.id.value)
        getMultiverse().updateAutomaticWorldNames()
        return worldMembership
    }

    override suspend fun onPlayerCreateWorldRequest(user: User, universe: Universe) {
        val multiverse = getMultiverse()

        if (multiverse.seed != null) throw ConflictException("Cannot manually create or remove worlds from seed-linked universes")

        server.connections.toPlayers(
            (multiverse.memberships - universe.memberships).map { it.id.value }, makeServerTextMessage(
                "${user.name} joined this game in another universe",
            ), false
        )

        server.connections.toPlayers(
            universe.memberships.map { it.id.value }, makeServerTextMessage(
                "${user.name} joined a new world in your universe",
            )
        )

        val world = World.new(universe, user.name + "'s World")
        movePlayerToWorld(user, world)
    }

    override suspend fun onPlayerJoinWorldRequest(user: User, world: World) {
        val multiverse = getMultiverse()
        if (!world.members.contains(user)) {
            val worldMembership = movePlayerToWorld(user, world)
            getMultiverse().cleanup(world)

            server.connections.toPlayers(
                (multiverse.memberships - world.universe.memberships - worldMembership).map { it.id.value }, makeServerTextMessage(
                    "${user.name} joined this game in another universe",
                )
            )

            server.connections.toPlayers(
                (world.universe.memberships - world.memberships - worldMembership).map { it.id.value }, makeServerTextMessage(
                    "${user.name} joined your universe in another world",
                )
            )

            server.connections.toPlayers(
                (world.memberships - worldMembership).map { it.id.value }, makeServerTextMessage(
                    "${user.name} joined your world",
                )
            )
        }
    }

    override suspend fun onPlayerCreateUniverseRequest(user: User) {
        val multiverse = getMultiverse()
        val universe = Universe.new {
            name = "${user.name}'s Universe"
            this.multiverse = multiverse
        }

        GameState.new {
            this.multiverse = multiverse
            this.universe = universe
            this.uberStateData = UberStateMap()
        }

        val world = multiverse.seed?.let { seed ->
            var first: World? = null

            seed.worldSeeds.forEach { worldSeed ->
                val world = World.new(universe, worldSeed.id.value.toString(), worldSeed)
                if (first == null) {
                    first = world
                }
            }

            first ?: throw RuntimeException("Seed group does not contain any world")
        } ?: World.new(universe, "${user.name}'s World")

        val worldMembership = movePlayerToWorld(user, world)
        getMultiverse().cleanup(world)

        server.connections.toPlayers(
            (multiverse.memberships - worldMembership).map { it.id.value }, makeServerTextMessage(
                "${user.name} joined this game in a new universe",
            )
        )
    }

    override suspend fun onGameConnectionSetup(connectionHandler: GameConnectionHandler, setupResult: GameConnectionHandlerSyncResult) {
        val (multiverseWorldMembershipIds, seedContent) = newSuspendedTransaction {
            val world = World.findById(setupResult.worldId)

            (
                world?.universe?.multiverse?.memberships?.map { it.id.value } ?: emptyList()
            ) to world?.seed?.content
        }

        // Send the seed
        connectionHandler.worldMembershipId?.let { worldMembershipId ->
            if (seedContent != null) {
                server.connections.toPlayers(
                    listOf(worldMembershipId),
                    SetSeedMessage(seedContent),
                )
            }
        }

        // Check if all players are online
        val allPlayersOnline = multiverseWorldMembershipIds.all {
            server.connections.playerMultiverseConnections.containsKey(it)
        }

        if (allPlayersOnline && multiverseWorldMembershipIds.count() >= 2) {
            server.connections.toPlayers(
                multiverseWorldMembershipIds, makeServerTextMessage(
                    "All ${multiverseWorldMembershipIds.count()} players are connected!",
                )
            )
        }
    }

    override suspend fun getDifficultySettingsOverrides(worldMembershipId: WorldMembershipId): GameDifficultySettingsOverrides? {
        if (!state.raceModeEnabled) {
            return null
        }

        return newSuspendedTransaction {
            server.worldMembershipEnvironmentCache.get(worldMembershipId).worldSeedId?.let { worldSeedId ->
                WorldSeed.findById(worldSeedId)?.let { worldSeed ->
                    return@newSuspendedTransaction worldSeed.inferGameDifficultySettingsOverrides()
                }
            }

            return@newSuspendedTransaction null
        }
    }

    override suspend fun isDisposable(): Boolean {
        // Allow disposal if race mode is inactive or the race started >24h ago
        return !state.raceModeEnabled || ((state.raceStartingAt ?: 0L) < (System.currentTimeMillis() - 24L * 60L * 60L))
    }
}
