package wotw.server.game.handlers.deprecated.infection

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.*
import wotw.io.messages.protobuf.*
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.api.UberStateSyncStrategy
import wotw.server.api.sync
import wotw.server.api.with
import wotw.server.database.model.Multiverse
import wotw.server.database.model.User
import wotw.server.database.model.World
import wotw.server.game.*
import wotw.server.game.handlers.GameHandler
import wotw.server.game.handlers.PlayerId
import wotw.server.main.WotwBackendServer
import wotw.server.sync.WorldStateCache
import wotw.server.sync.normalWorldSyncAggregationStrategy
import wotw.server.sync.pickupIds
import wotw.server.util.Every
import wotw.server.util.Scheduler
import wotw.server.util.doAfterTransaction
import wotw.server.util.logger
import java.util.concurrent.TimeUnit
import kotlin.math.pow


@Serializable
data class InfectionGameHandlerState(
    var started: Boolean = false,
    var catchPhase: Boolean = false,
    var secondsUntilCatchPhase: Int = 10,

    var secondsUntilPlayerReveal: Int = 0,
    var playerRevealIntervalIncreasePerSeeker: Int = 10,

    var gameSecondsElapsed: Int = 0,
    var seekerWorlds: MutableMap<Long, InfectedWorldInfo> = mutableMapOf(),
)

@Serializable
data class InfectedWorldInfo(
    @ProtoNumber(1) val worldId: Long,
    @ProtoNumber(2) val radius: Float,
    @ProtoNumber(3) val cooldown: Float,
)

@Serializable
data class InfectionGameHandlerClientInfo(
    @ProtoNumber(1) val infectedWorldInfos: List<InfectedWorldInfo>,
)

enum class PlayerType {
    Hider,
    Infected,
}

data class PlayerInfo(
    var type: PlayerType,
    var position: Vector2 = Vector2(0f, 0f),
    var revealedMapPosition: Vector2? = null,
    var hiddenInWorldSeconds: Int = 0,
) {
    override fun toString(): String {
        return "$type at $position invisible for ${hiddenInWorldSeconds}s"
    }
}

