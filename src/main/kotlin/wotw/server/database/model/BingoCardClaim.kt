package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

object BingoCardClaims : LongIdTable("bingo_card_claims") {
    val multiverseId = reference("multiverse_id", Multiverses, ReferenceOption.CASCADE)
    val universeId = reference("world_id", Universes, ReferenceOption.CASCADE)
    val time = long("timestamp")
    val x = integer("x")
    val y = integer("y")
    val manual = bool("manual")

    init {
        index(true, universeId, x, y)
    }
}

class BingoCardClaim(id: EntityID<Long>) : LongEntity(id) {
    var multiverse by Multiverse referencedOn BingoCardClaims.multiverseId
    var universe by Universe referencedOn BingoCardClaims.universeId
    var x by BingoCardClaims.x
    var y by BingoCardClaims.y
    var time by BingoCardClaims.time
    var manual by BingoCardClaims.manual

    companion object : LongEntityClass<BingoCardClaim>(BingoCardClaims)
}
