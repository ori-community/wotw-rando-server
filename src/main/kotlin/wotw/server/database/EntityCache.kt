package wotw.server.database

import io.ktor.features.*
import kotlinx.html.currentTimeMillis
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.bingo.UberStateMap
import wotw.server.database.model.GameState
import java.util.*

open class Cache<KEY : Any, ENTITY : Any>(
    private val retrieve: suspend (KEY) -> ENTITY?,
    private val save: suspend (KEY, ENTITY) -> Unit
) {

    private data class CacheEntry<T>(var entity: T, var lastAccess: Long)

    private val cache: MutableMap<KEY, CacheEntry<ENTITY>> = Collections.synchronizedMap(hashMapOf())

    suspend fun get(key: KEY) = cache.getOrPut(key) {
        CacheEntry(retrieve(key) ?: throw NotFoundException(), currentTimeMillis())
    }.let {
        it.lastAccess = currentTimeMillis()
        it.entity
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
