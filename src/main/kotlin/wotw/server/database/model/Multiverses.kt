package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.SizedCollection
import wotw.io.messages.protobuf.*
import wotw.server.bingo.BingoCard
import wotw.server.bingo.Line
import wotw.server.bingo.UberStateMap
import wotw.server.database.jsonb
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.math.ceil
import kotlin.to

object Multiverses : LongIdTable("multiverse") {
    override val primaryKey = PrimaryKey(id)
    val seed = reference("seed", Seeds).nullable()
    val board = jsonb("board", BingoCard.serializer()).nullable()
}

class Multiverse(id: EntityID<Long>) : LongEntity(id) {
    var board by Multiverses.board
    val universes by Universe referrersOn Universes.multiverseId
    val worlds: Collection<World>
        get() = universes.flatMap { it.worlds }
    private val states by GameState referrersOn GameStates.multiverseId
    private val events by BingoEvent referrersOn BingoEvents.multiverseId
    var spectators by User via Spectators

    val universeStates
        get() = states.filter { it.world == null }.mapNotNull { it.universe?.let{universe -> universe to it}}.toMap()
    val worldStates
        get() = states.mapNotNull { it.world?.let{world -> world to it}}.toMap()
    val players
        get() = worlds.flatMap { it.members }
    val members
        get() = players + spectators

    fun updateCompletions(universe: Universe) {
        val board = board ?: return
        val state = universeStates[universe]?.uberStateData ?: UberStateMap.empty
        val completions = events.filter { it.universe == universe }.map { it.x to it.y }

        for (x in 1..board.size) {
            for (y in 1..board.size) {
                val point = x to y
                if (point in completions) continue
                if (board.goalCompleted(point, state)) {
                    BingoEvent.new {
                        this.universe = universe
                        this.multiverse = this@Multiverse
                        this.manual = false
                        this.time = Date().time
                        this.x = x
                        this.y = y
                    }
                }
            }
        }
    }

    fun createSyncableBoard(universe: Universe?, spectator: Boolean = false): BingoBoard {
        val board = board ?: return BingoBoard()
        val state = universeStates[universe]?.uberStateData ?: UberStateMap.empty

        var goals = board.goals.map { (position, goal) ->
            Position(position.first, position.second) to BingoSquare(
                goal.title,
                goal.printSubText(state)
                    .map { (text, completed) -> BingoGoal(text, completed) }
            )
        }
        goals = if (spectator) {
            //spectator board: show everything anyone can see
            goals.filter { states.any { s -> board.goalVisible(it.first.x to it.first.y, s.uberStateData) } }

        } else {
            goals.filter { board.goalVisible(it.first.x to it.first.y, state) }
        }


        val completions = scoreRelevantCompletionMap()
        goals.forEach {
            it.second.completedBy =
                completions[it.first.x to it.first.y]?.map { it.id.value } ?: emptyList()
        }

        return BingoBoard(goals.map{ PositionedBingoSquare(it.first, it.second) }, board.size, board.config?.lockout ?: false)
    }

    fun fillCompletionData(syncedBoard: BingoBoard): BingoBoard {
        val completions = scoreRelevantCompletionMap()
        syncedBoard.squares.forEach { (position, square) ->
            square.completedBy =
                completions[position.x to position.y]?.map { it.id.value } ?: emptyList()
        }
        return syncedBoard
    }

    fun goalCompletionMap(): Map<Pair<Int, Int>, Set<Universe>> {
        val board = board ?: return emptyMap()
        val events = events
        return (1..board.size).flatMap { x ->
            (1..board.size).map { y ->
                x to y
            }
        }.map { (x, y) ->
            (x to y) to events.filter { it.x == x && it.y == y }.map { it.universe }.toSet()
        }.toMap()
    }

    fun lockoutGoalOwnerMap(): Map<Pair<Int, Int>, Universe?> {
        val board = board ?: return emptyMap()
        val owners = (1..board.size).flatMap { x ->
            (1..board.size).map { y ->
                x to y
            }
        }.map { (x, y) ->
            (x to y) to events.filter { it.x == x && it.y == y }.minByOrNull { it.time }?.universe
        }.toMap()
        return owners
    }

    fun scoreRelevantCompletionMap() =
        (if (board?.config?.lockout == true) lockoutGoalOwnerMap().mapValues {
            val universe = it.value ?: return@mapValues emptySet()
            setOf(universe)
        } else goalCompletionMap())

    fun bingoUniverseInfo(universe: Universe): BingoUniverseInfo {
        val board = board ?: return BingoUniverseInfo(universe.id.value, "")
        val lockout = board.config?.lockout ?: false
        val completions = scoreRelevantCompletionMap().filterValues { universe in it }.keys.toSet()

        val lines = Line.values().count { l -> l.cards(board.size).all { it in completions } }
        val squares = completions.count { it in completions }
        val scoreLine =
            if (lockout) "$squares / ${ceil((board.goals.size).toFloat() / 2f).toLong()}" else "$lines line${(if (lines == 1) "" else "s")} | $squares / ${board.goals.size}"
        return BingoUniverseInfo(
            universe.id.value,
            scoreLine,
            if (lockout) squares else lines * board.size * board.size + squares,
            squares,
            if (lockout) 0 else lines
        )
    }

    fun bingoUniverseInfo(): List<BingoUniverseInfo> {
        return universes.map { universe ->
            bingoUniverseInfo(universe)
        }.sortedByDescending { it.rank }
    }

    fun removePlayerFromWorlds(player: User, newWorld: World? = null): HashSet<Long> {
        val existingWorlds = World.findAll(player.id.value).filter { it != newWorld }
        val affectedMultiverseIds = hashSetOf<Long>()

        existingWorlds.forEach {
            if (it.members.contains(player)) {
                affectedMultiverseIds.add(it.universe.multiverse.id.value)
                it.members = SizedCollection(it.members.minus(player))

                if (it.members.empty()) {
                    it.delete()
                }

                if (it.universe.worlds.empty()) {
                    it.universe.delete()
                }
            }
        }

        return affectedMultiverseIds
    }

    companion object : LongEntityClass<Multiverse>(Multiverses)
}