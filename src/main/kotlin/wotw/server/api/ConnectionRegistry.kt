package wotw.server.api

import io.ktor.http.cio.websocket.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.RequestFullUpdate
import wotw.server.database.model.Multiverse
import wotw.server.database.model.User
import wotw.server.io.ClientConnection
import wotw.server.main.WotwBackendServer
import wotw.server.sync.ShareScope
import wotw.server.util.logger
import wotw.server.util.randomString
import wotw.util.MultiMap
import java.util.*

class ConnectionRegistry(val server: WotwBackendServer) {
    val logger = logger()

    data class PlayerConnection(val clientConnection: ClientConnection, val multiverseId: Long?)

    data class MultiverseObserverConnection(
        val socketConnection: ClientConnection,
        val playerId: String?,
        var spectating: Boolean,
    )

    val multiverseObserverConnections =
        //       ↓ multiverseId
        MultiMap<Long, MultiverseObserverConnection>(Collections.synchronizedMap(hashMapOf()))

    data class RemoteTrackerEndpoint(
        var broadcasterConnection: ClientConnection?,
        val listeners: MutableList<ClientConnection> = Collections.synchronizedList(mutableListOf()),
        var expires: Long? = null,
    )

    // endpointId => RemoteTrackerEndpoint
    val remoteTrackerEndpoints: MutableMap<String, RemoteTrackerEndpoint> = Collections.synchronizedMap(hashMapOf())

    // userId => endpointId
    val remoteTrackerEndpointIds: MutableMap<String, String> = Collections.synchronizedMap(hashMapOf())

    /*
    * A Map (MultiverseId?, PlayerId) -> WebSocketConnection
    * If MultiverseId == null then Socket listens to newest
    * */
    val playerObserverConnections =
        MultiMap<Pair<Long?, String>, ClientConnection>(Collections.synchronizedMap(hashMapOf()))

    val playerMultiverseConnections = Collections.synchronizedMap(hashMapOf<String, PlayerConnection>())

    //region Connection registering

    fun registerObserverConnection(
        socket: ClientConnection,
        multiverseId: Long? = null,
        playerId: String,
        spectator: Boolean = false
    ) {
        if (multiverseId != null)
            multiverseObserverConnections[multiverseId] += MultiverseObserverConnection(socket, playerId, spectator)

        playerObserverConnections[multiverseId to playerId] += socket
    }

    suspend fun broadcastMultiverseInfoMessage(multiverseId: Long) {
        Multiverse.findById(multiverseId)?.let { multiverse ->
            val message = server.infoMessagesService.generateMultiverseInfoMessage(multiverse)

            toPlayers(
                multiverse.players.map { it.id.value },
                message,
            )

            toObservers(multiverseId, spectatorsOnly = false, message)
        }
    }

    suspend fun registerMultiverseConnection(socket: ClientConnection, playerId: String, multiverseId: Long? = null) =
        run {
            unregisterMultiverseConnection(playerId)

            playerMultiverseConnections[playerId] = PlayerConnection(socket, multiverseId)
            if (multiverseId != null) {
                newSuspendedTransaction {
                    broadcastMultiverseInfoMessage(multiverseId)
                }
            }
        }

    suspend fun unregisterMultiverseConnection(playerId: String) = run {
        val playerConnection = playerMultiverseConnections.remove(playerId)
        playerConnection?.multiverseId?.let {
            newSuspendedTransaction {
                broadcastMultiverseInfoMessage(it)
            }
        }
    }

    fun unregisterAllObserverConnections(socket: ClientConnection, multiverseId: Long) {
        multiverseObserverConnections[multiverseId].removeIf { it.socketConnection == socket }
        playerObserverConnections.filterKeys { it.first == multiverseId }
            .forEach { playerObserverConnections[it.key] -= socket }
    }

    fun unregisterObserverConnection(socket: ClientConnection, multiverseId: Long? = null, playerId: String) {
        playerObserverConnections[multiverseId to playerId] -= socket
    }

