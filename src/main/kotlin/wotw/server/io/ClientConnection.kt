package wotw.server.io

import com.auth0.jwt.impl.JWTParser
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.Payload
import io.ktor.auth.jwt.*
import io.ktor.http.cio.websocket.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerializationException
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.*
import wotw.server.api.WotwUserPrincipal
import wotw.server.database.model.User
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.util.EventBus
import java.lang.Exception
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.text.String

// ktor WHYYYY
fun DecodedJWT.parsePayload(): Payload {
    val payloadString = String(Base64.getUrlDecoder().decode(payload))
    return JWTParser().parsePayload(payloadString)
}

class ClientConnectionUDPRegistry() {
    companion object {
        private val availableUdpIDs = HashSet((0..65535).toList());
        private val connections: HashMap<Int, ClientConnection> = hashMapOf()

        fun register(connection: ClientConnection): Int {
            if (availableUdpIDs.isEmpty()) {
                throw Exception("Well this got big")
            }

            val udpId = availableUdpIDs.first()
            availableUdpIDs.remove(udpId)
            connections[udpId] = connection
            return udpId
        }

        fun unregister(udpId: Int) {
            connections.remove(udpId)
            availableUdpIDs.add(udpId)
        }

        fun getById(udpId: Int): ClientConnection? {
            return connections[udpId]
        }
    }
}

class ClientConnection(val webSocket: WebSocketSession, val eventBus: EventBus) {
    var udpId: Int? = null
    var udpSocket: ConnectedDatagramSocket? = null
    val udpKey = ByteArray(16)

    var principal: WotwUserPrincipal? = null
        private set

    suspend inline fun<reified T : Any> handleUdpMessage(datagram: Datagram, message: T) {
        if (udpSocket == null) {
            udpSocket = aSocket(ActorSelectorManager(Dispatchers.IO)).udp().connect(datagram.address)
        }
        eventBus.send(message)
    }

    suspend fun listen(errorHandler: (suspend (Throwable) -> Unit)?, afterAuthenticatedHandler: (suspend () -> Unit)?) {
        for (frame in webSocket.incoming) {
            if (frame is Frame.Binary) {
                try {
                    val message = Packet.deserialize(frame.data) ?: continue

                    if (message is AuthenticateMessage) {
                        if (principal == null) {
                            val verifier = WotwBackendServer.getJwtVerifier()
                            val decodedJwt = verifier.verify(message.jwt)
                            val credential = JWTCredential(decodedJwt.parsePayload())
                            WotwBackendServer.validateJwt(credential)?.let {
                                principal = it

                                udpId = ClientConnectionUDPRegistry.register(this)
                                generateUdpKey()

                                val userInfo = newSuspendedTransaction {
                                    User.findById(it.userId)!!.userInfo
                                }

                                sendMessage(AuthenticatedMessage(userInfo, udpId!!, udpKey))
                                afterAuthenticatedHandler?.invoke()
                            }
                        } else {
                            logger().warn("Cannot authenticate twice in a WebSocket connection")
                        }
                    } else if (principal != null) {
                        eventBus.send(message)
                    } else {
                        sendMessage(PrintTextMessage(text = "Authentication failed.\nPlease login again in the launcher.", frames = 240, ypos = 3f))
                        webSocket.close()
                        return
                    }
                } catch (e: SerializationException) {
                    e.printStackTrace()
                    errorHandler?.invoke(e)
                }
            }
        }

        udpId?.let {
            ClientConnectionUDPRegistry.unregister(it)
        }

        udpSocket?.dispose()
    }

    private fun generateUdpKey() {
        for (i in 0 until udpKey.size - 1) {
            udpKey[i] = Math.random().times(255).toInt().toByte()
        }
    }

    suspend inline fun <reified T : Any> sendMessage(message: T, unreliable: Boolean = false) {
        if (principal != null) {
            val binaryData = Packet.serialize(message) ?: throw IOException("Cannot serialize object: $message | ${message::class}")

            if (unreliable) {
                udpSocket?.let {
                    it.send(Datagram(ByteReadPacket(binaryData), it.remoteAddress))
                }
            } else {
                webSocket.send(Frame.Binary(true, binaryData))
            }
        } else {
            logger().warn("Packet of type ${message::class} has been discarded. Authentication is required but websocket is not authenticated.")
        }
    }
}