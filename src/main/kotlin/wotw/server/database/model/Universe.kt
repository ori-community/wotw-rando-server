package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import wotw.server.bingo.UberStateMap

object Universes : LongIdTable("universes") {
    val multiverseId = reference("multiverse_id", Multiverses, ReferenceOption.CASCADE)
    val name = varchar("name", 255)
}

class Universe(id: EntityID<Long>) : LongEntity(id) {
    var multiverse by Multiverse referencedOn Universes.multiverseId
    var name by Universes.name
    val worlds by World referrersOn Worlds.universeId
    val members
        get() = worlds.flatMap { it.members }.toSet()

    val state: GameState?
        get() = GameState.findUniverseState(id.value)

    companion object : LongEntityClass<Universe>(Universes)
}

object Worlds : LongIdTable("worlds") {
    val universeId = reference("universe_id", Universes, ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val seed = reference("world_seed", WorldSeeds).nullable()
    val hasCustomName = bool("has_custom_name").default(false)
}

class World(id: EntityID<Long>) : LongEntity(id) {
    var universe by Universe referencedOn Worlds.universeId
    var name by Worlds.name
    var seed by WorldSeed optionalReferencedOn Worlds.seed
    val members by User optionalReferrersOn Users.currentWorldId
    val hasCustomName by Worlds.hasCustomName

    companion object : LongEntityClass<World>(Worlds) {
        fun new(universe: Universe, name: String, seed: WorldSeed? = null): World {
            val gameState = GameState.new {
                this.multiverse = universe.multiverse
                this.universe = universe
                val world = World.new {
                    this.universe = universe
                    this.name = name
                    this.seed = seed
                }
                this.world = world
                uberStateData = UberStateMap()
                world.flush()
            }

            gameState.flush()

            return gameState.world!!
        }
    }
}
