package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

object BingoEvents : LongIdTable("events") {
    override val primaryKey = PrimaryKey(id)
    val gameId = reference("game_id", Games, ReferenceOption.CASCADE)
    val teamId = reference("team_id", Teams, ReferenceOption.CASCADE)
    val time = long("timestamp")
    val x = integer("x")
    val y = integer("y")
    val manual = bool("manual")

}

class BingoEvent(id: EntityID<Long>) : LongEntity(id) {
    var game by Game referencedOn BingoEvents.gameId
    var team by Team referencedOn BingoEvents.teamId
    var x by BingoEvents.x
    var y by BingoEvents.y
    var time by BingoEvents.time
    var manual by BingoEvents.manual

    companion object : LongEntityClass<BingoEvent>(BingoEvents)
}