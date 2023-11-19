package wotw.io.messages.protobuf

import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.HashSet
import java.util.SortedSet
import java.util.TreeSet
import kotlin.math.max

// All fields have default values ^= protobuf optional
// We just send partial updates and merge state client-side

@Serializable
data class BingoData(
    val board: BingoBoardMessage,
    val universes: List<BingoUniverseInfo>,
)

@Serializable
data class PositionedBingoSquare(
    @ProtoNumber(1) val position: Position,
    @ProtoNumber(2) val square: BingoSquare,
)

@Serializable
data class BingoBoardMessage(
    @ProtoNumber(1) val squares: List<PositionedBingoSquare> = emptyList(),
    @ProtoNumber(2) val size: Int = -1,
    @ProtoNumber(3) val lockout: Boolean = false,
) {

    operator fun get(position: Position) = squares.first { it.position == position }.square
    fun merge(other: BingoBoardMessage) =
        BingoBoardMessage(squares + other.squares, max(size, other.size), lockout || other.lockout)

    operator fun plus(other: BingoBoardMessage) = merge(other)
}

@Serializable
data class BingothonBingoBoard(
    val size: Int,
    val universes: List<BingothonBingoUniverseInfo>,
    val squares: List<BingothonBingoSquare>,
)

@Serializable
data class BingothonBingoSquare(
    val position: Position,
    val text: String,
    val html: String,
    val completedBy: LinkedHashSet<Long>,
    val visibleFor: LinkedHashSet<Long>,
)

@Serializable
data class BingothonBingoUniverseInfo(
    val universeId: Long,
    val squares: Int = 0,
    val lines: Int = 0,
)


@Serializable
data class Position(
    @ProtoNumber(1) val x: Int,
    @ProtoNumber(2) val y: Int
)

@Serializable
data class BingoSquare(
    @ProtoNumber(1) val text: String = "",
    @ProtoNumber(3) val goals: List<BingoGoal> = emptyList(),

    /** This is always in order of completion, so in Lockout games, the first entry is the owner */
    @ProtoNumber(2) var completedBy: LinkedHashSet<Long> = LinkedHashSet(),

    @ProtoNumber(4) var visibleFor: LinkedHashSet<Long> = LinkedHashSet(),
)

@Serializable
data class BingoGoal(
    @ProtoNumber(1) val text: String = "",
    @ProtoNumber(2) val completed: Boolean = false
)
