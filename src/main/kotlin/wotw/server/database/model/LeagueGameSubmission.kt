package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

object LeagueGameSubmissions : LongIdTable("league_game_submissions") {
    val gameId = reference("game_id", LeagueGames, ReferenceOption.CASCADE)
    val membershipId = reference("membership_id", LeagueSeasonMemberships, ReferenceOption.CASCADE)
    val saveFile = binary("save_file", 512 * 1024)
    val time = float("time").nullable()
    val points = integer("points").default(0)
    val videoUrl = varchar("video_url", 128).nullable()
    val discarded = bool("discarded").default(false)
}

class LeagueGameSubmission(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<LeagueGameSubmission>(LeagueGameSubmissions)

    var game by LeagueGame referencedOn LeagueGameSubmissions.gameId
    var membership by LeagueSeasonMembership referencedOn LeagueGameSubmissions.membershipId
    var saveFile by LeagueGameSubmissions.saveFile
    var time by LeagueGameSubmissions.time
    var points by LeagueGameSubmissions.points
    var videoUrl by LeagueGameSubmissions.videoUrl
    var discarded by LeagueGameSubmissions.discarded
}
