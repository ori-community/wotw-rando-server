package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import wotw.io.messages.protobuf.UniverseInfo
import wotw.io.messages.protobuf.WorldInfo
import wotw.server.bingo.UberStateMap

object Universes : LongIdTable() {
    val multiverseId = reference("multiverse_id", Multiverses)
    val name = varchar("name", 255)
}

class Universe(id: EntityID<Long>) : LongEntity(id) {
    var multiverse by Multiverse referencedOn Universes.multiverseId
    var name by Universes.name
    val worlds by World referrersOn Worlds.universeId
    val universeInfo: UniverseInfo
        get() = UniverseInfo(id.value, name, worlds.map { it.worldInfo })

    val state: GameState?
        get() = GameState.findUniverseState(id.value)

    companion object : LongEntityClass<Universe>(Universes)
}

object Worlds : LongIdTable() {
    val universeId = reference("universe_id", Universes)
    val name = varchar("name", 255)
}

class World(id: EntityID<Long>) : LongEntity(id) {
    var universe by Universe referencedOn Worlds.universeId
    var name by Worlds.name
    var members by User via WorldMemberships
    var state by GameState optionalReferencedOn GameStates.worldId

    companion object : LongEntityClass<World>(Worlds) {
        fun find(gameId: Long, playerId: String) =
            Worlds
                .innerJoin(WorldMemberships)
                .innerJoin(Universes)
                .select {
                    (Universes.multiverseId eq gameId) and (WorldMemberships.playerId eq playerId)
                }.map { World.wrapRow(it) }.firstOrNull()

        fun new(universe: Universe, player: User) =
            GameState.new {
                this.multiverse = universe.multiverse
                this.universe = universe
                val world = World.new {
                    this.universe = universe
                    this.name = player.name + "'s team"
                }
                this.world = world
                WorldMemberShip.new {
                    this.player = player
                    this.world = world
                }
                uberStateData = UberStateMap()
            }.world
    }

    val worldInfo: WorldInfo
        get() = WorldInfo(id.value, name, members.map { it.userInfo })
}


object WorldMemberships : LongIdTable() {
    val teamId = reference("world_id", Worlds, ReferenceOption.CASCADE)
    val playerId = reference("user_id", Users, ReferenceOption.CASCADE)

    init {
        uniqueIndex(teamId, playerId)
    }
}

class WorldMemberShip(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<WorldMemberShip>(WorldMemberships)

    var world by World referencedOn WorldMemberships.teamId
    var player by User referencedOn WorldMemberships.playerId
}
