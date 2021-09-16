package wotw.server.api

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.Multiverse
import wotw.server.database.model.User
import wotw.server.io.ClientConnection
import wotw.server.sync.ShareScope
import wotw.server.util.logger
import wotw.util.MultiMap
import java.util.*

class ConnectionRegistry {
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

    /*
    * A Map (MultiverseId?, PlayerId) -> WebSocketConnection
    * If MultiverseId == null then Socket listens to newest
    * */
    val playerObserverConnections =
        MultiMap<Pair<Long?, String>, ClientConnection>(Collections.synchronizedMap(hashMapOf()))

    val playerMultiverseConnections = Collections.synchronizedMap(hashMapOf<String, PlayerConnection>())

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

    fun registerMultiverseConn(socket: ClientConnection, playerId: String, multiverseId: Long? = null) =
        run { playerMultiverseConnections[playerId] = PlayerConnection(socket, multiverseId) }

    fun unregisterMultiverseConn(playerId: String) = playerMultiverseConnections.remove(playerId)

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

    // region Convenience sending functions
    suspend inline fun <reified T : Any> sendTo(
        multiverseId: Long,
        playerId: String,
        scope: ShareScope = ShareScope.PLAYER,
        excludePlayer: Boolean = false,
        vararg messages: T,
        unreliable: Boolean = false,
    ) {
        val targets: MutableCollection<String> = newSuspendedTransaction {
            val multiverse = Multiverse.findById(multiverseId) ?: return@newSuspendedTransaction mutableSetOf()
            val player = User.findById(playerId) ?: return@newSuspendedTransaction mutableSetOf()

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

        toPlayers(targets, multiverseId, unreliable, *messages)
    }

    suspend inline fun <reified T : Any> toPlayers(
        players: Iterable<String>,
        multiverseId: Long? = null,
        unreliable: Boolean = false,
        message: T
    ) =
        toPlayers(players, multiverseId, unreliable, *arrayOf(message))

    suspend inline fun <reified T : Any> toPlayers(
        players: Iterable<String>,
        multiverseId: Long? = null,
        unreliable: Boolean = false,
        vararg messages: T
    ) {
        for (player in players) {
            playerMultiverseConnections[player]?.let { conn ->
                for (message in messages) {
                    try {
                        if (multiverseId == null || multiverseId == conn.multiverseId)
                            conn.clientConnection.sendMessage(message, unreliable)
                    } catch (e: Throwable) {
                        println(e)
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
        println(multiverseId)
        multiverseObserverConnections[multiverseId].filter { !spectatorsOnly || it.spectating }.forEach { (conn, _) ->
            for (message in messages) {
                try {
                    conn.sendMessage(message, false)
                } catch (e: Throwable) {
                    println(e)
                }
            }
        }
    }

    suspend inline fun <reified T : Any> toObservers(multiverseId: Long, playerId: String, message: T) =
        toObservers(multiverseId, playerId, *arrayOf(message))

    suspend inline fun <reified T : Any> toObservers(multiverseId: Long, playerId: String, vararg messages: T) {
        var conns: Set<ClientConnection> = playerObserverConnections[multiverseId to playerId]
        if (newSuspendedTransaction {
                User.findById(playerId)?.latestBingoMultiverse?.id?.value == multiverseId
            })
            conns = conns + playerObserverConnections[null to playerId]

        conns.forEach { conn ->
            for (message in messages) {
                try {
                    conn.sendMessage(message, false)
                } catch (e: Throwable) {
                    println(e)
                }
            }
        }
    }

    // endregion
}