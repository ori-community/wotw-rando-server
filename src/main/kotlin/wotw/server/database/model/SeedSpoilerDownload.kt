package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

object SeedSpoilerDownloads : LongIdTable() {
    val seedId = reference("seed_id", Seeds, ReferenceOption.CASCADE)
    val playerId = reference("user_id", Users, ReferenceOption.CASCADE)

    init {
        uniqueIndex(SeedSpoilerDownloads.seedId, SeedSpoilerDownloads.playerId)
    }
}

class SeedSpoilerDownload(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<SeedSpoilerDownload>(SeedSpoilerDownloads)

    var seed by Seed referencedOn SeedSpoilerDownloads.seedId
    var player by User referencedOn SeedSpoilerDownloads.playerId
}
