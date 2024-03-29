package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.json.jsonb
import wotw.io.messages.json
import wotw.server.bingo.UberStateMap

object GameStates : LongIdTable("game_states") {
    val multiverseId = reference("multiverse_id", Multiverses, ReferenceOption.CASCADE)
    val universeId = optReference("universe_id", Universes, ReferenceOption.CASCADE)
    val worldId = optReference("world_id", Worlds, ReferenceOption.CASCADE)
    val playerId = optReference("player_id", Users, ReferenceOption.CASCADE)
    val uberStateData = jsonb<UberStateMap>("uber_state_data", json)

    init {
        uniqueIndex(multiverseId, universeId, worldId, playerId)
    }
}

class GameState(id: EntityID<Long>) : LongEntity(id) {
    var multiverse by Multiverse referencedOn GameStates.multiverseId
    var universe by Universe optionalReferencedOn GameStates.universeId
    var world by World optionalReferencedOn GameStates.worldId
    var player by User optionalReferencedOn GameStates.playerId
    var uberStateData by GameStates.uberStateData

    val type: Type
        get() = when {
            world != null -> Type.WORLD
            universe != null -> Type.UNIVERSE
            else -> Type.MULTIVERSE
        }

    companion object : LongEntityClass<GameState>(GameStates) {
        fun findMultiverseState(multiverseId: Long) = find {
            (GameStates.multiverseId eq multiverseId and GameStates.universeId.isNull() and GameStates.worldId.isNull())
        }.firstOrNull()

        fun findUniverseState(universeId: Long) = find {
            (GameStates.universeId eq universeId and GameStates.worldId.isNull())
        }.firstOrNull()

        fun findWorldState(worldId: Long) = find {
            (GameStates.worldId eq worldId)
        }.firstOrNull()

        fun findPlayerState(playerId: String) = find {
            (GameStates.playerId eq playerId)
        }.firstOrNull()
    }

    enum class Type {
        MULTIVERSE,
        UNIVERSE,
        WORLD,
    }
}
