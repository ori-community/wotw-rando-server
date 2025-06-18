package wotw.server.sync

import org.jetbrains.exposed.dao.load
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.*
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.api.UberStateSyncStrategy
import wotw.server.bingo.UberStateMap
import wotw.server.database.EntityCache
import wotw.server.database.model.GameState
import wotw.server.database.model.Multiverse
import wotw.server.database.model.Universe
import wotw.server.database.model.World
import wotw.server.database.model.WorldMembership
import wotw.server.main.WotwBackendServer
import wotw.server.util.assertTransaction
import java.lang.System.currentTimeMillis
import java.util.concurrent.ConcurrentHashMap


object PlayerStateCache : EntityCache<String, UberStateMap>(
    { PlayerStateCache.obtainState(it)?.uberStateData },
    { k, v -> PlayerStateCache.obtainState(k)?.uberStateData = v }
) {
    private fun obtainState(key: String): GameState? {
        assertTransaction()
        return GameState.findPlayerState(key)
    }
}

object WorldStateCache : EntityCache<Long, UberStateMap>(
    { WorldStateCache.obtainState(it)?.uberStateData },
    { k, v -> WorldStateCache.obtainState(k)?.uberStateData = v }
) {
    private fun obtainState(key: Long): GameState? {
        assertTransaction()
        return GameState.findWorldState(key)
    }
}

object UniverseStateCache : EntityCache<Long, UberStateMap>(
    { UniverseStateCache.obtainState(it)?.uberStateData },
    { k, v -> UniverseStateCache.obtainState(k)?.uberStateData = v }
) {
    private fun obtainState(key: Long): GameState? {
        assertTransaction()
        return GameState.findUniverseState(key)
    }
}

object MultiverseStateCache : EntityCache<Long, UberStateMap>(
    { MultiverseStateCache.obtainState(it)?.uberStateData },
    { k, v -> MultiverseStateCache.obtainState(k)?.uberStateData = v }
) {
    private fun obtainState(key: Long): GameState? {
        assertTransaction()
        return GameState.findMultiverseState(key)
    }
}

class StateSynchronization(private val server: WotwBackendServer) {
    val aggregationStrategiesCache: ConcurrentHashMap<Long, AggregationStrategyRegistry> = ConcurrentHashMap()

    suspend fun aggregateState(
        worldMembership: WorldMembership,
        uberId: UberId,
        value: Double
    ): Map<UberId, AggregationResult> = aggregateStates(worldMembership, mapOf(uberId to value))

    suspend fun aggregateStates(
        worldMembership: WorldMembership,
        states: Map<UberId, Double>
    ): Map<UberId, AggregationResult> {
        worldMembership.load(
            WorldMembership::world,
            World::universe,
            Universe::multiverse,
        )

        val world = worldMembership.world
        val multiverse = worldMembership.multiverse

        var strategies = aggregationStrategiesCache[world.id.value]
        if (strategies == null) {
            strategies = server.gameHandlerRegistry.getHandler(multiverse.id.value).generateStateAggregationRegistry(world)
            aggregationStrategiesCache[world.id.value] = strategies
        }

        return states.flatMap { (uberId, value) ->
            strategies.getStrategies(uberId).map { strategy ->
                val cache = when (strategy.scope) {
                    ShareScope.WORLD -> {
                        WorldStateCache.getOrNull(world.id.value)
                    }

                    ShareScope.UNIVERSE -> {
                        UniverseStateCache.getOrNull(world.universe.id.value)
                    }

                    ShareScope.MULTIVERSE -> MultiverseStateCache.getOrNull(multiverse.id.value)
                    ShareScope.PLAYER -> PlayerStateCache.getOrNull(worldMembership.user.id.value)
                } ?: return@map uberId to AggregationResult(value, strategy)

                val oldValue = cache[uberId]
                if (!strategy.trigger(oldValue, value))
                    return@map uberId to AggregationResult(value, strategy, oldValue, false)

                val newValue = oldValue?.let { strategy.aggregation(it, value) } ?: value
                cache[uberId] = newValue

                uberId to AggregationResult(value, strategy, oldValue, true, newValue)
            }
        }.toMap()
    }

    suspend fun sendUberStateUpdates(worldMembershipId: Long, uberId: UberId, update: AggregationResult) =
        sendUberStateUpdates(worldMembershipId, mapOf(Pair(uberId, update)))

