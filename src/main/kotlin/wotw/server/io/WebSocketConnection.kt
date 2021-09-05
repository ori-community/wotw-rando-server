package wotw.server.io

import com.auth0.jwt.impl.JWTParser
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.Payload
import io.ktor.auth.jwt.*
import io.ktor.http.cio.websocket.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.errors.*
import kotlinx.serialization.SerializationException
import wotw.io.messages.protobuf.Authenticate
import wotw.io.messages.protobuf.Packet
import wotw.server.api.WotwUserPrincipal
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.util.EventBus
import java.util.*

// ktor WHYYYY
fun DecodedJWT.parsePayload(): Payload {
    val payloadString = String(Base64.getUrlDecoder().decode(payload))
    return JWTParser().parsePayload(payloadString)
}

class WebSocketConnection(val socket: WebSocketSession, var needsAuthentication: Boolean = false) {
    var principal: WotwUserPrincipal? = null
        private set

    suspend fun listen(eventBus: EventBus, errorHandler: (suspend (Throwable) -> Unit)?, afterAuthenticatedHandler: (suspend () -> Unit)?) {
        for (frame in socket.incoming) {
            if (frame is Frame.Binary) {
                try {
                    val message = Packet.deserialize(frame.data) ?: continue

                    if (message is Authenticate) {
                        if (principal == null) {
                            val verifier = WotwBackendServer.getJwtVerifier()
                            val decodedJwt = verifier.verify(message.jwt)
                            val credential = JWTCredential(decodedJwt.parsePayload())
                            principal = WotwBackendServer.validateJwt(credential)
                            afterAuthenticatedHandler?.invoke()
                        } else {
                            logger().warn("Cannot authenticate twice in a WebSocket connection")
                        }
                    } else if (!needsAuthentication || principal != null) {
                        eventBus.send(message)
                    } else {
                        socket.close()
                        return
                    }
                } catch (e: SerializationException) {
                    e.printStackTrace()
                    errorHandler?.invoke(e)
                }
            }
        }
    }

    suspend inline fun <reified T : Any> sendMessage(message: T) {
        val binaryData = Packet.serialize(message) ?: throw IOException("Cannot serialize object: $message | ${message::class}")
        socket.send(Frame.Binary(true, binaryData))
    }
}