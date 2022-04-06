package wotw.server.game

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.*
import wotw.server.database.model.*
import wotw.server.io.ClientConnection
import wotw.server.main.WotwBackendServer
import wotw.server.sync.multiStates
import wotw.server.util.makeServerTextMessage
import wotw.server.util.zerore

data class GameConnectionHandlerSyncResult(
    val worldId: Long,
    val universeId: Long,
    val multiverseId: Long,
)

class GameConnectionHandler(
    private var playerId: String,
    private val connection: ClientConnection,
    private val server: WotwBackendServer,
) {
    var multiverseId: Long? = null
        private set

    suspend fun onMessage(message: Any) {
        multiverseId?.let {
            server.gameHandlerRegistry.getHandler(it).onMessage(message, playerId)
        }
    }

    suspend fun setup(): GameConnectionHandlerSyncResult? {
        return newSuspendedTransaction {
            val player = User.findById(playerId)
            val world = player?.currentWorld

            if (player == null || world == null) {
                return@newSuspendedTransaction null
            }

            val universe = world.universe
            val multiverse = universe.multiverse
            multiverseId = multiverse.id.value

            val states = server.gameHandlerRegistry.getHandler(multiverse.id.value).generateStateAggregationRegistry().getSyncedStates()

            this@GameConnectionHandler.connection.sendMessage(InitGameSyncMessage(states.map {
                UberId(zerore(it.group), zerore(it.state))
            }))

            val worldMemberNames = world.members.map { it.name }
            var greeting = "${player.name} - Connected to multiverse ${multiverse.id.value}"
            greeting += "\nWorld: ${world.name}\n" + worldMemberNames.joinToString()

            this@GameConnectionHandler.connection.sendMessage(makeServerTextMessage(greeting))

            this@GameConnectionHandler.connection.sendMessage(server.infoMessagesService.generateMultiverseInfoMessage(
                multiverse
            ))

            return@newSuspendedTransaction GameConnectionHandlerSyncResult(
                world.id.value,
                universe.id.value,
                multiverse.id.value,
            )
        }
    }
}