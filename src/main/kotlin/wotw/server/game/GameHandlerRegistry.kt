package wotw.server.game

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.Multiverse
import wotw.server.game.handlers.GameHandler
import wotw.server.util.assertTransaction
import java.util.Collections
import kotlin.reflect.full.primaryConstructor

data class GameHandlerCacheEntry(
    val handler: GameHandler?,
    var expires: Long = System.currentTimeMillis() + 1000L * 60L * 30L, // 30 min
) {
    fun isExpired() = expires < System.currentTimeMillis()
    fun isDisposable() = isExpired() && (handler == null || handler.isDisposable())
    fun refresh() {
        expires = System.currentTimeMillis() + 1000L * 60L * 30L // 30 min
    }
}

class GameHandlerRegistry {
    private val handlers: MutableMap<Long, GameHandlerCacheEntry> = Collections.synchronizedMap(mutableMapOf())

    val cacheEntries: List<GameHandlerCacheEntry>
        get() = handlers.values.toList()

    suspend fun getHandler(multiverseId: Long): GameHandler? {
        return handlers[multiverseId]?.handler ?: newSuspendedTransaction {
            val multiverse = Multiverse.findById(multiverseId)
            val handler = multiverse?.let { getHandler(it) }
            val cacheEntry = GameHandlerCacheEntry(handler)
            handlers[multiverseId] = cacheEntry
            cacheEntry
        }.apply { refresh() }.handler
    }

    fun getHandler(multiverse: Multiverse): GameHandler? {
        assertTransaction()

        return handlers.getOrPut(multiverse.id.value) {
            GameHandlerCacheEntry(loadHandler(multiverse))
        }.apply { refresh() }.handler
    }

    fun cacheAndStartHandler(multiverse: Multiverse) {
        assertTransaction()

        handlers.computeIfAbsent(multiverse.id.value) {
            GameHandlerCacheEntry(
                loadHandler(multiverse).also {
                    it.start()
                }
            )
        }
    }

    private fun loadHandler(multiverse: Multiverse): GameHandler {
        assertTransaction()

        val type = GameHandler.getByGameHandlerByType(multiverse.gameHandlerType)
        val handler = type.primaryConstructor?.call(multiverse.id.value)
            ?: throw RuntimeException("Error while instantiating game handler of type '${multiverse.gameHandlerType}'")

        multiverse.gameHandlerStateJson?.let {
            handler.restoreState(it)
        }

        return handler
    }
}