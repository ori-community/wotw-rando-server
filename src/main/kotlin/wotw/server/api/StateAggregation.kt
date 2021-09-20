package wotw.server.api

import wotw.io.messages.protobuf.UberId
import wotw.server.api.UberStateSyncStrategy.NotificationGroup.DIFFERENT
import wotw.server.sync.ShareScope
import wotw.util.MultiMap
import kotlin.math.max
import kotlin.math.min


data class UberStateSyncStrategy(val aggregation: (Double, Double) -> Double,
                   val trigger: (Double?, Double) -> Boolean = {_, _ -> true},
                   val group: NotificationGroup = DIFFERENT,
                   val scope: ShareScope = ShareScope.WORLD
){
    companion object{
        val MAX = UberStateSyncStrategy(::max)
        val MIN = UberStateSyncStrategy(::min)
        val ALWAYS_OVERWRITE = UberStateSyncStrategy({ _, v -> v })
        val KEEP = UberStateSyncStrategy({ v, _ -> v })
        val AVG = UberStateSyncStrategy({ o, n -> (o + n) / 2 })
    }

    enum class NotificationGroup{
        /**
         * Only listen for updates
         * */
        NONE,
        /**
         * echo if server state differs, sync to other players if updated
         * */
        DIFFERENT,
        /**
         * Sync to other players if updated
         * */
        OTHERS,
        /**
         * Always Echo, Always sync to other players
         * */
        ALL
    }

}

private typealias UberStateRegistration = Pair<Collection<UberId>, UberStateSyncStrategy?>
class AggregationStrategyRegistry(private val strategies: MultiMap<UberId, UberStateSyncStrategy> = MultiMap()){
    fun register(vararg registrations: UberStateRegistration): AggregationStrategyRegistry{
        registrations.flatMap{ (ids, strategy) ->
            val mappedStrategy = strategy ?: UberStateSyncStrategy.MAX
            ids.map { it to mappedStrategy }
        }.forEach { (id, strat) ->
            strategies.add(id, strat)
        }
        return this
    }

    fun getStrategies(uberId: UberId) = strategies[uberId]
    fun getSyncedStates() = strategies.keys.toSet()

    operator fun plus(other: AggregationStrategyRegistry) = AggregationStrategyRegistry(strategies + other.strategies)
}

fun sync(vararg ids: Int, strategy: UberStateSyncStrategy? = null): UberStateRegistration =
    sync(ids.toList().zipWithNext().map { UberId(it.first, it.second) }, strategy)
fun sync(vararg ids: Pair<Int, Int>, strategy: UberStateSyncStrategy? = null): UberStateRegistration =
    sync(ids.map { UberId(it.first, it.second) }, strategy)
fun sync(vararg ids: UberId, strategy: UberStateSyncStrategy? = null): UberStateRegistration =
    sync(ids.toSet(), strategy)
fun sync(ids: Collection<UberId>, strategy: UberStateSyncStrategy? = null): UberStateRegistration =
    UberStateRegistration(ids.toSet(), strategy)

fun UberStateRegistration.with(strategy: UberStateSyncStrategy) = first to strategy
fun UberStateRegistration.on(threshold: Float) = first to (second ?: UberStateSyncStrategy.MAX).copy(trigger = { o, n -> n >= threshold})
fun UberStateRegistration.across(scope: ShareScope) = first to (second ?: UberStateSyncStrategy.MAX).copy(scope = scope)
fun UberStateRegistration.notify(group: UberStateSyncStrategy.NotificationGroup) = first to (second ?: UberStateSyncStrategy.MAX).copy(group = group)
