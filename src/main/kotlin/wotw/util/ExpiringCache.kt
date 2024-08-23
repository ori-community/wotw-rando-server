package wotw.util

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

class ExpiringCache<K, V>(val ttl: Duration) {
    private data class CacheEntry<V> (
        val value: V,
        val lastAccess: Instant = Clock.System.now(),
    )

    private val cache: ConcurrentHashMap<K, CacheEntry<V>> = ConcurrentHashMap()

    val values get() = cache.values.map { it.value }

    suspend fun getOrPutSuspended(key: K, defaultValue: suspend () -> V): V {
        return cache.getOrPut(key) {
            CacheEntry(defaultValue())
        }.value
    }

    suspend fun getOrTryPutSuspended(key: K, defaultValue: suspend () -> V?): V? {
        return cache.getOrElse(key) {
            defaultValue()?.let {
                cache[key] = CacheEntry(it)
                return it
            }

            return null
        }.value
    }

    fun getOrPut(key: K, defaultValue: () -> V): V {
        return cache.getOrPut(key) {
            CacheEntry(defaultValue())
        }.value
    }

    fun getOrTryPut(key: K, defaultValue: () -> V?): V? {
        return cache.getOrElse(key) {
            defaultValue()?.let {
                cache[key] = CacheEntry(it)
                return it
            }

            return null
        }.value
    }

    fun garbageCollect() {
        val expired = cache.filter { it.value.lastAccess < Clock.System.now().minus(ttl) }
        cache -= expired.keys
    }
}
