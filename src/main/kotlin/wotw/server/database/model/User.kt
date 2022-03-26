package wotw.server.database.model

import org.jetbrains.exposed.dao.id.EntityID
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
    val games by Multiverse via GameStates

    val currentMultiverse: Multiverse?
        get() = WorldMembership.find {
            WorldMemberships.playerId eq id.value
        }.firstOrNull()?.world?.universe?.multiverse
}