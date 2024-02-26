package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import wotw.server.database.model.LeagueSeasonMemberships.defaultExpression
import wotw.server.util.assertTransaction
import kotlin.math.ceil
import kotlin.math.max

object LeagueGames : LongIdTable() {
    val seasonId = reference("season_id", LeagueSeasons, ReferenceOption.CASCADE)
    val multiverseId = reference("multiverse_id", Multiverses, ReferenceOption.CASCADE).uniqueIndex()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

class LeagueGame(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<LeagueGame>(LeagueGames)

    var season by LeagueSeason referencedOn LeagueGames.seasonId
    var multiverse by Multiverse referencedOn LeagueGames.multiverseId
    var createdAt by LeagueGames.createdAt
    val submissions by LeagueGameSubmission referrersOn LeagueGameSubmissions.gameId

    val isCurrent get() = season.currentGame?.id?.value == this.id.value

    fun recalculateSubmissionPoints() {
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
    }
}
