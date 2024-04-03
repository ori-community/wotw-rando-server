package wotw.server.database.model

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import wotw.server.database.StringEntity
import wotw.server.database.StringEntityClass
import wotw.server.database.StringIdTable

object Users : StringIdTable("users") {
    val name = text("nickname")
    val isCustomName = bool("is_custom_name").default(false)
    val avatarId = text("avatar_id").nullable()
    val isDeveloper = bool("is_developer").default(false)

    override val primaryKey = PrimaryKey(id)
}

class User(id: EntityID<String>) : StringEntity(id) {
    companion object : StringEntityClass<User>(Users)

    var name by Users.name
    var isCustomName by Users.isCustomName
    var avatarId by Users.avatarId
    var isDeveloper by Users.isDeveloper
    val worlds by World via WorldMemberships
    val multiverses by Multiverse via WorldMemberships
    val worldMemberships by WorldMembership referrersOn WorldMemberships.userId

    val mostRecentWorldMembership
        get() = worldMemberships.orderBy(WorldMemberships.createdAt to SortOrder.DESC).limit(1).singleOrNull()
    val mostRecentMultiverse
        get() = mostRecentWorldMembership?.multiverse

    override fun toString(): String {
        return "$name (${id.value})"
    }
}
