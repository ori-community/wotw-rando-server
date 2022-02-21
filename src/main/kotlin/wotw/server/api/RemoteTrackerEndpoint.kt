package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.websocket.*
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.MultiverseCreationConfig
import wotw.io.messages.protobuf.*
import wotw.server.bingo.BingoBoardGenerator
import wotw.server.bingo.UberStateMap
import wotw.server.sync.multiStates
import wotw.server.sync.worldStateAggregationRegistry
import wotw.server.database.model.*
import wotw.server.exception.ConflictException
import wotw.server.io.handleClientSocket
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.server.util.rezero
import wotw.server.util.then
import wotw.server.util.zerore

class RemoteTrackerEndpoint(server: WotwBackendServer) : Endpoint(server) {
    val logger = logger()
    override fun Route.initRouting() {
        webSocket("remote-tracker") {
            handleClientSocket {
                var endpointId: String? = null

                afterAuthenticated {
                    principalOrNull?.hasScope(Scope.MULTIVERSE_CONNECT) ?: this@webSocket.close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "You are not allowed to connect with these credentials!"
                        )
                    )

                    endpointId = server.connections.registerRemoteTrackerEndpoint(this.socketConnection)

                    this.socketConnection.sendMessage(SetTrackerEndpointId(endpointId))
                }

                onMessage(Any::class) {
                    if (endpointId != null) {
                        server.connections.broadcastRemoteTrackerMessage(endpointId!!, this)
                    }
                }

                onClose {
                    logger.info("Remote tracker WebSocket $endpointId disconnected (close, ${closeReason.await()})")
                    if (endpointId != null) {
                        server.connections.unregisterRemoteTrackerEndpoint(endpointId!!)
                    }
                }
                onError {
                    logger.info("Remote tracker WebSocket $endpointId disconnected (error, ${closeReason.await()})")
                    if (endpointId != null) {
                        server.connections.unregisterRemoteTrackerEndpoint(endpointId!!)
                    }
                }
            }
        }

        webSocket("remote-tracker/{endpoint_id}") {
            handleClientSocket {
                val endpointId = call.parameters["endpoint_id"].orEmpty()

                if (!server.connections.registerRemoteTrackerListener(endpointId, this.socketConnection)) {
                    this.socketConnection.webSocket.close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "Invalid endpoint ID"
                        )
                    )
                }

                onClose {
                    logger.info("Remote tracker listener disconnected from endpoint $endpointId (close, ${closeReason.await()})")
                    server.connections.unregisterRemoteTrackerListener(endpointId, this.socketConnection)
                }
                onError {
                    logger.info("Remote tracker listener disconnected from endpoint $endpointId (close, ${closeReason.await()})")
                    server.connections.unregisterRemoteTrackerListener(endpointId, this.socketConnection)
                }
            }
        }
    }
}
