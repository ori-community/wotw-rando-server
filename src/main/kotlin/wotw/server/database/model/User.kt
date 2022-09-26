package wotw.server.database.model

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ReferenceOption
import wotw.server.database.StringEntity
import wotw.server.database.StringEntityClass
import wotw.server.database.StringIdTable

object Users : StringIdTable("users") {
    val name = text("nickname")
    val isCustomName = bool("is_custom_name").default(false)
    val avatarId = text("avatar_id").nullable()
    val isDeveloper = bool("is_developer").default(false)
    val currentWorldId = optReference("world_id", Worlds, ReferenceOption.SET_NULL, ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(id)
}

class User(id: EntityID<String>) : StringEntity(id) {
    companion object : StringEntityClass<User>(Users)

    var name by Users.name
    var isCustomName by Users.isCustomName
    var avatarId by Users.avatarId
    var isDeveloper by Users.isDeveloper
    var currentWorld by World optionalReferencedOn Users.currentWorldId

    val currentMultiverse: Multiverse?
        get() = currentWorld?.universe?.multiverse
}