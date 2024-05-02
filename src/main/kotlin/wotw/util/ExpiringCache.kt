package wotw.util

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class ExpiringCache<K, V>(val ttl: Duration) {
    private data class CacheEntry<V> (
        val value: V,
        val lastAccess: Instant = Clock.System.now(),
    )

    private val cache: ConcurrentHashMap<K, CacheEntry<V>> = ConcurrentHashMap()

    val values get() = cache.values.map { it.value }

    suspend fun getOrPut(key: K, defaultValue: suspend () -> V): V {
        return cache.getOrPut(key) {
            CacheEntry(defaultValue())
        }.value
    }

    suspend fun getOrTryPut(key: K, defaultValue: suspend () -> V?): V? {
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
