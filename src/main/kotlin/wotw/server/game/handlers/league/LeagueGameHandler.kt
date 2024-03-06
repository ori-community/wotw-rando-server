package wotw.server.game.handlers.league

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.*
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.database.model.*
import wotw.server.game.MultiverseEvent
import wotw.server.game.PlayerLeftEvent
import wotw.server.game.handlers.GameHandler
import wotw.server.game.handlers.NormalGameHandlerState
import wotw.server.game.handlers.PlayerId
import wotw.server.main.WotwBackendServer
import wotw.server.util.assertTransaction
import wotw.server.util.logger


@Serializable
data class LeagueGameHandlerState(
    var playerInGameTimes: MutableMap<String, Float> = mutableMapOf(),
    var playerSaveGuids: MutableMap<String, MoodGuid> = mutableMapOf(),
)


@Serializable
class LeagueGameHandlerClientState


class LeagueGameHandler(multiverseId: Long, server: WotwBackendServer) :
    GameHandler<Nothing>(multiverseId, server) {

    private var state = NormalGameHandlerState()

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
        multiverseEventBus.register(this, PlayerLeftEvent::class) { event ->
            val currentInGameTime = state.playerInGameTimes[event.player.id.value] ?: 0f
            if (currentInGameTime > 0f && canSubmit(event.player)) {
                createSubmission(event.player, null)
            }
        }

        multiverseEventBus.register(this, MultiverseEvent::class) { message ->
            when (message.event) {
                "forfeit" -> {
                    if (canSubmit(message.sender)) {
                        createSubmission(message.sender, null)
                    }
                }
            }
        }

        messageEventBus.register(this, SetPlayerSaveGuidMessage::class) { message, playerId ->
            // Don't override existing GUID if there's already one.
            // Resetting and starting a new save file is not allowed
            val guid = state.playerSaveGuids.getOrPut(playerId) { return@getOrPut message.playerSaveGuid }

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

    private fun didSubmitForThisGame(user: User): Boolean {
        return getLeagueGame().submissions.any { it.membership.user.id == user.id }
    }

    override suspend fun canJoin(user: User): Boolean {
        // Allow joining if user is part of the league season
        return newSuspendedTransaction {
            isLeagueSeasonMember(user) && getLeagueGame().isCurrent
        }
    }

    suspend fun canSubmit(user: User): Boolean {
        return !didSubmitForThisGame(user) && isLeagueSeasonMember(user) && getLeagueGame().isCurrent
    }

    suspend fun createSubmission(user: User, time: Float?, saveFile: ByteArray = ByteArray(0)) {
        newSuspendedTransaction {
            val membership = getLeagueSeasonMembership(user)

            if (membership == null) {
                logger().error("LeagueGameHandler ($multiverseId): Tried to create a submission for user $user but did not find a membership")
                return@newSuspendedTransaction
            }

            val submission = LeagueGameSubmission.new {
                this.game = getLeagueGame()
                this.membership = membership
                this.time = time
                this.saveFile = saveFile
            }

            submission.flush()
        }
    }

    override suspend fun generateStateAggregationRegistry(world: World): AggregationStrategyRegistry {
        // We don't sync anything in League games
        return AggregationStrategyRegistry()
    }

    override suspend fun getPlayerSaveGuid(playerId: PlayerId): MoodGuid? {
        return state.playerSaveGuids[playerId]
    }

    override fun canSpectate(player: User): Boolean {
        return false
    }
}
