package wotw.server.game

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.*
import wotw.server.database.model.*
import wotw.server.io.ClientConnection
import wotw.server.main.WotwBackendServer
import wotw.server.sync.multiStates
import wotw.server.sync.worldStateAggregationRegistry
import wotw.server.util.makeServerTextMessage
import wotw.server.util.rezero
import wotw.server.util.zerore

data class GameSyncHandlerSetupResult(
    val worldId: Long,
    val universeId: Long,
    val multiverseId: Long,
)

class GameSyncHandler(
    private var playerId: String,
    private val connection: ClientConnection,
    private val server: WotwBackendServer,
) {
    suspend fun onUberStateUpdateMessage(message: UberStateUpdateMessage) {
        server.populationCache.getOrNull(playerId)?.worldId?.let {
            if (playerId.isNotEmpty()) {
                updateUberState(message, it, playerId)
            }
        }
    }

    suspend fun onUberStateBatchUpdateMessage(message: UberStateBatchUpdateMessage) {
        server.populationCache.getOrNull(playerId)?.worldId?.let {
            if (playerId.isNotEmpty()) {
                batchUpdateUberStates(message, it, playerId)
            }
        }
    }

    suspend fun onPlayerPositionMessage(message: PlayerPositionMessage) {
        val targetPlayers = server.populationCache.get(playerId).universeMemberIds - playerId

        server.connections.toPlayers(
            targetPlayers,
            null,
            true,
            UpdatePlayerPositionMessage(playerId, message.x, message.y)
        )
    }

    suspend fun setup(): GameSyncHandlerSetupResult? {
        return newSuspendedTransaction {
            val player = User.findById(playerId)
            val world = WorldMembership.find {
                WorldMemberships.playerId eq playerId
            }.firstOrNull()?.world

            if (player == null || world == null) {
                return@newSuspendedTransaction null
            }

            val universe = world.universe
            val multiverse = universe.multiverse

            val bingoStates = world.universe.multiverse.board?.goals?.flatMap { it.value.keys }
                ?.map { UberId(it.first, it.second) }.orEmpty()

            val states = multiStates()
                .plus(worldStateAggregationRegistry.getSyncedStates())
                .plus(bingoStates)  // don't sync new data

            this@GameSyncHandler.connection.sendMessage(InitGameSyncMessage(states.map {
                UberId(zerore(it.group), zerore(it.state))
            }))

            val worldMemberNames = world.members.map { it.name }
            var greeting = "${player.name} - Connected to multiverse ${multiverse.id.value}"
            greeting += "\nWorld: ${world.name}\n" + worldMemberNames.joinToString()

            this@GameSyncHandler.connection.sendMessage(makeServerTextMessage(greeting))

            this@GameSyncHandler.connection.sendMessage(server.infoMessagesService.generateMultiverseInfoMessage(
                multiverse
            ))

            return@newSuspendedTransaction GameSyncHandlerSetupResult(
                world.id.value,
                universe.id.value,
                multiverse.id.value,
            )
        }
    }

    private suspend fun updateUberState(message: UberStateUpdateMessage, worldId: Long, playerId: String) =
        batchUpdateUberStates(UberStateBatchUpdateMessage(message), worldId, playerId)

    private suspend fun batchUpdateUberStates(message: UberStateBatchUpdateMessage, worldId: Long, playerId: String) {
        val updates = message.updates.map {
            UberId(rezero(it.uberId.group), rezero(it.uberId.state)) to rezero(it.value)
        }.toMap()

        val (results, multiverseId) = newSuspendedTransaction {
            val world = World.findById(worldId) ?: error("Error: Requested uber state update on unknown world")
            val result = server.sync.aggregateStates(world, updates) to world.universe.multiverse.id.value
            world.universe.multiverse.updateCompletions(world.universe)
            result
        }

        // Don't think this is needed?
        // val pc = server.connections.playerMultiverseConnections[playerId]!!
        // if (pc.multiverseId != multiverseId) {
        //     server.connections.unregisterMultiverseConnection(playerId)
        //     server.connections.registerMultiverseConnection(pc.clientConnection, playerId, multiverseId)
        // }

        server.sync.syncMultiverseProgress(multiverseId)
        server.sync.syncStates(multiverseId, playerId, results)
    }
}