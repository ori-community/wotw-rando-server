package wotw.server.game.handlers

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.*
import wotw.server.api.*
import wotw.server.database.model.GameState
import wotw.server.database.model.Multiverse
import wotw.server.database.model.World
import wotw.server.game.inventory.WorldInventory
import wotw.server.main.WotwBackendServer
import wotw.server.sync.ShareScope
import wotw.server.sync.multiStates
import wotw.server.sync.normalWorldSyncAggregationStrategy

class NormalGameHandler(multiverseId: Long, server: WotwBackendServer) : GameHandler<Nothing>(multiverseId, server) {
    init {
        messageEventBus.register(this, UberStateUpdateMessage::class) { message, playerId ->
            server.populationCache.getOrNull(playerId)?.worldId?.let { worldId ->
                updateUberState(message, worldId, playerId)
            }
        }

        messageEventBus.register(this, UberStateBatchUpdateMessage::class) { message, playerId ->
            server.populationCache.getOrNull(playerId)?.worldId?.let { worldId ->
                batchUpdateUberStates(message, worldId, playerId)
            }
        }

        messageEventBus.register(this, PlayerPositionMessage::class) { message, playerId ->
            val targetPlayers = server.populationCache.get(playerId).universeMemberIds - playerId

            server.connections.toPlayers(
                targetPlayers,
                UpdatePlayerPositionMessage(playerId, message.x, message.y),
                unreliable = true,
            )
        }

        messageEventBus.register(this, ResourceRequestMessage::class) { message, playerId ->
            val playerPopulationCache = server.populationCache.get(playerId)

            newSuspendedTransaction {
                playerPopulationCache.worldId?.let { worldId ->
                    GameState.findWorldState(worldId)?.let { gameState ->
                        val inventory = WorldInventory(gameState)

                        val targetPlayers = playerPopulationCache.worldMemberIds
                        val uberStateUpdateMessage = inventory.handleRequest(message)

                        if (uberStateUpdateMessage != null) {
                            server.connections.toPlayers(
                                targetPlayers,
                                uberStateUpdateMessage,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun updateUberState(message: UberStateUpdateMessage, worldId: Long, playerId: String) =
        batchUpdateUberStates(UberStateBatchUpdateMessage(message), worldId, playerId)

    private suspend fun batchUpdateUberStates(message: UberStateBatchUpdateMessage, worldId: Long, playerId: String) {
        val updates = message.updates.associate {
            it.uberId to it.value
        }

        val results = newSuspendedTransaction {
            val world = World.findById(worldId) ?: error("Error: Requested uber state update on unknown world")
            val result = server.sync.aggregateStates(world, updates)
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
        server.sync.syncStates(playerId, results)
    }

    override suspend fun generateStateAggregationRegistry(world: World): AggregationStrategyRegistry {
        var aggregationRegistry = AggregationStrategyRegistry()

        // Add bingo states if we have a bingo game
        newSuspendedTransaction {
            Multiverse.findById(multiverseId)?.board?.goals?.flatMap { it.value.keys }
        }?.let {
            aggregationRegistry += AggregationStrategyRegistry().apply {
                register(
                    sync(it).notify(UberStateSyncStrategy.NotificationGroup.NONE).across(ShareScope.UNIVERSE)
                )
            }
        }

        aggregationRegistry += normalWorldSyncAggregationStrategy

        aggregationRegistry += AggregationStrategyRegistry().apply {
            register(
                sync(multiStates()).across(ShareScope.UNIVERSE),
            )
        }

        return aggregationRegistry
    }
}