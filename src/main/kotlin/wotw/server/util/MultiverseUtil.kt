package wotw.server.util

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.UberId
import wotw.io.messages.protobuf.UberStateBatchUpdateMessage
import wotw.io.messages.protobuf.UberStateUpdateMessage
import wotw.server.database.PlayerUniversePopulationCacheEntry
import wotw.server.database.model.User
import wotw.server.database.model.World
import wotw.server.game.handlers.PlayerId
import wotw.server.main.WotwBackendServer
import wotw.server.sync.ShareScope
import wotw.server.sync.StateCache

class MultiverseUtil(val server: WotwBackendServer) {
    suspend fun removePlayerFromCurrentWorld(player: User, dontNotifyMultiverseId: Long? = null) {
        player.currentWorld?.let { world ->
            val previousMultiverseId = world.universe.multiverse.id.value
            player.currentWorld = null

            if (previousMultiverseId != dontNotifyMultiverseId) {
                server.connections.broadcastMultiverseInfoMessage(previousMultiverseId)
            }
        }
    }

    suspend fun movePlayerToWorld(player: User, world: World) {
        if (player.currentWorld != world) {
            removePlayerFromCurrentWorld(player, world.universe.multiverse.id.value)

            player.currentWorld = world
            player.flush()

            server.populationCache.put(player.id.value, PlayerUniversePopulationCacheEntry(
                player.id.value,
                world.id.value,
                world.universe.members.map { it.id.value }.toSet(),
                world.members.map { it.id.value }.toSet(),
            ))

            logger().info("Moving ${player.name} to world ${world.id.value}")

            server.connections.broadcastMultiverseInfoMessage(world.universe.multiverse.id.value)
        }
    }

    suspend fun sendWorldStateAfterMovedToAnotherWorld(worldId: Long, playerId: PlayerId) {
        var messages = listOf<UberStateUpdateMessage>()

        newSuspendedTransaction {
            StateCache.getOrNull(ShareScope.WORLD to worldId)?.map { (uberId, value) ->
                UberStateUpdateMessage(UberId(uberId.group, uberId.state), value)
            }?.let { uberStateUpdateMessages ->
                messages = uberStateUpdateMessages
            }
        }

        server.connections.toPlayers(
            listOf(playerId),
            UberStateBatchUpdateMessage(messages, true)
        )
    }
}