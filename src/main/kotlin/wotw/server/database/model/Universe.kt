package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import wotw.server.bingo.UberStateMap

object Universes : LongIdTable() {
    val multiverseId = reference("multiverse_id", Multiverses)
    val name = varchar("name", 255)
}

class Universe(id: EntityID<Long>) : LongEntity(id) {
    var multiverse by Multiverse referencedOn Universes.multiverseId
    var name by Universes.name
    val worlds by World referrersOn Worlds.universeId

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

    companion object : LongEntityClass<World>(Worlds) {
        fun find(multiverseId: Long, playerId: String) =
            findAll(multiverseId, playerId).firstOrNull()

        fun findAll(multiverseId: Long, playerId: String) =
            Worlds
                .innerJoin(WorldMemberships)
                .innerJoin(Universes)
                .select {
                    (Universes.multiverseId eq multiverseId) and (WorldMemberships.playerId eq playerId)
                }.map { World.wrapRow(it) }

        fun findAll(playerId: String) =
            Worlds
                .innerJoin(WorldMemberships)
                .innerJoin(Universes)
                .select {
                    WorldMemberships.playerId eq playerId
                }.map { World.wrapRow(it) }

        fun new(universe: Universe, player: User) =
            GameState.new {
                this.multiverse = universe.multiverse
                this.universe = universe
                val world = World.new {
                    this.universe = universe
                    this.name = player.name + "'s world"
                }
                this.world = world
                uberStateData = UberStateMap()
            }.world
    }
}


object WorldMemberships : LongIdTable() {
    val worldId = reference("world_id", Worlds, ReferenceOption.CASCADE)
    val playerId = reference("user_id", Users, ReferenceOption.CASCADE)

    init {
        uniqueIndex(playerId)
    }
}

//FIXME: Rename to Census while @zre is not looking :)
//DONTFIXME: oriNo
class WorldMembership(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<WorldMembership>(WorldMemberships)

    var world by World referencedOn WorldMemberships.worldId
    var player by User referencedOn WorldMemberships.playerId
}
