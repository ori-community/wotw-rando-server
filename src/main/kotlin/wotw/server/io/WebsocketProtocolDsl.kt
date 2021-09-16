package wotw.server.io

import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import wotw.server.api.WotwUserPrincipal
import wotw.server.exception.UnauthorizedException
import wotw.util.EventBus
import kotlin.reflect.KClass

class ClientSocketProtocolBuilder(val socketConnection: ClientConnection, internal val eventBus: EventBus) {
    var errorHandler: (suspend (Throwable) -> Unit)? = null
        private set
    var closeHandler: (suspend (ClosedReceiveChannelException) -> Unit)? = null
        private set
    var afterAuthenticatedHandler: (suspend () -> Unit)? = null
        private set
    val principalOrNull: WotwUserPrincipal?
        get() = socketConnection.principal
    val principal: WotwUserPrincipal
        get() = socketConnection.principal ?: throw UnauthorizedException()

    fun afterAuthenticated(block: suspend () -> Unit) {
        afterAuthenticatedHandler = block
    }

    fun onError(block: (suspend (Throwable) -> Unit)) {
        errorHandler = block
    }

    fun onClose(block: suspend (ClosedReceiveChannelException) -> Unit) {
        closeHandler = block
    }

    fun <T : Any> onMessage(type: KClass<T>, block: suspend T.() -> Unit) {
        eventBus.register(this, type, block)
    }
}


suspend fun WebSocketSession.handleClientSocket(block: ClientSocketProtocolBuilder.() -> Unit) {
    val eventBus = EventBus()
    val builder = ClientSocketProtocolBuilder(ClientConnection(this, eventBus), eventBus)

    block(builder)

    try {
        builder.socketConnection.listen(builder.errorHandler, builder.afterAuthenticatedHandler)
    } catch (e: ClosedReceiveChannelException) {
        builder.closeHandler?.invoke(e)
    } catch (e: Throwable) {
        builder.errorHandler?.invoke(e)
    }
}