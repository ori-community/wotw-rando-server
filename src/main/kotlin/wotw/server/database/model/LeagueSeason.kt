package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime
import wotw.server.util.assertTransaction
import kotlin.math.max
import kotlin.math.min

object LeagueSeasons : LongIdTable() {
    val name = varchar("name", 64)
    val scheduleStartAt = datetime("schedule_start_at")
    val scheduleEndAt = datetime("schedule_end_at")
    val scheduleCron = varchar("schedule_cron", 64)
    val currentGameId = optReference("current_game_id", LeagueGames, ReferenceOption.CASCADE)

    /**
     * Base points awarded for submitting a run
     */
    val basePoints = integer("base_points").default(10)

    /**
     * Points awarded to the fastest runner
     */
    val speedPoints = integer("speed_points").default(190)

    /**
     * Speed points are awarded until x times the fastest time
     */
    val speedPointsRangeFactor = float("speed_points_range_factor").default(2.5f)

    /**
     * Number of worst scores to discard when calculating total points
     */
    val discardWorstGamesCount = integer("discard_worst_games_count").default(2)
}

class LeagueSeason(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<LeagueSeason>(LeagueSeasons)

    var name by LeagueSeasons.name
    var scheduleStartAt by LeagueSeasons.scheduleStartAt
    var scheduleEndAt by LeagueSeasons.scheduleEndAt
    var scheduleCron by LeagueSeasons.scheduleCron
    var basePoints by LeagueSeasons.basePoints
    var speedPoints by LeagueSeasons.speedPoints
    var speedPointsRangeFactor by LeagueSeasons.speedPointsRangeFactor
    var discardWorstGamesCount by LeagueSeasons.discardWorstGamesCount
    var currentGame by LeagueGame optionalReferencedOn LeagueSeasons.currentGameId
    val games by LeagueGame referrersOn LeagueGames.seasonId
    val memberships by LeagueSeasonMembership referrersOn LeagueSeasonMemberships.seasonId

    // Allow joining before the first game has ended
    val canJoin get() = games.count() <= 1L && currentGame == games.firstOrNull()

    fun recalculateMembershipPoints() {
        assertTransaction()

        val gamesCount = games.count().toInt()

        memberships.forEach { membership ->
            val submissions = membership.submissions.sortedBy { it.points }
            val worstSubmissionsToDiscardCount = min(
                max(
                    0,
                    discardWorstGamesCount - (gamesCount - submissions.count())
                ),
                submissions.count(),
            )

            submissions.take(worstSubmissionsToDiscardCount).forEach { submission ->
                submission.discarded = true
            }

            val countingSubmissions = submissions.drop(worstSubmissionsToDiscardCount)
            countingSubmissions.forEach { submission ->
                submission.discarded = false
            }

            membership.points = countingSubmissions.sumOf { it.points }
        }
    }
}
