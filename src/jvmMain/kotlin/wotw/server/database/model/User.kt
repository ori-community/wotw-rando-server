package wotw.server.database.model

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.select
import wotw.io.messages.protobuf.UserInfo
import wotw.server.database.StringEntity
import wotw.server.database.StringEntityClass
import wotw.server.database.StringIdTable

object Users : StringIdTable("users") {
    val name = text("nickname")
    val isCustomName = bool("is_custom_name").default(false)
    val avatarId = text("avatar_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

class User(id: EntityID<String>) : StringEntity(id) {
    companion object : StringEntityClass<User>(Users)

    var name by Users.name
    var isCustomName by Users.isCustomName
    var avatarId by Users.avatarId
    val games by Game via GameStates

    val latestBingoGame: Game?
        get() = (TeamMemberships innerJoin Teams innerJoin Games).slice(Teams.id, Games.id).select {
            TeamMemberships.playerId eq id.value
        }.sortedByDescending {
            it[Teams.id]
        }.firstOrNull()?.get(Games.id)?.let { Game.findById(it) }

    val userInfo: UserInfo
        get() = UserInfo(id.value, name, avatarId)
}