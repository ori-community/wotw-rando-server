package wotw.server.bingo

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import wotw.io.messages.protobuf.UberId
import wotw.server.database.model.BingoEvent
import kotlin.math.max
import kotlin.math.min

@Serializable //Type-erasure forces a compiled class for type-safe serialization
data class UberStateMap(private val map: MutableMap<UberId, Double>) : MutableMap<UberId, Double> by map {
    constructor() : this(mutableMapOf())

    companion object {
        val empty = UberStateMap()
    }
}

@Polymorphic
@Serializable
sealed class BingoGoal {
    abstract val keys: Set<UberId>
    abstract val title: String
    open val helpText: String? = null

    open fun printSubText(state: UberStateMap): Iterable<Pair<String, Boolean>> = emptyList()
    abstract fun isCompleted(state: UberStateMap): Boolean
}

sealed class CompositeGoal : BingoGoal() {
    abstract val goals: Array<out BingoGoal>
    override val keys: Set<UberId> by lazy { goals.flatMap { it.keys }.toSet() }

    override fun printSubText(state: UberStateMap) =
        goals.map { (listOf(it.title) + it.printSubText(state)).joinToString(" ") to it.isCompleted(state) }
}

@Serializable
data class CountGoal(
    private val text: String,
    val threshold: Int,
    override val goals: Array<out BingoGoal>,
    val hideChildren: Boolean = false
) : CompositeGoal() {
    override fun isCompleted(state: UberStateMap): Boolean {
        return goals.count { it.isCompleted(state) } >= threshold
    }

    override fun printSubText(state: UberStateMap) =
        if (hideChildren)
            listOf("${goals.count { it.isCompleted(state) }} / $threshold" to false)
        else
            super.printSubText(state)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CountGoal

        if (text != other.text) return false
        if (threshold != other.threshold) return false
        if (!goals.contentEquals(other.goals)) return false
        if (hideChildren != other.hideChildren) return false
        if (title != other.title) return false

        return true
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + threshold
        result = 31 * result + goals.contentHashCode()
        result = 31 * result + hideChildren.hashCode()
        result = 31 * result + title.hashCode()
        return result
    }

    fun String.fixSuffix(plural: Boolean) = if (plural)
        this.replace("[s]", "s").replace("[es]", "es")
    else
        this.replace("[s]", "").replace("[es]", "")

    override val title: String = when {
        hideChildren -> text.replace(" #", "")
        threshold == goals.size -> text.replace(
            "#", when (goals.size) {
                1 -> "THIS"
                2 -> "BOTH"
                else -> "THESE"
            }
        )
        threshold > 1 -> text.replace("#", "$threshold")
        goals.size == 2 -> text.replace("#", "EITHER").fixSuffix(false)
        else -> text.replace("#", "ONE OF THESE").fixSuffix(true)
    }.fixSuffix(threshold > 1)
}

@Serializable
data class BoolGoal(override val title: String, private val key: UberId) : BingoGoal() {
    override val keys = setOf(key)
    override fun isCompleted(state: UberStateMap) = state[key]?.equals(0.toDouble()) == false
}

@Serializable
data class NumberThresholdGoal(
    val text: String,
    private val expression: StateExpression,
    private val threshold: StateExpression,
    private val hide: Boolean = false
) :
    BingoGoal() {
    override val keys = expression.keys + threshold.keys
    override fun isCompleted(state: UberStateMap): Boolean {
        return expression.calc(state) >= threshold.calc(state)
    }

    override val title: String by lazy {
        val replacements = if (expression is AggregationExpression) {
            expression.names ?: emptyList()
        } else emptyList()
        text.replace("<\\d*>".toRegex()) {
            val index = it.groups[1]?.value?.toIntOrNull() ?: return@replace ""
            replacements.getOrNull(index) ?: ""
        }
    }

    override fun printSubText(state: UberStateMap): Iterable<Pair<String, Boolean>> {
        return if (hide)
            emptyList()
        else {
            listOf(
                (expression.calc(state).toLong().toString() + " / " + threshold.calc(state).toLong()
                    .toString()) to false
            )
        }
    }
}

typealias Point = Pair<Int, Int>

fun Boolean.int() = if (this) 1 else 0
operator fun Point.times(i: Int) = this.first * i to this.second * i
operator fun Point.plus(p: Point) = this.first + p.first to this.second + p.second

