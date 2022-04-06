package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

object Spectators : LongIdTable() {
    val gameId = reference("multiverse_id", Multiverses, ReferenceOption.CASCADE)
    val playerId = reference("user_id", Users, ReferenceOption.CASCADE)

    init {
        uniqueIndex(Spectators.gameId, Spectators.playerId)
    }
}

class Spectator(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<Spectator>(Spectators)

    var multiverse by Multiverse referencedOn Spectators.gameId
    var player by User referencedOn Spectators.playerId
}
