package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import kotlin.time.Duration.Companion.milliseconds

object LeagueGameSubmissions : LongIdTable("league_game_submissions") {
    val gameId = reference("game_id", LeagueGames, ReferenceOption.CASCADE)
    val membershipId = reference("membership_id", LeagueSeasonMemberships, ReferenceOption.CASCADE)
    val submittedAt = timestamp("submitted_at").defaultExpression(CurrentTimestamp)
    val saveFile = binary("save_file", 512 * 1024).nullable()
    val time = float("time").nullable()
    val points = integer("points").default(0)
    val rank = integer("rank").nullable()
    val videoUrl = varchar("video_url", 128).nullable()
    val rankingMultiplier = float("rankingMultiplier").default(1.0f)
    val validated = bool("validated").default(false)
    val autoValidationErrors = text("auto_validation_error").nullable()

    init {
        uniqueIndex(gameId, membershipId)
    }
}

class LeagueGameSubmission(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<LeagueGameSubmission>(LeagueGameSubmissions)

    var game by LeagueGame referencedOn LeagueGameSubmissions.gameId
    var membership by LeagueSeasonMembership referencedOn LeagueGameSubmissions.membershipId
    var submittedAt by LeagueGameSubmissions.submittedAt
    var saveFile by LeagueGameSubmissions.saveFile
    var time by LeagueGameSubmissions.time
    var points by LeagueGameSubmissions.points
    var rank by LeagueGameSubmissions.rank
    var videoUrl by LeagueGameSubmissions.videoUrl
    var rankingMultiplier by LeagueGameSubmissions.rankingMultiplier
    var validated by LeagueGameSubmissions.validated
    var autoValidationErrors by LeagueGameSubmissions.autoValidationErrors

    val formattedTime: String get() {
        return this.time?.let { time ->
            (time * 1000f).toLong().milliseconds.toComponents { hours, minutes, seconds, nanoseconds ->
                var result = ""

                if (hours > 0) {
                    result += "$hours:"
                }

                result += "${minutes.toString().padStart(2, '0')}:"
                result += "${seconds.toString().padStart(2, '0')}."
                result += (nanoseconds / 1_000_000).toString().padStart(3, '0')

                result
            }
        } ?: "-"
    }
}
