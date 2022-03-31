package wotw.server.game.handlers

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.Multiverse
import wotw.util.EventBusWithMetadata
import wotw.util.biMapOf
import kotlin.reflect.KClass

typealias PlayerId = String

abstract class GameHandler(
    val multiverseId: Long,
) {
    protected val messageEventBus = EventBusWithMetadata<PlayerId>()

    suspend fun onMessage(message: Any, sender: PlayerId) {
        messageEventBus.send(message, sender)
    }

    open fun serializeState(): String? {
        return null
    }

    open fun restoreState(serializedState: String?) {

    }

    open fun start() {}
    open fun stop() {}

    /**
     * Return false if the game handler must not be destroyed currently.
     * e.g. when a game is active and the handler has an active scheduler.
     * If you return false here, the state of the handler can be serialized
     * and the handler will be stopped and destroyed.
     */
    open fun isDisposable(): Boolean {
        return true
    }

    suspend fun persistState() {
        newSuspendedTransaction {
            Multiverse.findById(multiverseId)?.let { multiverse ->
                multiverse.gameHandlerStateJson = serializeState()
            }
        }
    }

    companion object {
        private val handlerTypeMap = biMapOf(
            "normal" to HideAndSeekGameHandler::class, // TODO
            "bingo" to BingoGameHandler::class,
            "hide_and_seek" to NormalGameHandler::class, // TODO
        )
        fun getByGameHandlerByType(type: String): KClass<out GameHandler> {
            return handlerTypeMap[type] ?: throw IllegalArgumentException("Could not get game handler for type '$type'")
        }

        fun getByGameHandlerType(handler: KClass<out GameHandler>): String {
            return handlerTypeMap.inverse[handler] ?: throw IllegalArgumentException()
        }
    }
}