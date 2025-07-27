package wotw.server.database.model

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import wotw.server.database.StringEntity
import wotw.server.database.StringEntityClass
import wotw.server.database.StringIdTable

object BingothonTokens : StringIdTable("bingothon_tokens") {
    val owner = reference("owner_id", Users, ReferenceOption.CASCADE, ReferenceOption.CASCADE)
    val multiverseId = reference("multiverse_id", Multiverses, ReferenceOption.CASCADE)
    val created = datetime("created_at").defaultExpression(CurrentDateTime)

    init {
        uniqueIndex(owner, multiverseId)
    }
}

class BingothonToken(id: EntityID<String>): StringEntity(id){
    companion object : StringEntityClass<BingothonToken>(BingothonTokens)

    var owner by User referencedOn BingothonTokens.owner
    var multiverse by Multiverse referencedOn BingothonTokens.multiverseId
    var created by BingothonTokens.created
}
