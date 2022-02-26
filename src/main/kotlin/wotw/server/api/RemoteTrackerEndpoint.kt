package wotw.server.api

import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.websocket.*
import wotw.io.messages.protobuf.ResetTracker
import wotw.io.messages.protobuf.SetTrackerEndpointId
import wotw.io.messages.protobuf.TrackerFlagsUpdate
import wotw.io.messages.protobuf.TrackerUpdate
import wotw.server.io.handleClientSocket
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger

class RemoteTrackerEndpoint(server: WotwBackendServer) : Endpoint(server) {
    val logger = logger()
    override fun Route.initRouting() {
        webSocket("remote-tracker") {
            handleClientSocket {
                var endpointId: String? = null

                afterAuthenticated {
                    if (!principal.hasScope(Scope.MULTIVERSE_CONNECT))
                        this@webSocket.close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY,
                                "You are not allowed to connect with these credentials!"
                            )
                        )

                    endpointId = server.connections.registerRemoteTrackerEndpoint(
                        this.socketConnection,
                        principal.userId,
                        call.request.queryParameters["reconnect"] == "true",
                        call.request.queryParameters["static"] == "true",
                    )

                    this.socketConnection.sendMessage(SetTrackerEndpointId(endpointId!!))
                }

                onMessage(TrackerUpdate::class) {
                    if (endpointId != null) {
                        server.connections.broadcastRemoteTrackerMessage(endpointId!!, this)
                    }
                }

                onMessage(ResetTracker::class) {
                    if (endpointId != null) {
                        server.connections.broadcastRemoteTrackerMessage(endpointId!!, this)
                    }
                }

                onMessage(TrackerFlagsUpdate::class) {
                    if (endpointId != null) {
                        server.connections.broadcastRemoteTrackerMessage(endpointId!!, this)
                    }
                }

                onClose {
                    logger.info("Remote tracker WebSocket $endpointId disconnected (close, ${closeReason.await()})")
                    if (endpointId != null) {
                        server.connections.unregisterRemoteTrackerBroadcaster(endpointId!!)
                    }
                }
                onError {
                    logger.info("Remote tracker WebSocket $endpointId disconnected (error, ${closeReason.await()})")
                    if (endpointId != null) {
                        server.connections.unregisterRemoteTrackerBroadcaster(endpointId!!)
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
