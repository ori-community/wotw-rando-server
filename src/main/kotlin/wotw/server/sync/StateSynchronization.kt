package wotw.server.sync

import kotlinx.html.currentTimeMillis
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.*
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.api.UberStateSyncStrategy
import wotw.server.bingo.UberStateMap
import wotw.server.database.EntityCache
import wotw.server.database.model.GameState
import wotw.server.database.model.Multiverse
import wotw.server.database.model.World
import wotw.server.main.WotwBackendServer
import wotw.server.util.zerore
import java.util.*
import kotlin.to

object StateCache : EntityCache<Pair<ShareScope, Long>, UberStateMap>(
    { StateCache.obtainState(it)?.uberStateData },
    { k, v -> StateCache.obtainState(k)?.uberStateData = v }
) {
    private suspend fun obtainState(key: Pair<ShareScope, Long>): GameState? = newSuspendedTransaction {
        when (key.first) {
            ShareScope.WORLD -> GameState.findWorldState(key.second)
            ShareScope.UNIVERSE -> GameState.findUniverseState(key.second)
            ShareScope.MULTIVERSE -> GameState.findMultiverseState(key.second)
            else -> null
        }
    }
}

class StateSynchronization(private val server: WotwBackendServer) {
    val aggregationStrategiesCache: MutableMap<Long, AggregationStrategyRegistry> =
        Collections.synchronizedMap(hashMapOf())

    //Requires active transaction
    suspend fun aggregateState(
        world: World,
        uberId: UberId,
        value: Double
    ): Collection<Pair<UberId, AggregationResult>> = aggregateStates(world, mapOf(uberId to value))

    suspend fun aggregateStates(
        world: World,
        states: Map<UberId, Double>
    ): Collection<Pair<UberId, AggregationResult>> {
        val universe = world.universe
        val multiverse = universe.multiverse

        var strategies = aggregationStrategiesCache[multiverse.id.value]
        if (strategies == null) {
            strategies = server.gameHandlerRegistry.getHandler(multiverse.id.value).generateStateAggregationRegistry()
            aggregationStrategiesCache[multiverse.id.value] = strategies
        }

        val result = mutableListOf<Pair<UberId, AggregationResult>>()

        result += states.flatMap { (uberId, value) ->
            strategies.getStrategies(uberId).map { strategy ->
                val id = when (strategy.scope) {
                    ShareScope.WORLD -> world
                    ShareScope.UNIVERSE -> universe
                    ShareScope.MULTIVERSE -> multiverse
                    else -> null
                }?.id?.value ?: return@map uberId to AggregationResult(value, strategy)
                val data = StateCache.getOrNull(strategy.scope to id) ?: return@map uberId to AggregationResult(
                    value,
                    strategy
                )

                val oldValue = data[uberId.group to uberId.state]
                if (!strategy.trigger(oldValue, value))
                    return@map uberId to AggregationResult(value, strategy, oldValue, false)

                val newValue = oldValue?.let { strategy.aggregation(it, value) } ?: value
                data[uberId.group to uberId.state] = newValue
                uberId to AggregationResult(value, strategy, oldValue, true, newValue)
            }
        }

        return result
    }

    suspend fun syncStates(playerId: String, uberId: UberId, updates: Collection<AggregationResult>) =
        syncStates(playerId, updates.map { uberId to it })

    suspend fun syncStates(
        playerId: String,
        updates: Map<UberId, Collection<AggregationResult>>
    ) =
        syncStates(playerId, updates.entries.flatMap { (key, value) -> value.map { key to it } })

    suspend fun syncStates(playerId: String, updates: Collection<Pair<UberId, AggregationResult>>) {
        val triggered = updates.filter { it.second.triggered }
        val playerUpdates = triggered.filter {
            val strategy = it.second.strategy
            strategy?.group == UberStateSyncStrategy.NotificationGroup.ALL
                    || it.second.sentValue != it.second.newValue && strategy?.group == UberStateSyncStrategy.NotificationGroup.DIFFERENT
        }
        val shareScopeUpdates = triggered.filter {
            val strategy = it.second.strategy
            strategy?.group == UberStateSyncStrategy.NotificationGroup.ALL ||
                    it.second.oldValue != it.second.newValue &&
                    (strategy?.group == UberStateSyncStrategy.NotificationGroup.OTHERS ||
                            strategy?.group == UberStateSyncStrategy.NotificationGroup.DIFFERENT)
        }.groupBy { it.second.strategy?.scope }

        shareScopeUpdates.entries.forEach { (scope, states) ->
            if (scope !== null)
                server.connections.sendTo(
                    playerId, scope, false,
                    *states.map { (uberId, result) ->
                        UberStateUpdateMessage(
                            UberId(zerore(uberId.group), zerore(uberId.state)),
                            zerore(result.newValue ?: 0.0)
                        )
                    }.toTypedArray()
                )
        }
        server.connections.toPlayers(listOf(playerId), UberStateBatchUpdateMessage(
            playerUpdates.map { (uberId, result) ->
                UberStateUpdateMessage(
                    UberId(zerore(uberId.group), zerore(uberId.state)),
                    zerore(result.newValue ?: 0.0)
                )
            }
        ))
    }

    suspend fun syncMultiverseProgress(multiverseId: Long) {
        val (syncBingoUniversesMessage, spectatorBoard, stateUpdates) = newSuspendedTransaction {
            val multiverse = Multiverse.findById(multiverseId) ?: return@newSuspendedTransaction null
            multiverse.board ?: return@newSuspendedTransaction null

            val info = multiverse.bingoUniverseInfo()
            val syncBingoUniversesMessage = SyncBingoUniversesMessage(info)
            val worldUpdates = multiverse.worlds.map { world ->
                val bingoPlayerData = multiverse.bingoUniverseInfo(world.universe)
                Triple(
                    world.members.map { it.id.value },
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
                        )
                    ),
                    SyncBoardMessage(
                        multiverse.createSyncableBoard(world.universe),
                        true
                    )
                )
            }
            Triple(
                syncBingoUniversesMessage,
                SyncBoardMessage(
                    multiverse.createSyncableBoard(null, true),
                    true
                ),
                worldUpdates
            )
        } ?: return

        server.connections.toObservers(multiverseId, message = syncBingoUniversesMessage)
        server.connections.toObservers(multiverseId, true, spectatorBoard)
        stateUpdates.forEach { (players, goalStateUpdate, board) ->
            server.connections.toPlayers(players, goalStateUpdate)
            players.forEach { playerId ->
                server.connections.toObservers(multiverseId, playerId, board)
            }
        }
    }

    suspend fun purgeCache(seconds: Int) {
        StateCache.purge(currentTimeMillis() - 1000 * seconds)
    }

    data class AggregationResult(
        val sentValue: Double,
        val strategy: UberStateSyncStrategy? = null,
        val oldValue: Double? = null,
        val triggered: Boolean = false,
        val newValue: Double? = null,
    )
}