class InfectionGameHandler(
    multiverseId: Long,
    server: WotwBackendServer,
) : GameHandler<InfectionGameHandlerClientInfo>(multiverseId, server) {
    private var state = InfectionGameHandlerState()

    private val playerInfos = mutableMapOf<PlayerId, PlayerInfo>()

    private val BLAZE_UBER_ID = UberId(6, 1115)

    private val infectedSeedgenConfig = WorldPreset(
        includes = setOf(
            "world_presets/qol",
            "world_presets/rspawn"
        ),
        spawn = "FullyRandom",
        difficulty = "Gorlek",
        headers = setOf(
            "black_market",
            "key_hints",
            "zone_hints",
            "trial_hints",
            "open_mode",
            "no_ks_doors",
            "better_stomp",
        ),
        inlineHeaders = listOf(
            Header(
                "infection_infected",
                """
                    Flags: Infection (Infected)
                    
                    !!remove 2|115  // Remove Blaze
                    3|0|2|100
                    3|0|2|5
                    3|0|2|97
                    3|0|2|101
                    3|0|9|0
                    3|0|2|118
                    3|0|2|0
                    3|0|2|51
                    3|0|2|98
                    3|0|2|14
                    
                    1|1074|2|57    // Grapple
                    1|1098|2|8     // Launch
                    1|1106|2|77    // Regenerate
                    1|1115|2|102   // Dash
                    
                    2|22|2|104     // Water Dash
                    
                    3|1|8|1|11074|int|600
                    3|1|8|1|11098|int|5000
                    3|1|8|1|11106|int|600
                    3|1|8|1|11115|int|3500
                    
                    3|1|8|2|122|int|1500
                """.trimIndent()
            )
        )
    )

    private val hiderSeedgenConfig = WorldPreset(
        includes = setOf(
            "world_presets/qol",
            "world_presets/rspawn",
            "world_presets/full_bonus",
        ),
        spawn = "FullyRandom",
        difficulty = "Gorlek",
        headers = setOf(
            "teleporters",
            "black_market",
            "key_hints",
            "zone_hints",
            "trial_hints",
            "open_mode",
            "no_ks_doors",
            "better_stomp",
        ),
        inlineHeaders = listOf(
            Header(
                "infection_hider",
                """
                    Flags: Infection (Hider)
                    
                    !!remove 2|23   // Remove Water Breath
                    
                    3|0|2|100
                    3|0|2|5
                    3|0|2|97
                    3|0|2|101
                    3|0|9|0
                    3|0|2|118
                    3|0|2|0
                    3|0|2|51
                    3|0|2|98
                    3|0|2|14
                    
                    1|1074|2|57    // Grapple
                    1|1098|2|8     // Launch
                    1|1106|2|77    // Regenerate
                    1|1115|2|102   // Dash
                    
                    2|22|2|104     // Water Dash
                    
                    3|1|8|1|11074|int|600
                    3|1|8|1|11098|int|3000
                    3|1|8|1|11106|int|300
                    3|1|8|1|11115|int|1500
                    
                    3|1|8|2|122|int|1500
                """.trimIndent()
            )
        ),
        goals = setOf()
    )

    private val scheduler = Scheduler {
        state.apply {
            if (started) {
                if (!catchPhase) {
                    handleCatchPhaseCountdown()
                } else {
                    gameSecondsElapsed++
                    handlePlayerWorldVisibility()
                    handlePlayerRevealInterval()
                }
            }
        }
    }

    private suspend fun handlePlayerRevealInterval() = state.apply {
        secondsUntilPlayerReveal--

        if (secondsUntilPlayerReveal <= 0) {
            var infectedCount = 0

            playerInfos.values.forEach { playerInfo ->
                if (playerInfo.type == PlayerType.Hider) {
                    playerInfo.revealedMapPosition = playerInfo.position
                } else {
                    infectedCount++
                }
            }

            secondsUntilPlayerReveal = state.playerRevealIntervalIncreasePerSeeker * infectedCount
        }
    }

    private suspend fun handleCatchPhaseCountdown() = state.apply {
        secondsUntilCatchPhase--

        val message: PrintTextMessage

        if (secondsUntilCatchPhase == 0) {
            catchPhase = true

            // Give blaze to seeker worlds
            newSuspendedTransaction {
                seekerWorlds.keys.forEach { seekerWorldId ->
                    World.findById(seekerWorldId)?.let { seekerWorld ->
                        seekerWorld.members.forEach { member ->
                            val result = server.sync.aggregateStates(member, mapOf(BLAZE_UBER_ID to 1.0))
                            server.sync.syncStates(member.id.value, result)
                        }
                    }
                }
            }

            message = PrintTextMessage(
                "<s_2>GO!</>",
                Vector2(0f, 1f),
                0,
                3f,
                PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
                queue = "infection",
            )

            broadcastPlayerVisibility()
        } else {
            message = PrintTextMessage(
                "Infection starts in $secondsUntilCatchPhase",
                Vector2(1.5f, 0f),
                0,
                3f,
                screenPosition = PrintTextMessage.SCREEN_POSITION_BOTTOM_RIGHT,
                horizontalAnchor = PrintTextMessage.HORIZONTAL_ANCHOR_RIGHT,
                alignment = PrintTextMessage.ALIGNMENT_RIGHT,
                withBox = false,
                withSound = false,
                queue = "infection",
            )
        }

        server.connections.toPlayers(playerInfos.keys, message)
    }

    private suspend fun handlePlayerWorldVisibility() = state.apply {
        playerInfos.forEach { (playerId, playerInfo) ->
            if (playerInfo.hiddenInWorldSeconds > 0) {
                playerInfo.hiddenInWorldSeconds--

                val message: String
                if (playerInfo.hiddenInWorldSeconds == 0) {
                    message = "You are no longer invisible."
                    broadcastPlayerVisibility()
                } else {
                    message = "You are invisible for ${playerInfo.hiddenInWorldSeconds}s"
                }

                server.connections.toPlayers(
                    listOf(playerId), PrintTextMessage(
                        message,
                        Vector2(1.5f, 2f),
                        1,
                        2f,
                        screenPosition = PrintTextMessage.SCREEN_POSITION_BOTTOM_RIGHT,
                        horizontalAnchor = PrintTextMessage.HORIZONTAL_ANCHOR_RIGHT,
                        alignment = PrintTextMessage.ALIGNMENT_RIGHT,
                        withBox = false,
                        withSound = true,
                        queue = "infection",
                    )
                )
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
            playerInfos[playerId]?.let { senderInfo ->
                senderInfo.position = Vector2(message.x, message.y)
                val cache = server.playerEnvironmentCache.get(playerId)

                if (senderInfo.type == PlayerType.Infected) {
                    server.connections.toPlayers(
                        cache.universeMemberIds - playerId,
                        UpdatePlayerPositionMessage(playerId, message.x, message.y, message.ghostFrameData),
                        unreliable = true,
                    )
                } else {
                    if (senderInfo.hiddenInWorldSeconds == 0) {
                        server.connections.toPlayers(
                            cache.universeMemberIds - playerId,
                            UpdatePlayerWorldPositionMessage(playerId, message.x, message.y, message.ghostFrameData),
                            unreliable = true,
                        )
                    }

                    // If there's only one seeker, reveal positions instantly
                    if (playerInfos.values.count { p -> p.type == PlayerType.Infected } == 1) {
                        senderInfo.revealedMapPosition = senderInfo.position
                    }

                    senderInfo.revealedMapPosition?.let { revealedMapPosition ->
                        server.connections.toPlayers(
                            playerInfos.filter { (_, info) -> info.type == PlayerType.Infected }.keys - playerId,
                            UpdatePlayerMapPositionMessage(playerId, revealedMapPosition.x, revealedMapPosition.y),
                            unreliable = true,
                        )
                    }

                    server.connections.toPlayers(
                        playerInfos.filter { (_, info) -> info.type == PlayerType.Hider }.keys - playerId,
                        UpdatePlayerMapPositionMessage(playerId, message.x, message.y),
                        unreliable = true,
                    )
                }
            }
        }

        messageEventBus.register(this, PlayerTeleportMessage::class) { message, playerId ->
            playerInfos[playerId]?.let { senderInfo ->
                if (senderInfo.type == PlayerType.Hider) {
                    senderInfo.hiddenInWorldSeconds = 10
                    broadcastPlayerVisibility()
                }
            }
        }

        messageEventBus.register(this, PlayerUseCatchingAbilityMessage::class) { message, playerId ->
            val cache = server.playerEnvironmentCache.get(playerId)

            state.seekerWorlds[cache.worldId]?.let { seekerWorldInfo ->
                server.connections.toPlayers(
                    playerInfos.keys - playerId,
                    PlayerUsedCatchingAbilityMessage(playerId),
                )

                val caughtPlayerIds = mutableSetOf<PlayerId>()

                playerInfos[playerId]?.let { seekerInfo ->
                    playerInfos
                        .filterValues { it.type == PlayerType.Hider && it.hiddenInWorldSeconds == 0 }
                        .forEach { (playerId, hiderInfo) ->
                            if (seekerInfo.position.distanceSquaredTo(hiderInfo.position) < seekerWorldInfo.radius.pow(2)) {
                                caughtPlayerIds.add(playerId)
                            }
                        }
                }

                val seekerName = newSuspendedTransaction {
                    User.findById(playerId)?.name ?: "(?)"
                }

                server.connections.toPlayers(
                    caughtPlayerIds,
                    PrintTextMessage(
                        "You have been infected by ${seekerName}!",
                        Vector2(0f, 0f),
                        0,
                        5f,
                        PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
                        withBox = true,
                        withSound = false,
                    )
                )

                for (caughtPlayerId in caughtPlayerIds) {
                    server.connections.toPlayers(
                        playerInfos.keys,
                        PlayerCaughtMessage(caughtPlayerId),
                    )

                    newSuspendedTransaction {
                        val caughtPlayer = User.findById(caughtPlayerId)
                        val seekerWorld = World.findById(seekerWorldInfo.worldId)

                        if (caughtPlayer == null || seekerWorld == null) {
                            false
                        } else {
                            server.multiverseUtil.movePlayerToWorld(caughtPlayer, seekerWorld)
                            true
                        }
                    }
                }

                if (caughtPlayerIds.isNotEmpty()) {
                    updatePlayerInfoCache()
                    broadcastPlayerVisibility()

                    val caughtPlayerNames = newSuspendedTransaction {
                        caughtPlayerIds.mapNotNull {
                            User.findById(it)?.name
                        }
                    }

                    caughtPlayerNames.forEach { caughtPlayerName ->
                        server.connections.toPlayers(
                            playerInfos.keys,
                            PrintTextMessage(
                                "$caughtPlayerName has been infected by $seekerName!",
                                Vector2(0f, 0f),
                                time = 3f,
                                screenPosition = PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
                                withBox = true,
                                withSound = true,
                                queue = "infection",
                            )
                        )
                    }

                    val remainingPlayers = playerInfos.filter { info -> info.value.type == PlayerType.Hider }

                    if (remainingPlayers.count() == 1) {
                        val lastRemainingPlayerName = newSuspendedTransaction {
                            User.findById(remainingPlayers.keys.toList()[0])?.name
                        }

                        server.connections.toPlayers(
                            playerInfos.keys,
                            PrintTextMessage(
                                "<s_2>${lastRemainingPlayerName ?: "???"} won the game!</>",
                                Vector2(0f, 0f),
                                time = 8f,
                                screenPosition = PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
                                withBox = true,
                                withSound = true,
                                queue = "infection",
                            )
                        )

                        newSuspendedTransaction {
                            Multiverse.findById(multiverseId)?.locked = false
                            server.connections.broadcastMultiverseInfoMessage(multiverseId)
                        }
                    }
                }
            }
        }

        messageEventBus.register(this, UberStateUpdateMessage::class) { message, playerId ->
            server.playerEnvironmentCache.getOrNull(playerId)?.let { playerCache ->
                playerCache.worldId?.let { worldId ->
                    updateUberState(message, playerId)

                    if (
                        pickupIds.containsValue(message.uberId) && // It's a pickup
                        state.seekerWorlds.containsKey(worldId) // We are a seeker
                    ) {

                        val worldNamesThatPickedUpThisItem = newSuspendedTransaction {
                            Multiverse.findById(multiverseId)?.let { multiverse ->
                                multiverse.worlds
                                    .filter { !state.seekerWorlds.containsKey(it.id.value) }
                                    .filter { world ->
                                        val state = WorldStateCache.get(world.id.value)
                                        state[message.uberId] == 1.0
                                    }
                                    .map { it.name }
                            } ?: listOf()
                        }

                        // logger().info("${message.uberId} picked up by $worldNamesThatPickedUpThisItem")

                        if (worldNamesThatPickedUpThisItem.isNotEmpty()) {
                            // logger().info(playerInfos[playerId]?.position.toString())
                            // logger().info(playerCache.worldMemberIds.toString())
                            server.connections.toPlayers(
                                playerCache.worldMemberIds,
                                PrintTextMessage(
                                    worldNamesThatPickedUpThisItem.joinToString("\n"),
                                    (playerInfos[playerId]?.position ?: Vector2(0f, 0f)) + Vector2(0f, 0.5f),
                                    time = 3f,
                                    // verticalAnchor = PrintTextMessage.VERTICAL_ANCHOR_BOTTOM,
                                    useInGameCoordinates = true,
                                    withBox = false,
                                    withSound = true,
                                )
                            )
                        }
                    }
                }
            }
        }

        messageEventBus.register(this, UberStateBatchUpdateMessage::class) { message, playerId ->
            batchUpdateUberStates(message, playerId)
        }

        multiverseEventBus.register(this, DebugEvent::class) { message ->
            when (message.event) {
                "start" -> {
                    state.started = true
                    newSuspendedTransaction {
                        Multiverse.findById(multiverseId)?.let { multiverse ->
                            multiverse.gameHandlerActive = true
                            multiverse.locked = true
                        }

                        server.connections.broadcastMultiverseInfoMessage(multiverseId)
                    }
                }

                "updatePlayerInfoCache" -> {
                    updatePlayerInfoCache()
                    broadcastPlayerVisibility()
                }

                "reset" -> {
                    state = InfectionGameHandlerState()
                    updatePlayerInfoCache()
                    newSuspendedTransaction {
                        Multiverse.findById(multiverseId)?.let { multiverse ->
                            multiverse.gameHandlerActive = false

                            server.connections.toPlayers(
                                playerInfos.keys,
                                server.infoMessagesService.generateMultiverseInfoMessage(multiverse)
                            )
                        }
                    }
                }
            }
        }

        multiverseEventBus.register(this, WorldCreatedEvent::class) { message ->
            logger().info("world created: ${message.world.id.value}")

            // TODO: Make better
            val result = server.seedGeneratorService.generateSeed(
                UniversePreset(
                    worldSettings = listOf(
                        if (state.seekerWorlds.isEmpty()) {
                            infectedSeedgenConfig
                        } else {
                            hiderSeedgenConfig
                        }
                    ),
                    online = true,
                )
            )

            result.seed?.let { seed ->
                message.world.seed = seed.worldSeeds.firstOrNull()
            }

            doAfterTransaction {
                updatePlayerInfoCache()
            }
        }

        multiverseEventBus.register(this, WorldDeletedEvent::class) { message ->
            logger().info("world deleted: ${message.worldId}")

            doAfterTransaction {
                state.seekerWorlds.remove(message.worldId)
                updatePlayerInfoCache()
            }
        }

        multiverseEventBus.register(this, PlayerJoinedEvent::class) { message ->
            doAfterTransaction {
                updatePlayerInfoCache()
            }
        }

        multiverseEventBus.register(this, PlayerLeftEvent::class) { message ->
            doAfterTransaction {
                updatePlayerInfoCache()
            }
        }

        scheduler.scheduleExecution(Every(1, TimeUnit.SECONDS), true)
    }

    override fun stop() {
        scheduler.stop()
    }

    override fun serializeState(): String {
        return json.encodeToString(InfectionGameHandlerState.serializer(), state)
    }

    override suspend fun restoreState(serializedState: String?) {
        serializedState?.let {
            state = json.decodeFromString(InfectionGameHandlerState.serializer(), it)
        }

        updatePlayerInfoCache()
    }

    override suspend fun generateStateAggregationRegistry(world: World): AggregationStrategyRegistry {
        return normalWorldSyncAggregationStrategy + AggregationStrategyRegistry().apply {
            register(
                sync(BLAZE_UBER_ID).with(UberStateSyncStrategy.MAX), // Blaze
            )
        }
    }

    override fun getClientInfo(): InfectionGameHandlerClientInfo {
        return InfectionGameHandlerClientInfo(state.seekerWorlds.values.toList())
    }

    private suspend fun updatePlayerInfoCache() {
        val playerIds = mutableSetOf<PlayerId>()

        newSuspendedTransaction {
            Multiverse.findById(multiverseId)?.let { multiverse ->

                // TODO: Hack, remove later.
                multiverse.refresh()

                val sortedWorlds = multiverse.worlds.sortedBy { w -> w.id.value }

                sortedWorlds.firstOrNull()?.let { firstWorld ->
                    if (!state.seekerWorlds.containsKey(firstWorld.id.value)) {
                        state.seekerWorlds.clear()
                        state.seekerWorlds[firstWorld.id.value] = InfectedWorldInfo(
                            firstWorld.id.value,
                            8f,
                            6f,
                        )
                    }
                }

                sortedWorlds.forEach { world ->
                    val type = if (state.seekerWorlds.containsKey(world.id.value))
                        PlayerType.Infected else PlayerType.Hider

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
        broadcastPlayerVisibility()
    }

    private suspend fun updateUberState(message: UberStateUpdateMessage, playerId: String) =
        batchUpdateUberStates(UberStateBatchUpdateMessage(message), playerId)

    private suspend fun batchUpdateUberStates(message: UberStateBatchUpdateMessage, playerId: String) {
        val uberStates = message.updates.associate {
            it.uberId to it.value
        }

        val results = newSuspendedTransaction {
            val player = User.findById(playerId) ?: error("Error: Requested uber state update on unknown user")
            val results = server.sync.aggregateStates(player, uberStates)
            results
        }

        server.sync.syncStates(playerId, results)
    }

    override suspend fun onGameConnectionSetup(connectionHandler: GameConnectionHandler) {
        broadcastPlayerVisibility()
    }

    private suspend fun broadcastPlayerVisibility() {
        val hiddenOnMap =
            playerInfos.filter { (_, info) -> info.type == PlayerType.Hider && info.revealedMapPosition == null }.keys.toList()

        val hiddenInWorld = if (state.catchPhase) {
            playerInfos.filter { (_, info) -> info.hiddenInWorldSeconds > 0 }.keys.toList()
        } else {
            hiddenOnMap
        }

        server.connections.toPlayers(
            playerInfos.keys,
            SetVisibilityMessage(hiddenInWorld, hiddenOnMap)
        )
    }
}
