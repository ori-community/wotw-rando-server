@file:OptIn(ExperimentalSerializationApi::class)

package wotw.server.game.handlers.league

import kotlinx.datetime.Clock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.json
import wotw.io.messages.protobuf.*
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.bingo.UberStateMap
import wotw.server.database.model.*
import wotw.server.exception.ConflictException
import wotw.server.game.GameConnectionHandler
import wotw.server.game.GameConnectionHandlerSyncResult
import wotw.server.game.MultiverseEvent
import wotw.server.game.handlers.GameHandler
import wotw.server.game.handlers.WorldMembershipId
import wotw.server.main.WotwBackendServer
import wotw.server.util.assertTransaction
import wotw.server.util.logger


@Serializable
data class LeagueGameHandlerState(
    var playerInGameTimes: MutableMap<WorldMembershipId, Float> = mutableMapOf(),
    var playerStartedAtTimestamps: MutableMap<WorldMembershipId, Float> = mutableMapOf(),
    var playerSaveGuids: MutableMap<WorldMembershipId, MoodGuid> = mutableMapOf(),
)


class LeagueGameHandler(multiverseId: Long, server: WotwBackendServer) :
    GameHandler<Nothing>(multiverseId, server) {

    private var state = LeagueGameHandlerState()

    fun getLeagueGame(): LeagueGame {
        assertTransaction()
        return LeagueGame.find { LeagueGames.multiverseId eq multiverseId }.firstOrNull() ?: throw RuntimeException("Could not find league game for multiverse $multiverseId")
    }

    init {
        // Prevent going backwards in time
        messageEventBus.register(this, ReportInGameTimeMessage::class) { message, playerId ->
            state.playerInGameTimes[playerId] = message.inGameTime

            val currentInGameTime = state.playerInGameTimes[playerId] ?: 0f

            if (currentInGameTime > message.inGameTime) {
                server.connections.toPlayers(listOf(playerId), OverrideInGameTimeMessage(currentInGameTime))
                return@register
            } else {
                state.playerInGameTimes[playerId] = message.inGameTime
            }
        }

        // Mark user as DNF if they aborted a game with a >0 time
        // multiverseEventBus.register(this, PlayerLeftEvent::class) { event ->
        //     val currentInGameTime = state.playerInGameTimes[event.player.id.value] ?: 0f
        //     if (currentInGameTime > 0f && canSubmit(event.player)) {
        //         createSubmission(event.player, null)
        //     }
        // }

        multiverseEventBus.register(this, MultiverseEvent::class) { message ->
            when (message.event) {
                "forfeit" -> {
                    if (canSubmit(message.sender.user)) {
                        createSubmission(message.sender.user) {
                            it.validated = true
                        }
                    }
                }
            }
        }

        messageEventBus.register(this, SetPlayerSaveGuidMessage::class) { message, playerId ->
            // Don't override existing GUID if there's already one.
            // Resetting and starting a new save file is not allowed
            val guid = state.playerSaveGuids.getOrPut(playerId) {
                state.playerStartedAtTimestamps[playerId] = Clock.System.now().toEpochMilliseconds() / 1000f
                return@getOrPut message.playerSaveGuid
            }

            server.connections.toPlayers(
                listOf(playerId),
                SetSaveGuidRestrictionsMessage(
                    guid,
                    true,
                )
            )
        }
    }

    private fun getLeagueSeasonMembership(user: User): LeagueSeasonMembership? {
        return getLeagueGame().season.memberships.firstOrNull { it.user.id == user.id }
    }

    private fun isLeagueSeasonMember(user: User): Boolean {
        return getLeagueGame().season.memberships.any { it.user.id == user.id }
    }

    fun getSubmissionForThisGame(user: User): LeagueGameSubmission? {
        return getLeagueGame().submissions.firstOrNull { it.membership.user.id == user.id }
    }

    fun didSubmitForThisGame(user: User): Boolean {
        return getLeagueGame().submissions.any { it.membership.user.id == user.id }
    }

    suspend fun canSubmit(user: User): Boolean {
        return !didSubmitForThisGame(user) && isLeagueSeasonMember(user) && getLeagueGame().isCurrent
    }

    suspend fun createSubmission(user: User, apply: ((LeagueGameSubmission) -> Unit)? = null) {
        newSuspendedTransaction {
            val membership = getLeagueSeasonMembership(user)

            if (membership == null) {
                logger().error("LeagueGameHandler ($multiverseId): Tried to create a submission for user $user but did not find a membership")
                return@newSuspendedTransaction
            }

            val submission = LeagueGameSubmission.new {
                this.game = getLeagueGame()
                this.membership = membership
                apply?.invoke(this)
            }

            submission.flush()
        }
    }

    override suspend fun generateStateAggregationRegistry(world: World): AggregationStrategyRegistry {
        // We don't sync anything in League games
        return AggregationStrategyRegistry()
    }

    override suspend fun getPlayerSaveGuid(worldMembership: WorldMembership): MoodGuid? {
        return state.playerSaveGuids[worldMembership.id.value]
    }

    override suspend fun shouldPreventCheats(worldMembership: WorldMembership): Boolean {
        return System.getenv("LEAGUE_ALLOW_CHEATS") != "true"
    }

    // We only support creating new universes. Every other join operation
    // does nothing by design since league games don't support multiple
    // worlds anyway and players don't interact with it like normal games
    override suspend fun onPlayerCreateUniverseRequest(user: User) {
        if (!canSubmit(user)) {
            throw ConflictException("You cannot join this game because it is not possible for you to submit")
        }

        val multiverse = getMultiverse()

        if (multiverse.players.contains(user)) {
            throw ConflictException("You cannot create a new universe in this league game because you are already part of it")
        }

        val leagueGame = getLeagueGame()

        val universe = Universe.new {
            name = "League Game ${leagueGame.gameNumber}"
            this.multiverse = multiverse
        }

        GameState.new {
            this.multiverse = multiverse
            this.universe = universe
            this.uberStateData = UberStateMap()
        }

        val worldSeed = multiverse.seed?.worldSeeds?.firstOrNull() ?: throw RuntimeException("World seed not found")

        val world = World.new(universe, "League Game ${leagueGame.gameNumber}", worldSeed)
        server.multiverseUtil.movePlayerToWorld(user, world)
    }

    override suspend fun onGameConnectionSetup(connectionHandler: GameConnectionHandler, setupResult: GameConnectionHandlerSyncResult) {
        val seedContent = newSuspendedTransaction {
            World.findById(setupResult.worldId)?.seed?.content
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
    }

    override fun canSpectateMultiverse(user: User): Boolean = false

    override fun canDuplicateMultiverse(): Boolean = false

    override suspend fun isDisposable(): Boolean {
        return newSuspendedTransaction {
            !getLeagueGame().isCurrent
        }
    }

    override fun serializeState(): String {
        return json.encodeToString(LeagueGameHandlerState.serializer(), state)
    }

    override suspend fun restoreState(serializedState: String?) {
        serializedState?.let {
            state = json.decodeFromString(LeagueGameHandlerState.serializer(), it)
        }
    }

    override fun shouldEnforceSeedDifficulty(): Boolean = true


    fun getPlayerRealTime(worldMembership: WorldMembership): Float? {
        return state.playerStartedAtTimestamps[worldMembership.id.value]?.let {
            return@let (Clock.System.now().toEpochMilliseconds() / 1000f) - it
        }
    }
}
