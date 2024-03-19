package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.timestamp
import wotw.server.database.model.BingothonTokens.defaultExpression
import wotw.server.database.model.LeagueGame.Companion.referrersOn
import wotw.server.database.model.RaceTeamMembers.nullable

object LeagueSeasonMemberships : LongIdTable("league_season_memberships") {
    val seasonId = reference("season_id", LeagueSeasons, ReferenceOption.CASCADE)
    val userId = reference("user_id", Users, ReferenceOption.CASCADE)
    val joinedAt = timestamp("joined_at").defaultExpression(CurrentTimestamp())
    val points = integer("points").default(0)
}

class LeagueSeasonMembership(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<LeagueSeasonMembership>(LeagueSeasonMemberships)

    var season by LeagueSeason referencedOn LeagueSeasonMemberships.seasonId
    var user by User referencedOn LeagueSeasonMemberships.userId
    var joinedAt by LeagueSeasonMemberships.joinedAt
    var points by LeagueSeasonMemberships.points
    val submissions by LeagueGameSubmission referrersOn LeagueGameSubmissions.membershipId
}