    suspend fun sendUberStateUpdates(worldMembershipId: Long, updates: Map<UberId, AggregationResult>) {
        val triggered = updates.filterValues { it.triggered }
        val playerUpdates = triggered.filterValues {
            val strategy = it.strategy
            strategy?.group == UberStateSyncStrategy.NotificationGroup.ALL
                || it.sentValue != it.newValue && strategy?.group == UberStateSyncStrategy.NotificationGroup.DIFFERENT
        }

        val shareScopeUpdates = triggered.filterValues {
            val strategy = it.strategy
            strategy?.group == UberStateSyncStrategy.NotificationGroup.ALL ||
                it.oldValue != it.newValue &&
                (strategy?.group == UberStateSyncStrategy.NotificationGroup.OTHERS ||
                    strategy?.group == UberStateSyncStrategy.NotificationGroup.DIFFERENT)
        }.entries.groupBy { it.value.strategy?.scope }

        shareScopeUpdates.entries.forEach { (scope, states) ->
            if (scope !== null)
                server.connections.sendTo(
                    worldMembershipId, scope, false,
                    *states.map { (uberId, result) ->
                        UberStateUpdateMessage(
                            uberId,
                            result.newValue ?: 0.0
                        )
                    }.toTypedArray()
                )
        }

        if (playerUpdates.isNotEmpty()) {
            server.connections.toPlayers(listOf(worldMembershipId), UberStateBatchUpdateMessage(
                playerUpdates.map { (uberId, result) ->
                    UberStateUpdateMessage(
                        uberId,
                        result.newValue ?: 0.0
                    )
                }
            ))
        }
    }

    suspend fun syncMultiverseProgress(multiverseId: Long) {
        val (syncBingoUniversesMessage, spectatorBoard, stateUpdates) = newSuspendedTransaction {
            val multiverse = Multiverse.findById(multiverseId) ?: return@newSuspendedTransaction null
            multiverse.cachedBoard ?: return@newSuspendedTransaction null

            val info = multiverse.bingoUniverseInfo()
            val syncBingoUniversesMessage = SyncBingoUniversesMessage(info)
            val worldUpdates = multiverse.worlds.map { world ->
                val bingoPlayerData = multiverse.bingoUniverseInfo(world.universe)
                Triple(
                    world.memberships.map { it.id.value } to world.members.map { it.id.value },
                    UberStateBatchUpdateMessage(
                        UberStateUpdateMessage(
                            UberId(10, 0),
                            bingoPlayerData.squares.toDouble()
                        ),
                        UberStateUpdateMessage(
                            UberId(10, 1),
                            bingoPlayerData.lines.toDouble()
                        ),
                        UberStateUpdateMessage(
                            UberId(10, 2),
                            bingoPlayerData.rank.toDouble()
                        ),
                    ),
                    SyncBoardMessage(
                        multiverse.createBingoBoardMessage(world.universe),
                        true
                    ),
                )
            }

            Triple(
                syncBingoUniversesMessage,
                SyncBoardMessage(
                    multiverse.createBingoBoardMessage(null, true),
                    true
                ),
                worldUpdates,
            )
        } ?: return

        server.connections.toObservers(multiverseId, message = syncBingoUniversesMessage)
        server.connections.toObservers(multiverseId, true, spectatorBoard)
        stateUpdates.forEach { (playerAndWorldMembershipIds, goalStateUpdate, board) ->
            server.connections.toPlayers(playerAndWorldMembershipIds.first, goalStateUpdate)

            playerAndWorldMembershipIds.second.forEach { playerId ->
                server.connections.toObservers(multiverseId, playerId, board)
            }
        }
    }

    suspend fun purgeCache(seconds: Int) {
        PlayerStateCache.purge(currentTimeMillis() - 1000 * seconds)
        WorldStateCache.purge(currentTimeMillis() - 1000 * seconds)
        UniverseStateCache.purge(currentTimeMillis() - 1000 * seconds)
        MultiverseStateCache.purge(currentTimeMillis() - 1000 * seconds)
    }

    data class AggregationResult(
        val sentValue: Double,
        val strategy: UberStateSyncStrategy? = null,
        val oldValue: Double? = null,
        val triggered: Boolean = false,
        val newValue: Double? = null,
    )
}
