package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import wotw.server.util.assertTransaction
import kotlin.math.ceil
import kotlin.math.max

object LeagueGames : LongIdTable("league_games") {
    val gameNumber = integer("game_number")
    val seasonId = reference("season_id", LeagueSeasons, ReferenceOption.CASCADE)
    val multiverseId = reference("multiverse_id", Multiverses, ReferenceOption.CASCADE).uniqueIndex()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
    val discordSpoilerThreadId = ulong("discord_thread_id").nullable()
    val reminderSent = bool("reminder_sent").default(false)

    init {
        uniqueIndex(gameNumber, seasonId)
    }
}

class LeagueGame(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<LeagueGame>(LeagueGames)

    var gameNumber by LeagueGames.gameNumber
    var season by LeagueSeason referencedOn LeagueGames.seasonId
    var multiverse by Multiverse referencedOn LeagueGames.multiverseId
    var createdAt by LeagueGames.createdAt
    val submissions by LeagueGameSubmission referrersOn LeagueGameSubmissions.gameId
    var discordSpoilerThreadId by LeagueGames.discordSpoilerThreadId
    var reminderSent by LeagueGames.reminderSent

    val isCurrent get() = season.currentGame?.id?.value == this.id.value

    fun recalculateSubmissionPointsAndRanks() {
        assertTransaction()

        val cachedSubmissions = submissions.toList()

        val lowestTime = cachedSubmissions
            .filter { it.time != null }
            .minOfOrNull { it.time!! }

        if (lowestTime == null) {  // There's no valid submission, everyone gets 0 points
            cachedSubmissions.forEach { submission -> submission.points = 0 }
            return
        }

        val speedPointsRange = lowestTime * season.speedPointsRangeFactor - lowestTime

        cachedSubmissions.forEach { submission ->
            var points = 0

            submission.time?.let { time ->
                points += season.basePoints

                val speedPoints = max(0, ceil(season.speedPoints - season.speedPoints * ((time - lowestTime) / speedPointsRange)).toInt())
                points += speedPoints
            }

            submission.points = points
        }

        val submissionsGroupedByPointsInDescendingOrder = cachedSubmissions
            .groupBy { it.points }
            .toSortedMap()
            .values
            .reversed()

        var nextRank = 1
        for (submissionsWithSamePoints in submissionsGroupedByPointsInDescendingOrder) {
            for (submission in submissionsWithSamePoints) {
                val newRank = if (submission.time == null) {
                    null
                } else {
                    nextRank
                }

                submission.rank = newRank
            }

            nextRank += submissionsWithSamePoints.size
        }
    }
}
