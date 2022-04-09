package wotw.server.game

import io.ktor.features.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.dao.EntityChangeType
import org.jetbrains.exposed.dao.EntityHook
import org.jetbrains.exposed.dao.toEntity
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.Multiverse
import wotw.server.database.model.User
import wotw.server.database.model.World
import wotw.server.game.handlers.GameHandler
import wotw.server.main.WotwBackendServer
import wotw.server.util.assertTransaction
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.primaryConstructor

data class GameHandlerCacheEntry(
    val handler: GameHandler<out Any>,
    var expires: Long = System.currentTimeMillis() + 1000L * 60L * 30L, // 30 min
) {
    fun isExpired() = expires < System.currentTimeMillis()
    fun isDisposable() = isExpired() && (handler.isDisposable())
    fun refresh() {
        expires = System.currentTimeMillis() + 1000L * 60L * 30L // 30 min
    }
}

class GameHandlerRegistry(val server: WotwBackendServer) {
    private val handlers: ConcurrentHashMap<Long, GameHandlerCacheEntry> = ConcurrentHashMap()

    val cacheEntries: List<GameHandlerCacheEntry>
        get() = handlers.values.toList()

    suspend fun getHandler(multiverseId: Long): GameHandler<out Any> {
        return handlers[multiverseId]?.handler ?: newSuspendedTransaction {
            val multiverse = Multiverse.findById(multiverseId)
            val handler = multiverse?.let { getHandler(it) } ?: throw NotFoundException("Could not load handler for multiverse $multiverseId: Multiverse does not exist")
            val cacheEntry = GameHandlerCacheEntry(handler)
            handlers[multiverseId] = cacheEntry
            cacheEntry
        }.apply { refresh() }.handler
    }

    suspend fun getHandler(multiverse: Multiverse): GameHandler<out Any> {
        assertTransaction()

        return handlers.getOrPut(multiverse.id.value) {
            GameHandlerCacheEntry(
                loadHandler(multiverse).also { it.start() }
            )
        }.apply { refresh() }.handler
    }

    suspend fun cacheAndStartHandler(multiverse: Multiverse) {
        assertTransaction()

        if (!handlers.containsKey(multiverse.id.value)) {
            handlers[multiverse.id.value] = GameHandlerCacheEntry(
                loadHandler(multiverse).also {
                    it.start()
                }
            )
        }
    }

    private suspend fun loadHandler(multiverse: Multiverse): GameHandler<out Any> {
        assertTransaction()

        val type = GameHandler.getByGameHandlerByType(multiverse.gameHandlerType)
        val handler = type.primaryConstructor?.call(multiverse.id.value, server)
            ?: throw RuntimeException("Error while instantiating game handler of type '${multiverse.gameHandlerType}'")

        multiverse.gameHandlerStateJson?.let {
            handler.restoreState(it)
        }

        return handler
    }

    init {
        EntityHook.subscribe {
            runBlocking {
                it.toEntity(World.Companion)?.let { world ->
                    try {
                        when (it.changeType) {
                            EntityChangeType.Created -> {
                                val handler = getHandler(world.universe.multiverse.id.value)
                                handler.onMultiverseEvent(
                                    WorldCreatedEvent(world)
                                )
                            }
                            EntityChangeType.Removed -> getHandler(world.universe.multiverse.id.value).onMultiverseEvent(
                                WorldDeletedEvent(world.id.value)
                            )
                            else -> {}
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // TODO: Player joined/left events in a separate bus
            }
        }
    }
}