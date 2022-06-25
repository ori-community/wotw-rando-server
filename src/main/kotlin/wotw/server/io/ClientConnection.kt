package wotw.server.io

import com.auth0.jwt.impl.JWTParser
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.Payload
import io.ktor.server.auth.jwt.*
import io.ktor.websocket.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.*
import wotw.server.api.WotwUserPrincipal
import wotw.server.database.model.User
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.server.util.makeServerTextMessage
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

class ClientConnection(val webSocket: WebSocketServerSession, val eventBus: EventBus) {
    var udpId: Int? = null
    var udpAddress: SocketAddress? = null
    val udpKey = ByteArray(16)

    var principal: WotwUserPrincipal? = null
        private set

    suspend inline fun <reified T : Any> handleUdpMessage(datagram: Datagram, message: T) {
        if (udpAddress != datagram.address) {
            logger().info("ClientConnection: Updated UDP remote address of connection $udpId to ${datagram.address}")
            udpAddress = datagram.address
        }

        eventBus.send(message)
    }

    suspend fun listen(errorHandler: (suspend (Throwable) -> Unit)?, afterAuthenticatedHandler: (suspend () -> Unit)?) {
        try {
            for (frame in webSocket.incoming) {
                if (frame is Frame.Binary) {
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
                                    val user = User.findById(it.userId)!!
                                    UserInfo(
                                        user.id.value,
                                        user.name,
                                        user.avatarId,
                                        null,
                                        user.currentMultiverse?.id?.value,
                                        user.isDeveloper
                                    )
                                }

                                logger().info("ClientConnection: User ${userInfo.name} (${userInfo.id}) authenticated a WebSocket connection")

                                sendMessage(AuthenticatedMessage(userInfo, udpId!!, udpKey))
                                afterAuthenticatedHandler?.invoke()
                            } ?: logger().info("ClientConnection: Authentication failed. Could not validate JWT.")
                        } else {
                            logger().warn("ClientConnection: Cannot authenticate twice in a WebSocket connection")
                        }
                    } else if (principal != null) {
                        eventBus.send(message)
                    } else {
                        logger().debug("Received message of type ${message::class.qualifiedName} while the socket was unauthenticated")
                        sendMessage(
                            makeServerTextMessage("Authentication failed.\nPlease login again in the launcher.", 10f)
                        )
                        webSocket.close()
                        return
                    }
                }
            }

            throw ClosedReceiveChannelException("Channel closed")
        } catch (e: Throwable) {
            udpId?.let {
                ClientConnectionUDPRegistry.unregister(it)
            }

            if (e !is CancellationException) {
                e.printStackTrace()
            }

            throw e
        }
    }

    private fun generateUdpKey() {
        for (i in 0 until udpKey.size - 1) {
            udpKey[i] = Math.random().times(255).toInt().toByte()
        }
    }

    suspend inline fun <reified T : Any> sendMessage(
        message: T,
        unreliable: Boolean = false,
        ignoreAuthentication: Boolean = false
    ) {
        if (principal != null || ignoreAuthentication) {
            val binaryData =
                Packet.serialize(message) ?: throw IOException("Cannot serialize object: $message | ${message::class}")

            if (unreliable) {
                if (udpAddress == null) {
                    logger().trace("ClientConnection: Could not sent ${message::class.qualifiedName}, no remote address.")
                    return
                }

                WotwBackendServer.udpSocket?.let {
                    logger().trace("ClientConnection: Sending packet of type ${message::class.qualifiedName} to $udpAddress")
                    it.send(
                        Datagram(
                            ByteReadPacket(
                                UdpPacket.serialize(
                                    UdpPacket.fromPacketData(
                                        null,
                                        binaryData,
                                        udpKey
                                    )
                                )
                            ), udpAddress!!
                        )
                    )
                }
            } else {
                webSocket.send(Frame.Binary(true, binaryData))
            }
        } else {
            logger().debug("ClientConnection: Packet of type ${message::class.qualifiedName} has been discarded. Authentication is required but websocket is not authenticated.")
        }
    }
}