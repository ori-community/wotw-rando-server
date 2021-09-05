package wotw.server.api

import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.channels.SendChannel
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.Team
import wotw.server.database.model.User
import wotw.server.io.WebSocketConnection
import wotw.server.util.logger
import wotw.util.MultiMap
import java.util.*

class ConnectionRegistry {
    val logger = logger()

    data class PlayerConn(val socketConnection: WebSocketConnection, val gameId: Long?)

    data class GameObserverConnection(
        val socketConnection: WebSocketConnection,
        val playerId: String?,
        var spectating: Boolean,
    )

    val gameObserverConnections =
        //       ↓ gameId
        MultiMap<Long, GameObserverConnection>(Collections.synchronizedMap(hashMapOf()))

    /*
    * A Map (GameId?, PlayerId) -> WebSocketConnection
    * If GameId == null then Socket listens to newest
    * */
    val playerObserverConnections =
        MultiMap<Pair<Long?, String>, WebSocketConnection>(Collections.synchronizedMap(hashMapOf()))

    val playerGameConnections = Collections.synchronizedMap(hashMapOf<String, PlayerConn>())

    fun registerObserverConnection(
        socket: WebSocketConnection,
        gameId: Long? = null,
        playerId: String,
        spectator: Boolean = false
    ) {
        if (gameId != null)
            gameObserverConnections[gameId] += GameObserverConnection(socket, playerId, spectator)

        playerObserverConnections[gameId to playerId] += socket
    }

    fun registerGameConn(socket: WebSocketConnection, playerId: String, gameId: Long? = null) =
        run { playerGameConnections[playerId] = PlayerConn(socket, gameId) }

    fun unregisterGameConn(playerId: String) = playerGameConnections.remove(playerId)

    fun unregisterAllObserverConnections(socket: WebSocketConnection, gameId: Long) {
        gameObserverConnections[gameId].removeIf { it.socketConnection == socket }
        playerObserverConnections.filterKeys { it.first == gameId }
            .forEach { playerObserverConnections[it.key] -= socket }
    }

    fun unregisterObserverConnection(socket: WebSocketConnection, gameId: Long? = null, playerId: String) {
        playerObserverConnections[gameId to playerId] -= socket
    }

    fun setSpectating(gameId: Long, playerId: String, spectating: Boolean) {
        gameObserverConnections[gameId].filter { it.playerId == playerId }.map { it.spectating = spectating }
    }

    //------------------------Convenience sending functions-------------------------------
    suspend inline fun <reified T : Any> toTeam(teamId: Long, message: T) =
        toTeam(teamId, *arrayOf(message))

    suspend inline fun <reified T : Any> toTeam(teamId: Long, vararg messages: T) {
        val (players, gameId) = newSuspendedTransaction {
            val team = Team.findById(teamId) ?: return@newSuspendedTransaction null
            team.members.map { it.id.value } to team.game.id.value
        } ?: return
        toPlayers(players, gameId, *messages)
    }

    suspend inline fun <reified T : Any> toTeam(
        gameId: Long,
        playerId: String,
        echo: Boolean = true,
        message: T
    ) =
        toTeam(gameId, playerId, echo, *arrayOf(message))

    suspend inline fun <reified T : Any> toTeam(
        gameId: Long,
        playerId: String,
        echo: Boolean = true,
        vararg messages: T
    ) {
        var players = newSuspendedTransaction {
            Team.find(gameId, playerId)?.members?.map { it.id.value }
        } ?: return
        if (!echo)
            players = players.filter { it != playerId }
        toPlayers(if (echo) players else players.filter { it != playerId }, gameId, *messages)
    }

    suspend inline fun <reified T : Any> toPlayers(
        players: Iterable<String>,
        gameId: Long? = null,
        message: T
    ) =
        toPlayers(players, gameId, *arrayOf(message))

    suspend inline fun <reified T : Any> toPlayers(
        players: Iterable<String>,
        gameId: Long? = null,
        vararg messages: T
    ) {
        for (player in players) {
            playerGameConnections[player]?.let { conn ->
                for (message in messages) {
                    try {
                        if (gameId == null || gameId == conn.gameId)
                            conn.socketConnection.sendMessage(message)
                    } catch (e: Throwable) {
                        println(e)
                    }
                }
            }
        }
    }

    suspend inline fun <reified T : Any> toObservers(
        gameId: Long,
        spectatorsOnly: Boolean = false,
        message: T
    ) =
        toObservers(gameId, spectatorsOnly, *arrayOf(message))

    suspend inline fun <reified T : Any> toObservers(
        gameId: Long,
        spectatorsOnly: Boolean,
        vararg messages: T
    ) {
        println(gameId)
        gameObserverConnections[gameId].filter { !spectatorsOnly || it.spectating }.forEach { (conn, _) ->
            for (message in messages) {
                try {
                    conn.sendMessage(message)
                } catch (e: Throwable) {
                    println(e)
                }
            }
        }
    }

    suspend inline fun <reified T : Any> toObservers(gameId: Long, playerId: String, message: T) =
        toObservers(gameId, playerId, *arrayOf(message))

    suspend inline fun <reified T : Any> toObservers(gameId: Long, playerId: String, vararg messages: T) {
        var conns: Set<WebSocketConnection> = playerObserverConnections[gameId to playerId]
        if (newSuspendedTransaction {
                User.findById(playerId)?.latestBingoGame?.id?.value == gameId
        })
            conns = conns + playerObserverConnections[null to playerId]

        conns.forEach { conn ->
            for (message in messages) {
                try {
                    conn.sendMessage(message)
                } catch (e: Throwable) {
                    println(e)
                }
            }
        }
    }
}