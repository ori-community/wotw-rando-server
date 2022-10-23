package wotw.server.game.handlers

import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protoBuf
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.database.model.Multiverse
import wotw.server.database.model.World
import wotw.server.game.GameConnectionHandler
import wotw.server.game.handlers.hideandseek.HideAndSeekGameHandler
import wotw.server.game.handlers.infection.InfectionGameHandler
import wotw.server.main.WotwBackendServer
import wotw.util.EventBus
import wotw.util.EventBusWithMetadata
import wotw.util.biMapOf
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

typealias PlayerId = String

object GameHandlerType {
    const val NORMAL = 0
    const val HIDE_AND_SEEK = 1
    const val INFECTION = 2
}

abstract class GameHandler<CLIENT_INFO_TYPE : Any>(
    val multiverseId: Long,
    val server: WotwBackendServer,
) {
    protected val messageEventBus = EventBusWithMetadata<PlayerId>()
    protected val multiverseEventBus = EventBus()

    suspend fun onMessage(message: Any, sender: PlayerId) {
        messageEventBus.send(message, sender)
    }

    suspend fun onMultiverseEvent(message: Any) {
        multiverseEventBus.send(message)
    }

    open fun serializeState(): String? = null

    open suspend fun restoreState(serializedState: String?) {

    }

    open suspend fun getAdditionalDebugInformation(): String? = null

    abstract suspend fun generateStateAggregationRegistry(world: World): AggregationStrategyRegistry

    open fun start() {}
    open fun stop() {}

    open suspend fun onGameConnectionSetup(connectionHandler: GameConnectionHandler) {}

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

    open fun getClientInfo(): CLIENT_INFO_TYPE? {
        return null
    }

    private fun serializeClientInfo(clientInfo: CLIENT_INFO_TYPE): ByteArray {
        val serializer = serializer(clientInfo::class.createType())
        return protoBuf.encodeToByteArray(serializer, clientInfo)
    }

    fun getSerializedClientInfo(): ByteArray {
        return getClientInfo()?.let {
            serializeClientInfo(it as CLIENT_INFO_TYPE)
        } ?: ByteArray(0)
    }

    companion object {
        private val handlerTypeMap = biMapOf(
            GameHandlerType.NORMAL to NormalGameHandler::class,
            GameHandlerType.HIDE_AND_SEEK to HideAndSeekGameHandler::class,
            GameHandlerType.INFECTION to InfectionGameHandler::class,
        )

        fun getByGameHandlerByType(type: Int): KClass<out GameHandler<out Any>> {
            return handlerTypeMap[type] ?: throw IllegalArgumentException("Could not get game handler for type '$type'")
        }

        fun getByGameHandlerType(handler: KClass<out GameHandler<out Any>>): Int {
            return handlerTypeMap.inverse[handler] ?: throw IllegalArgumentException()
        }
    }
}