    fun setSpectating(multiverseId: Long, playerId: String, spectating: Boolean) {
        multiverseObserverConnections[multiverseId].filter { it.playerId == playerId }.map { it.spectating = spectating }
    }

    suspend fun registerRemoteTrackerEndpoint(clientConnection: ClientConnection, userId: String, reusePreviousEndpointId: Boolean, useStaticEndpointId: Boolean): String {
        cleanupRemoteTrackerEndpoints()

        var endpointId: String

        if (
            reusePreviousEndpointId &&
            remoteTrackerEndpointIds.containsKey(userId) &&
            remoteTrackerEndpoints.containsKey(remoteTrackerEndpointIds[userId])
        ) {
            endpointId = remoteTrackerEndpointIds[userId]!!

            remoteTrackerEndpoints[endpointId]?.broadcasterConnection?.webSocket?.close()
            remoteTrackerEndpoints[endpointId]?.broadcasterConnection = clientConnection

            logger.info("Reconnected broadcaster to Remote Tracker endpoint $endpointId (User $userId)")
        } else {
            if (useStaticEndpointId) {
                endpointId = "u-$userId"
                logger.info("Registered Remote Tracker endpoint $endpointId (static) for user $userId")
            } else {
                do {
                    endpointId = randomString(16)
                } while (remoteTrackerEndpoints.containsKey(endpointId))
                logger.info("Registered Remote Tracker endpoint $endpointId (new) for user $userId")
            }

            remoteTrackerEndpointIds[userId] = endpointId
            remoteTrackerEndpoints[endpointId] = RemoteTrackerEndpoint(
                clientConnection
            )
        }

        remoteTrackerEndpoints[endpointId]?.expires = null

        return endpointId
    }

    suspend fun unregisterRemoteTrackerBroadcaster(endpointId: String) {
        remoteTrackerEndpoints[endpointId]?.broadcasterConnection?.webSocket?.close()
        remoteTrackerEndpoints[endpointId]?.broadcasterConnection = null
        cleanupRemoteTrackerEndpoints()

        logger.info("Unregistered broadcaster from Remote Tracker endpoint $endpointId")
    }

    suspend fun registerRemoteTrackerListener(endpointId: String, clientConnection: ClientConnection): Boolean {
        cleanupRemoteTrackerEndpoints()

        if (remoteTrackerEndpoints.containsKey(endpointId)) {
            remoteTrackerEndpoints[endpointId]?.listeners?.add(clientConnection)
            remoteTrackerEndpoints[endpointId]?.broadcasterConnection?.sendMessage(RequestFullUpdate())
            remoteTrackerEndpoints[endpointId]?.expires = null

            logger.info("Registered listener for Remote Tracker endpoint $endpointId")

            return true
        }
        return false
    }

    fun unregisterRemoteTrackerListener(endpointId: String, clientConnection: ClientConnection) {
        remoteTrackerEndpoints[endpointId]?.listeners?.remove(clientConnection)
        cleanupRemoteTrackerEndpoints()
    }

    private fun cleanupRemoteTrackerEndpoints() {
        remoteTrackerEndpoints.keys.forEach { endpointId ->
            remoteTrackerEndpoints[endpointId]?.let {
                if (it.expires == null) {
                    return@let
                }

                if (it.expires!! < System.currentTimeMillis()) {
                    remoteTrackerEndpoints.remove(endpointId)
                    logger.info("Deleted expired Remote Tracker endpoint $endpointId")
                } else if (it.broadcasterConnection == null && it.listeners.isEmpty()) {
                    it.expires = System.currentTimeMillis() + 30 * 60 * 1000 // Keep the endpoint around for at least 30 minutes...
                    logger.info("Marked Remote Tracker endpoint $endpointId deletable in 30 minutes from now")
                }
            }
        }
    }

    suspend inline fun <reified T : Any> broadcastRemoteTrackerMessage(endpointId: String, message: T) {
        remoteTrackerEndpoints[endpointId]?.listeners?.forEach {
            it.sendMessage(message, ignoreAuthentication = true)
        }
    }
    // endregion

