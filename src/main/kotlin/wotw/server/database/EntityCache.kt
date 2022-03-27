package wotw.server.database

import io.ktor.features.*
import kotlinx.html.currentTimeMillis
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityHook
import org.jetbrains.exposed.dao.toEntity
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.World
import wotw.server.database.model.WorldMembership
import wotw.server.database.model.WorldMemberships
import java.util.*

open class Cache<KEY : Any, VALUE : Any>(
    private val retrieve: suspend (KEY) -> VALUE?,
    private val save: suspend (KEY, VALUE) -> Unit
) {

    protected data class CacheEntry<T>(var value: T, var lastAccess: Long)

    private val cache: MutableMap<KEY, CacheEntry<VALUE?>> = Collections.synchronizedMap(hashMapOf())

    suspend fun get(key: KEY) = cache.getOrPut(key) {
        CacheEntry(retrieve(key) ?: throw NotFoundException(), currentTimeMillis())
    }.let {
        it.lastAccess = currentTimeMillis()
        it.value ?: throw NotFoundException()
    }

    suspend fun getOrNull(key: KEY) = cache.getOrPut(key) {
        CacheEntry(retrieve(key), currentTimeMillis())
    }.let {
        it.lastAccess = currentTimeMillis()
        it.value
    }

    fun put(key: KEY, value: VALUE?) = CacheEntry(value, currentTimeMillis()).let {
        cache[key] = it
        it.value
    }

    fun invalidate(key: KEY) {
        cache.remove(key)
    }

    suspend fun purge(before: Long) {
        newSuspendedTransaction {
            val expired = cache.filter { it.value.lastAccess < before }
            cache -= expired.keys

            expired.forEach {
                try {
                    it.value.value?.also { entity ->
                        save(it.key, entity)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}

@Serializable
data class PlayerUniversePopulationCacheEntry(
    val playerId: String,
    val worldId: Long?,
    val universeMemberIds: Set<String>,
    val worldMemberIds: Set<String>,
)

class PlayerUniversePopulationCache : Cache<String, PlayerUniversePopulationCacheEntry>({ playerId ->
    newSuspendedTransaction {
        val membership = WorldMembership.find {
            WorldMemberships.playerId eq playerId
        }.firstOrNull()

        PlayerUniversePopulationCacheEntry(
            playerId,
            membership?.world?.id?.value,
            membership?.world?.universe?.members?.map { it.id.value }?.toSet() ?: emptySet(),
            membership?.world?.members?.map { it.id.value }?.toSet() ?: emptySet(),
        )
    }
}, { _, _ -> }) {
    init {
        EntityHook.subscribe {
            it.toEntity(WorldMembership.Companion)?.world?.universe?.members?.forEach { player ->
                invalidate(player.id.value)
            }

            it.toEntity(World.Companion)?.let { world ->
                world.members.forEach { player ->
                    invalidate(player.id.value)
                }
            }
        }
    }
}