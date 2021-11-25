package wotw.server.database.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import wotw.io.messages.SeedGenConfig
import wotw.server.bingo.UberStateMap
import wotw.server.database.jsonb

object Seeds : LongIdTable("seed") {
    @OptIn(InternalSerializationApi::class)
    val generatorConfig = jsonb("generator_config", SeedGenConfig::class.serializer())
    val creator = optReference("creator", Users)
    val created = datetime("created_at").defaultExpression(CurrentDateTime())
    val name = text("name")
}

class Seed(id: EntityID<Long>): LongEntity(id){
    companion object : LongEntityClass<Seed>(Seeds)

    var name by Seeds.name
    var generatorConfig by Seeds.generatorConfig
    var creator by User optionalReferencedOn Seeds.creator
}