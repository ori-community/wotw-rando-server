package wotw.server.sync

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.*
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.api.UberStateSyncStrategy
import wotw.server.database.model.*
import wotw.server.main.WotwBackendServer
import wotw.server.util.zerore
import java.util.*
import kotlin.to

class StateSynchronization(private val server: WotwBackendServer) {
    val aggregationStrategies: MutableMap<Long, AggregationStrategyRegistry> = Collections.synchronizedMap(hashMapOf())

    //Requires active transaction
    suspend fun aggregateState(world: World, uberId: UberId, value: Double): AggregationResult {
        val universe = world.universe
        val multiverse = universe.multiverse
        val strategy = aggregationStrategies.getOrPut(multiverse.id.value) {
            multiverse.generateStateAggregationRegistry()
        }.getStrategy(uberId) ?: return AggregationResult(value)

        val state = when (strategy.scope) {
            ShareScope.WORLD -> GameState.findWorldState(world.id.value)
            ShareScope.UNIVERSE -> GameState.findUniverseState(universe.id.value)
            ShareScope.MULTIVERSE -> GameState.findMultiverseState(multiverse.id.value)
            else -> null
        } ?: return AggregationResult(value, strategy)
        val data = state.uberStateData

        val oldValue = data[uberId.group to uberId.state]
        if (!strategy.trigger(oldValue, value))
            return AggregationResult(value, strategy, oldValue, false)

        val newValue = oldValue?.let { strategy.aggregation(it, value) } ?: value
        data[uberId.group to uberId.state] = newValue

        state.uberStateData = data
        return AggregationResult(value, strategy, oldValue, true, newValue)
    }

    suspend fun syncState(gameId: Long, playerId: String, uberId: UberId, aggregationResult: AggregationResult) {
        val strategy = aggregationResult.strategy ?: return
        if (!aggregationResult.triggered || strategy.group == UberStateSyncStrategy.NotificationGroup.NONE)
            return

        val uberId = UberId(zerore(uberId.group), zerore(uberId.state))
        val excludePlayerFromUpdate = strategy.group == UberStateSyncStrategy.NotificationGroup.NONE
                || strategy.group == UberStateSyncStrategy.NotificationGroup.DIFFERENT && aggregationResult.sentValue == aggregationResult.newValue
        server.connections.sendTo(
            gameId, playerId, strategy.scope, excludePlayerFromUpdate, UberStateUpdateMessage(
                uberId,
                zerore(aggregationResult.newValue ?: 0.0)
            )
        )
    }

    suspend fun syncStates(gameId: Long, playerId: String, updates: Map<UberId, AggregationResult>) {
        val triggered = updates.filter { it.value.triggered }
        val playerUpdates = triggered.filter {
            val strategy = it.value.strategy
            strategy?.group == UberStateSyncStrategy.NotificationGroup.ALL
                    || it.value.sentValue != it.value.newValue && strategy?.group == UberStateSyncStrategy.NotificationGroup.DIFFERENT
        }
        val shareScopeUpdates = triggered.filter {
            val strategy = it.value.strategy
            strategy?.group == UberStateSyncStrategy.NotificationGroup.ALL ||
                    it.value.oldValue != it.value.newValue &&
                    (strategy?.group == UberStateSyncStrategy.NotificationGroup.OTHERS ||
                            strategy?.group == UberStateSyncStrategy.NotificationGroup.DIFFERENT)
        }.entries.groupBy { it.value.strategy?.scope }

        shareScopeUpdates.entries.forEach { (scope, states) ->
            if (scope !== null)
                server.connections.sendTo(
                    gameId, playerId, scope, false,
                    states.map { (uberId, result) ->
                        UberStateUpdateMessage(
                            UberId(zerore(uberId.group), zerore(uberId.state)),
                            zerore(result.newValue ?: 0.0)
                        )
                    }
                )
        }
        server.connections.toPlayers(listOf(playerId), gameId, false, UberStateBatchUpdateMessage(
            playerUpdates.map { (uberId, result) ->
                UberStateUpdateMessage(
                    UberId(zerore(uberId.group), zerore(uberId.state)),
                    zerore(result.newValue ?: 0.0)
                )
            }
        ))
    }

    suspend fun syncMultiverseProgress(gameId: Long) {
        val (syncBingoWorldsMessage, spectatorBoard, stateUpdates) = newSuspendedTransaction {
            val multiverse = Multiverse.findById(gameId) ?: return@newSuspendedTransaction null
            multiverse.board ?: return@newSuspendedTransaction null

            val info = multiverse.bingoWorldInfo()
            val syncBingoWorldsMessage = SyncBingoUniversesMessage(info)
            val worldUpdates = multiverse.worlds.map { world ->
                val bingoPlayerData = multiverse.bingoWorldInfo(world)
                Triple(
                    world.members.map { it.id.value },
                    UberStateBatchUpdateMessage(
                        UberStateUpdateMessage(
                            UberId(10, 0),
                            bingoPlayerData.squares.toDouble()
                        ), UberStateUpdateMessage(
                            UberId(10, 1),
                            bingoPlayerData.lines.toDouble()
                        ), UberStateUpdateMessage(
                            UberId(10, 2),
                            bingoPlayerData.rank.toDouble()
                        )
                    ), SyncBoardMessage(
                        multiverse.createSyncableBoard(world),
                        true
                    )
                )
            }

            Triple(
                syncBingoWorldsMessage, SyncBoardMessage(
                    multiverse.createSyncableBoard(null, true),
                    true
                ), worldUpdates
            )
        } ?: return

        server.connections.toObservers(gameId, message = syncBingoWorldsMessage)
        server.connections.toObservers(gameId, true, spectatorBoard)
        stateUpdates.forEach { (players, goalStateUpdate, board) ->
            server.connections.toPlayers(players, gameId, false, goalStateUpdate)
            players.forEach { playerId ->
                server.connections.toObservers(gameId, playerId, board)
            }
        }
    }

    data class AggregationResult(
        val sentValue: Double,
        val strategy: UberStateSyncStrategy? = null,
        val oldValue: Double? = null,
        val triggered: Boolean = false,
        val newValue: Double? = null,
    )
}