    // region Convenience sending functions
    suspend inline fun <reified T : Any> sendTo(
        playerId: String,
        scope: ShareScope = ShareScope.PLAYER,
        excludePlayer: Boolean = false,
        vararg messages: T,
        unreliable: Boolean = false,
    ) {
        val targets: MutableCollection<String> = newSuspendedTransaction {
            val player = User.findById(playerId) ?: return@newSuspendedTransaction mutableSetOf()
            val multiverse = player.currentMultiverse ?: return@newSuspendedTransaction mutableSetOf()

            val affectedPlayers: Collection<User> = when (scope) {
                ShareScope.PLAYER -> setOf(player)
                ShareScope.WORLD -> multiverse.worlds.firstOrNull { player in it.members }?.members?.toList()
                    ?: emptySet()
                ShareScope.UNIVERSE -> multiverse.universes.firstOrNull { it.worlds.any { player in it.members } }?.worlds?.flatMap { it.members }
                    ?: emptySet()
                ShareScope.MULTIVERSE -> multiverse.players
            }
            affectedPlayers.map { it.id.value }.toMutableList()
        }
        if(excludePlayer)
            targets -= playerId

        toPlayers(targets, messages.toList(), unreliable)
    }

    suspend inline fun <reified T : Any> toAll(
        unreliable: Boolean = false,
        vararg messages: T
    ) {
        playerMultiverseConnections.values.forEach {
            for (message in messages) {
                try {
                    it.clientConnection.sendMessage(message, unreliable)
                } catch (e: Throwable) {
                    logger.error(e.message, e)
                }
            }
        }
    }

    suspend inline fun <reified T : Any> toPlayers(
        players: Iterable<String>,
        message: T,
        unreliable: Boolean = false,
    ) = toPlayers(players, listOf(message), unreliable)

    suspend inline fun <reified T : Any> toPlayers(
        players: Iterable<String>,
        messages: List<T>,
        unreliable: Boolean = false,
    ) {
        for (player in players) {
            playerMultiverseConnections[player]?.let { conn ->
                for (message in messages) {
                    try {
                        conn.clientConnection.sendMessage(message, unreliable)
                    } catch (e: Throwable) {
                        logger.error(e.message, e)
                    }
                }
            }
        }
    }

    suspend inline fun <reified T : Any> toObservers(
        multiverseId: Long,
        spectatorsOnly: Boolean = false,
        message: T
    ) =
        toObservers(multiverseId, spectatorsOnly, *arrayOf(message))

    suspend inline fun <reified T : Any> toObservers(
        multiverseId: Long,
        spectatorsOnly: Boolean,
        vararg messages: T
    ) {
        multiverseObserverConnections[multiverseId].filter { !spectatorsOnly || it.spectating }.forEach { (conn, _) ->
            for (message in messages) {
                try {
                    conn.sendMessage(message, false)
                } catch (e: Throwable) {
                    logger.error(e.message, e)
                }
            }
        }
    }

    suspend inline fun <reified T : Any> toObservers(multiverseId: Long, playerId: String, message: T) =
        toObservers(multiverseId, playerId, *arrayOf(message))

    suspend inline fun <reified T : Any> toObservers(multiverseId: Long, playerId: String, vararg messages: T) {
        var conns: Set<ClientConnection> = playerObserverConnections[multiverseId to playerId]
        if (newSuspendedTransaction {
                User.findById(playerId)?.currentMultiverse?.id?.value == multiverseId
            })
            conns = conns + playerObserverConnections[null to playerId]

        conns.forEach { conn ->
            for (message in messages) {
                try {
                    conn.sendMessage(message, false)
                } catch (e: Throwable) {
                    logger.error(e.message, e)
                }
            }
        }
    }

    suspend fun notifyNicknameChanged(playerId: String) {
        newSuspendedTransaction {
            val user = User.findById(playerId)
            user?.currentMultiverse?.id?.value?.let {
                broadcastMultiverseInfoMessage(multiverseId = it)
            }
        }
    }

    // endregion
}