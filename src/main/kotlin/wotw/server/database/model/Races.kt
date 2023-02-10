package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import wotw.server.database.model.Races.nullable

object Races : LongIdTable("races") {
    val finishedTime = float("finished_time").nullable()
}

class Race(id: EntityID<Long>): LongEntity(id){
    companion object : LongEntityClass<Race>(Races)

    val teams by RaceTeam referrersOn RaceTeams.raceId
    var finishedTime by Races.finishedTime
}

object RaceTeams : LongIdTable("race_teams") {
    val raceId = reference("race_id", Races, ReferenceOption.CASCADE)
    val finishedTime = float("finished_time").nullable()
    val points = integer("points").default(0)
}

class RaceTeam(id: EntityID<Long>): LongEntity(id){
    companion object : LongEntityClass<RaceTeam>(RaceTeams)

    var race by Race referencedOn RaceTeams.raceId
    val members by RaceTeamMember referrersOn RaceTeamMembers.raceTeamId
    var finishedTime by RaceTeams.finishedTime
    var points by RaceTeams.points
}

object RaceTeamMembers : LongIdTable("race_team_members") {
    val raceTeamId = reference("race_team_id", RaceTeams, ReferenceOption.CASCADE)
    val userId = reference("user_id", Users, ReferenceOption.CASCADE)
    val finishedTime = float("finished_time").nullable()
}

class RaceTeamMember(id: EntityID<Long>): LongEntity(id){
    companion object : LongEntityClass<RaceTeamMember>(RaceTeamMembers)

    var raceTeam by RaceTeam referencedOn RaceTeamMembers.raceTeamId
    var user by User referencedOn RaceTeamMembers.userId
    var finishedTime by RaceTeamMembers.finishedTime
}

