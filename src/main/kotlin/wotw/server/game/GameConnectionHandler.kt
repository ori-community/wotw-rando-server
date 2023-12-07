package wotw.server.game

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.InitGameSyncMessage
import wotw.io.messages.protobuf.PrintTextMessage
import wotw.io.messages.protobuf.Vector2
import wotw.server.database.model.User
import wotw.server.io.ClientConnection
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.server.util.makeServerTextMessage

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

    val currentPlayerId
        get() = playerId

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

            var handler = server.gameHandlerRegistry.getHandler(multiverse.id.value)
            val states = handler.generateStateAggregationRegistry(world).getSyncedStates()

            this@GameConnectionHandler.connection.sendMessage(
                InitGameSyncMessage(
                    states.toList(),
                    handler.shouldBlockStartingNewGame(),
                    handler.getPlayerSaveGuid(playerId),
                    true,
                )
            )

            val worldMemberNames = world.members.map { it.name }
            var greeting = "${player.name} - Connected to multiverse ${multiverse.id.value}"
            greeting += "\nWorld: ${world.name}\n" + worldMemberNames.joinToString()

            this@GameConnectionHandler.connection.sendMessage(makeServerTextMessage(greeting))

            this@GameConnectionHandler.connection.sendMessage(server.infoMessagesService.generateMultiverseInfoMessage(
                multiverse
            ))

            server.gameHandlerRegistry.getHandler(multiverse.id.value).onGameConnectionSetup(this@GameConnectionHandler)

            return@newSuspendedTransaction GameConnectionHandlerSyncResult(
                world.id.value,
                universe.id.value,
                multiverse.id.value,
            )
        }
    }
}
