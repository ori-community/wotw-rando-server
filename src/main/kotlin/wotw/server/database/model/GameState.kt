package wotw.server.database.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.and
import wotw.io.messages.protobuf.UberId
import wotw.server.api.*
import wotw.server.api.UberStateSyncStrategy.NotificationGroup.NONE
import wotw.server.bingo.UberStateMap
import wotw.server.bingo.universeStateAggregationRegistry
import wotw.server.bingo.worldStateAggregationRegistry
import wotw.server.database.jsonb

object GameStates : LongIdTable() {
    val multiverseId = reference("multiverse_id", Multiverses, ReferenceOption.CASCADE)
    val universeId = optReference("universe_id", Universes, ReferenceOption.CASCADE)
    val worldId = optReference("world_id", Worlds, ReferenceOption.CASCADE)

    @OptIn(InternalSerializationApi::class)
    val uberStateData = jsonb("uber_state_data", UberStateMap::class.serializer())

    init {
        uniqueIndex(multiverseId, universeId, worldId)
    }
}

class GameState(id: EntityID<Long>) : LongEntity(id) {
    var multiverse by Multiverse referencedOn GameStates.multiverseId
    var universe by Universe optionalReferencedOn GameStates.universeId
    var world by World optionalReferencedOn GameStates.worldId
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
        }.singleOrNull()

        fun findUniverseState(universeId: Long) = find {
            (GameStates.universeId eq universeId and GameStates.worldId.isNull())
        }.singleOrNull()

        fun findWorldState(worldId: Long) = find {
            (GameStates.worldId eq worldId)
        }.singleOrNull()
    }

    enum class Type {
        MULTIVERSE,
        UNIVERSE,
        WORLD,
        INVALID,
    }
}

fun Multiverse.generateStateAggregationRegistry(): AggregationStrategyRegistry {
    val bingoStates = board?.goals?.flatMap { it.value.keys }
        ?.map { UberId(it.first, it.second) } ?: emptySet()

    var aggregationRegistry = AggregationStrategyRegistry().register(
        sync(bingoStates).notify(NONE)
    )
    aggregationRegistry += worldStateAggregationRegistry
    aggregationRegistry += universeStateAggregationRegistry

    return aggregationRegistry
}