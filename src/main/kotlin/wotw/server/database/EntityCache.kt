package wotw.server.database

import io.ktor.server.plugins.*
import kotlinx.html.currentTimeMillis
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.Multiverse
import wotw.server.database.model.WorldMembership
import java.util.concurrent.ConcurrentHashMap

open class EntityCache<KEY : Any, VALUE : Any>(
    private val retrieve: suspend (KEY) -> VALUE?,
    private val save: suspend (KEY, VALUE) -> Unit
) {

    protected data class CacheEntry<T>(var value: T, var lastAccess: Long)

    private val cache: ConcurrentHashMap<KEY, CacheEntry<VALUE?>> = ConcurrentHashMap()

    suspend fun get(key: KEY): VALUE {
        return cache.getOrPut(key) {
            CacheEntry(retrieve(key) ?: throw NotFoundException(), currentTimeMillis())
        }.let {
            it.lastAccess = currentTimeMillis()
            it.value ?: throw NotFoundException()
        }
    }

    suspend fun getOrNull(key: KEY): VALUE? {
        cache[key]?.let { cacheEntry ->
            return cacheEntry.value
        }

        // Don't cache null values
        retrieve(key)?.let { cachedValue ->
            return cache.getOrPut(key) {
                CacheEntry(cachedValue, currentTimeMillis())
            }.let {
                it.lastAccess = currentTimeMillis()
                it.value
            }
        }

        return null
    }

    suspend fun put(key: KEY, value: VALUE?) = CacheEntry(value, currentTimeMillis()).let {
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

    suspend fun persist(key: KEY) {
        try {
            cache[key]?.value?.also { entity ->
                save(key, entity)
            }
            cache -= key
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Serializable
data class WorldMembershipEnvironmentCacheEntry(
    val worldMembershipId: Long,
    val playerId: String,
    val worldId: Long?,
    val universeWorldMembershipIds: Set<Long>,
    val worldWorldMembershipIds: Set<Long>,
)

@Serializable
data class MultiverseMemberCacheEntry(
    val multiverseId: Long,
    val playerAndSpectatorPlayerIds: Set<String>,
    val worldMembershipIds: Set<Long>,
)

class WorldMembershipEnvironmentCache : EntityCache<Long, WorldMembershipEnvironmentCacheEntry>({ worldMembershipId ->
    val retrieveFn = suspend {
        val worldMembership = WorldMembership.findById(worldMembershipId)

        WorldMembershipEnvironmentCacheEntry(
            worldMembershipId,
            worldMembership?.user?.id?.value ?: "<invalid cache>",
            worldMembership?.world?.id?.value,
            worldMembership?.world?.universe?.memberships?.map { it.id.value }?.toSet() ?: emptySet(),
            worldMembership?.world?.memberships?.map { it.id.value }?.toSet() ?: emptySet(),
        )
    }

    if (TransactionManager.currentOrNull() == null) {
        newSuspendedTransaction {
            retrieveFn()
        }
    } else {
        retrieveFn()
    }
}, { _, _ -> })

class MultiverseMemberCache : EntityCache<Long, MultiverseMemberCacheEntry>({ multiverseId ->
    val retrieveFn = suspend {
        val multiverse = Multiverse.findById(multiverseId)

        MultiverseMemberCacheEntry(
            multiverseId,
            multiverse?.playersAndSpectators?.map { it.id.value }?.toSet() ?: emptySet(),
            multiverse?.memberships?.map { it.id.value }?.toSet() ?: emptySet(),
        )
    }

    if (TransactionManager.currentOrNull() == null) {
        newSuspendedTransaction {
            retrieveFn()
        }
    } else {
        retrieveFn()
    }
}, { _, _ -> })
