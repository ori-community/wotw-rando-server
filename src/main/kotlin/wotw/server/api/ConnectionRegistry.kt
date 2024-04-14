package wotw.server.api

import io.ktor.websocket.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.RequestFullUpdate
import wotw.server.database.model.Multiverse
import wotw.server.database.model.User
import wotw.server.database.model.WorldMembership
import wotw.server.io.ClientConnection
import wotw.server.main.WotwBackendServer
import wotw.server.sync.ShareScope
import wotw.server.util.logger
import wotw.server.util.randomString
import wotw.util.MultiMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ConnectionRegistry(val server: WotwBackendServer) {
    val logger = logger()

    companion object {
        enum class OriType {
            WillOfTheWisps,
            BlindForest,
        }
    }

    data class PlayerConnection(
        val clientConnection: ClientConnection,
        val worldMembershipId: Long?,
        var raceReady: Boolean,
        val oriType: OriType,
    )

    data class MultiverseObserverConnection(
        val socketConnection: ClientConnection,
        val playerId: String?,
        var spectating: Boolean,
    )

    val multiverseObserverConnections =
        //       ↓ multiverseId
        MultiMap<Long, MultiverseObserverConnection>(ConcurrentHashMap())

    data class RemoteTrackerEndpoint(
        var broadcasterConnection: ClientConnection?,
        val listeners: CopyOnWriteArrayList<ClientConnection> = CopyOnWriteArrayList(),
        var expires: Long? = null,
    )

    // endpointId => RemoteTrackerEndpoint
    val remoteTrackerEndpoints: ConcurrentHashMap<String, RemoteTrackerEndpoint> = ConcurrentHashMap()

    // userId => endpointId
    val remoteTrackerEndpointIds: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    /*
    * A Map (MultiverseId, PlayerId) -> WebSocketConnection
    * */
    val playerObserverConnections =
        MultiMap<Pair<Long, String>, ClientConnection>(ConcurrentHashMap())

    // WorldMembership ID -> Player Connection
    val playerMultiverseConnections: ConcurrentHashMap<Long, PlayerConnection> = ConcurrentHashMap()

    //region Connection registering

    fun registerObserverConnection(
        socket: ClientConnection,
        multiverseId: Long,
        playerId: String,
        spectator: Boolean = false
    ) {
        multiverseObserverConnections[multiverseId] += MultiverseObserverConnection(socket, playerId, spectator)
        playerObserverConnections[multiverseId to playerId] += socket
    }

    suspend fun broadcastMultiverseInfoMessage(worldMembershipId: Long) {
        WorldMembership.findById(worldMembershipId)?.let { worldMembership ->
            broadcastMultiverseInfoMessage(worldMembership.multiverse)
        }
    }

    suspend fun broadcastMultiverseInfoMessage(multiverse: Multiverse) {
        val message = server.infoMessagesService.generateMultiverseInfoMessage(multiverse)

        toPlayers(
            multiverse.memberships.map { it.id.value },
            message,
        )

        toObservers(multiverse.id.value, spectatorsOnly = false, message)
    }

    suspend fun registerMultiverseConnection(socket: ClientConnection, worldMembershipId: Long, oriType: OriType) =
        run {
            unregisterMultiverseConnection(worldMembershipId)

            playerMultiverseConnections[worldMembershipId] = PlayerConnection(socket, worldMembershipId, false, oriType)
            newSuspendedTransaction {
                broadcastMultiverseInfoMessage(worldMembershipId)
            }
        }

    suspend fun unregisterMultiverseConnection(worldMembershipId: Long) = run {
        val playerConnection = playerMultiverseConnections.remove(worldMembershipId)

        playerConnection?.worldMembershipId?.let {
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

    fun unregisterObserverConnection(socket: ClientConnection, multiverseId: Long, playerId: String) {
        playerObserverConnections[multiverseId to playerId] -= socket
    }

    fun setSpectating(multiverseId: Long, playerId: String, spectating: Boolean) {
        multiverseObserverConnections[multiverseId].filter { it.playerId == playerId }
            .map { it.spectating = spectating }
    }

    suspend fun registerRemoteTrackerEndpoint(
        clientConnection: ClientConnection,
        userId: String,
        reusePreviousEndpointId: Boolean,
        useStaticEndpointId: Boolean
    ): String {
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
                    it.expires =
                        System.currentTimeMillis() + 30 * 60 * 1000 // Keep the endpoint around for at least 30 minutes...
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
        worldMembershipId: Long,
        scope: ShareScope = ShareScope.PLAYER,
        excludePlayer: Boolean = false,
        vararg messages: T,
        unreliable: Boolean = false,
    ) {
        val targets: Iterable<Long> = newSuspendedTransaction {
            val worldMembership = WorldMembership.findById(worldMembershipId) ?: return@newSuspendedTransaction mutableSetOf()
            val multiverse = worldMembership.multiverse

            val affectedPlayers: Iterable<WorldMembership> = when (scope) {
                ShareScope.PLAYER -> setOf(worldMembership)
                ShareScope.WORLD -> multiverse.worlds.firstOrNull { worldMembership in it.memberships }?.memberships?.toList()
                    ?: emptySet()

                ShareScope.UNIVERSE -> multiverse.universes.firstOrNull { it.worlds.any { worldMembership in it.memberships } }?.worlds?.flatMap { it.memberships }
                    ?: emptySet()

                ShareScope.MULTIVERSE -> multiverse.memberships
            }

            val targets = affectedPlayers.map { it.id.value }.toMutableList()

            if (excludePlayer) {
                targets -= worldMembership.id.value
            }

            targets
        }

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
        worldMembershipIds: Iterable<Long>,
        message: T,
        unreliable: Boolean = false,
    ) = toPlayers(worldMembershipIds, listOf(message), unreliable)

    suspend inline fun <reified T : Any> toPlayers(
        worldMembershipIds: Iterable<Long>,
        messages: List<T>,
        unreliable: Boolean = false,
    ) {
        for (worldMembershipId in worldMembershipIds) {
            playerMultiverseConnections[worldMembershipId]?.let { conn ->
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
        val targetConnections: Set<ClientConnection> = playerObserverConnections[multiverseId to playerId]

        targetConnections.forEach { conn ->
            for (message in messages) {
                try {
                    conn.sendMessage(message, false)
                } catch (e: Throwable) {
                    logger.error(e.message, e)
                }
            }
        }
    }

    suspend fun notifyUserInfoChanged(playerId: String) {
        newSuspendedTransaction {
            val user = User.findById(playerId)

            user?.multiverses?.forEach {
                broadcastMultiverseInfoMessage(it)
            }
        }
    }

    // endregion
}
