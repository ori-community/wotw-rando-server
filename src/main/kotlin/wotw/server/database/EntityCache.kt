package wotw.server.database

import io.ktor.features.*
import kotlinx.html.currentTimeMillis
import org.jetbrains.exposed.dao.EntityHook
import org.jetbrains.exposed.dao.toEntity
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.WorldMembership
import wotw.server.database.model.WorldMemberships
import java.util.*

open class Cache<KEY : Any, ENTITY : Any>(
    private val retrieve: suspend (KEY) -> ENTITY?,
    private val save: suspend (KEY, ENTITY) -> Unit
) {

    protected data class CacheEntry<T>(var entity: T, var lastAccess: Long)

    protected val cache: MutableMap<KEY, CacheEntry<ENTITY>> = Collections.synchronizedMap(hashMapOf())

    suspend fun get(key: KEY) = cache.getOrPut(key) {
        CacheEntry(retrieve(key) ?: throw NotFoundException(), currentTimeMillis())
    }.let {
        it.lastAccess = currentTimeMillis()
        it.entity
    }

    fun invalidate(key: KEY) {
        val entity = cache.remove(key)
    }

    suspend fun purge(after: Long) {
        val expired = cache.filter { it.value.lastAccess > after }
        cache -= expired.keys

        expired.forEach {
            try {
                save(it.key, it.value.entity)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

class PlayerUniversePopulationCache : Cache<Pair<String, Long>, Set<String>>({ (playerId, worldId) ->
    newSuspendedTransaction {
        val membership = WorldMembership.find {
            (WorldMemberships.worldId eq worldId) and (WorldMemberships.playerId eq playerId)
        }.firstOrNull() ?: return@newSuspendedTransaction emptySet()
        membership.world.universe.worlds.flatMap { it.members }.map { it.id.value }.toSet()
    }
}, { _, _ -> }) {
    suspend fun get(playerId: String, worlId: Long) = get(playerId to worlId)

    init {
        EntityHook.subscribe {
            val membership = it.toEntity(WorldMembership.Companion)
            if (membership != null) {
                val affectedWorld = membership.world.id.value
                val affectedPlayers = membership.world.universe.worlds.flatMap { it.members }.map { it.id.value }
                affectedPlayers.forEach {
                    invalidate(it to affectedWorld)
                }
            }
        }
    }
}

