package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import wotw.io.messages.protobuf.Vector2
import wotw.server.util.assertTransaction
import wotw.server.util.inverseLerp
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

object LeagueGames : LongIdTable("league_games") {
    val gameNumber = integer("game_number")
    val seasonId = reference("season_id", LeagueSeasons, ReferenceOption.CASCADE)
    val multiverseId = reference("multiverse_id", Multiverses, ReferenceOption.CASCADE).uniqueIndex()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
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

    class PointsCalculator(private val season: LeagueSeason, private val lowestTime: Float) {
        private val highestTime = lowestTime * season.speedPointsRangeFactor

        private val curveStart = Vector2(0f, 1f)
        private val curveControlPoint1 = Vector2(season.speedPointsCurveX, season.speedPointsCurveY)
        private val curveControlPoint2 = Vector2(1f, 0f).lerp(curveControlPoint1, season.speedPointsCurveFalloffFactor)
        private val curveEnd = Vector2(1f, 0f)

        private fun evaluateCurve(t: Float): Float {
            val quadraticPoint1 = curveStart.lerp(curveControlPoint1, t)
            val quadraticPoint2 = curveControlPoint1.lerp(curveControlPoint2, t)
            val quadraticPoint3 = curveControlPoint2.lerp(curveEnd, t)

            val cubicPoint1 = quadraticPoint1.lerp(quadraticPoint2, t)
            val cubicPoint2 = quadraticPoint2.lerp(quadraticPoint3, t)

            return cubicPoint1.lerp(cubicPoint2, t).y
        }

        fun calculateSpeedPoints(time: Float): Int {
            val relativeTime = inverseLerp(lowestTime, highestTime, time)

            if (relativeTime <= 0f) {
                return season.speedPoints
            }

            if (relativeTime >= 1f) {
                return 0
            }

            return ceil(evaluateCurve(relativeTime) * season.speedPoints).toInt()
        }
    }

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

        val pointsCalculator = PointsCalculator(season, lowestTime)

        cachedSubmissions.forEach { submission ->
            var points = 0

            submission.time?.let { time ->
                points += season.basePoints
                points += pointsCalculator.calculateSpeedPoints(time)
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

    fun shouldHideSubmissionsForUnfinishedPlayers(): Boolean {
        return createdAt.isAfter(Instant.now().minus(4, ChronoUnit.HOURS))
    }
}