@Serializable
data class BingoBoard(val goals: MutableMap<Point, BingoGoal> = hashMapOf(), val config: BingoConfig) {
    val size = config.boardSize
    fun goalCompleted(p: Point, state: UberStateMap?) = goals[p]?.isCompleted(state ?: UberStateMap.empty) ?: false

    fun getVisibleDiscoveryGoals(state: UberStateMap, events: List<BingoEvent>): Set<Point> {
        val initiallyVisibleGoalsPositions = config.discovery?.toMutableSet() ?: return goals.keys

        // Reveal first {config.revealFirstNCompletedGoals} completed goals
        events
            .take(config.revealFirstNCompletedGoals)
            .forEach { event ->
                initiallyVisibleGoalsPositions += Point(event.x, event.y)
            }

        val visibleGoalsPositions = mutableSetOf<Point>()

        fun collectGoalsRecursively(start: Point) {
            if (visibleGoalsPositions.contains(start)) {
                return
            }

            visibleGoalsPositions += start

            if (goalCompleted(start, state)) {
                if (start.first > 1) {
                    collectGoalsRecursively(start + (-1 to 0))
                }

                if (start.first < size) {
                    collectGoalsRecursively(start + (1 to 0))
                }

                if (start.second > 1) {
                    collectGoalsRecursively(start + (0 to -1))
                }

                if (start.second < size) {
                    collectGoalsRecursively(start + (0 to 1))
                }
            }
        }

        for (position in initiallyVisibleGoalsPositions) {
            collectGoalsRecursively(position)
        }

        return visibleGoalsPositions
    }
}

@Serializable
data class BingoConfig(
    val lockout: Boolean = false,
    val manualSquareCompletion: Boolean = false,
    val discovery: Set<Point>? = null,
    val revealFirstNCompletedGoals: Int = 0,
    val boardSize: Int = 5,
)

@Serializable
sealed class StateExpression {
    abstract fun calc(state: UberStateMap): Double
    abstract val keys: Set<UberId>

    operator fun plus(other: StateExpression): StateExpression {
        return OperatorExpression(this, other, OperatorExpression.OPERATOR.PLUS)
    }

    operator fun minus(other: StateExpression): StateExpression {
        return OperatorExpression(this, other, OperatorExpression.OPERATOR.MINUS)
    }

    operator fun times(other: StateExpression): StateExpression {
        return OperatorExpression(this, other, OperatorExpression.OPERATOR.TIMES)
    }
}

@Serializable
class AggregationExpression(
    val aggr: Aggregation,
    val expressions: List<StateExpression>,
    val names: List<String>? = null
) : StateExpression() {
    enum class Aggregation {
        SUM, PRODUCT, MAX, MIN
    }

    override fun calc(state: UberStateMap): Double {
        val children = expressions.map { it.calc(state) }
        val aggregationFunction: (Double, Double) -> Double = when (aggr) {
            Aggregation.SUM -> { a, b -> a + b }
            Aggregation.PRODUCT -> { a, b -> a * b }
            Aggregation.MAX -> { a, b -> max(a, b) }
            Aggregation.MIN -> { a, b -> min(a, b) }
        }
        return children.reduce(aggregationFunction)
    }

    override val keys: Set<UberId>
        get() = expressions.map { it.keys }.reduce { first, second -> first.union(second) }
}

@Serializable
class UberStateExpression(val uberId: UberId) : StateExpression() {
    constructor(group: Int, state: Int) : this(UberId(group, state))

    override fun calc(state: UberStateMap): Double = state[uberId] ?: Double.NaN
    override val keys: Set<UberId>
        get() = setOf(uberId)
}

@Serializable
class ConstExpression(val value: Double) : StateExpression() {
    override fun calc(state: UberStateMap): Double = value
    override val keys: Set<UberId>
        get() = emptySet()
}

@Serializable
class OperatorExpression(val first: StateExpression, val second: StateExpression, val op: OPERATOR) :
    StateExpression() {
    enum class OPERATOR {
        PLUS, MINUS, TIMES, DIV
    }

    override fun calc(state: UberStateMap): Double {
        return when (op) {
            OPERATOR.PLUS -> first.calc(state) + second.calc(state)
            OPERATOR.MINUS -> first.calc(state) - second.calc(state)
            OPERATOR.TIMES -> first.calc(state) * second.calc(state)
            OPERATOR.DIV -> first.calc(state) / second.calc(state)
        }
    }

    override val keys: Set<UberId>
        get() = first.keys + second.keys
}