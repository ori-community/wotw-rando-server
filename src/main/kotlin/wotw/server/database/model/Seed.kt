package wotw.server.database.model

import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import wotw.io.messages.SeedGenConfig
import wotw.server.database.jsonb

object SeedGroups : LongIdTable("seed_groups") {
    val generatorConfig = jsonb("generator_config", serializer<SeedGenConfig>())
    val creator = optReference("creator_id", Users)
    val created = datetime("created_at").defaultExpression(CurrentDateTime())
    val file = varchar("file", 64).default("")
}

class SeedGroup(id: EntityID<Long>): LongEntity(id){
    companion object : LongEntityClass<SeedGroup>(SeedGroups)

    var file by SeedGroups.file
    var generatorConfig by SeedGroups.generatorConfig
    var creator by User optionalReferencedOn SeedGroups.creator
    val seeds by Seed referrersOn Seeds.group
}

object Seeds : LongIdTable("seeds") {
    val file = varchar("file", 64)
    val group = reference("seed_group_id", SeedGroups)
}

class Seed(id: EntityID<Long>): LongEntity(id){
    companion object : LongEntityClass<Seed>(Seeds)

    var file by Seeds.file
    var group by SeedGroup referencedOn Seeds.group
}