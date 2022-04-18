package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import wotw.io.messages.protobuf.*
import wotw.server.bingo.BingoBoard
import wotw.server.bingo.UberStateMap
import wotw.server.database.jsonb
import wotw.server.game.handlers.GameHandlerType
import wotw.server.sync.ShareScope
import wotw.server.sync.StateCache
import java.util.*
import kotlin.math.ceil
import kotlin.to

object Multiverses : LongIdTable("multiverse") {
    val seedGroup = reference("seed_group_id", SeedGroups).nullable()
    val board = jsonb("board", BingoBoard.serializer()).nullable()

    val gameHandlerActive = bool("game_handler_active").default(false)
    val gameHandlerType = integer("game_handler_type").default(GameHandlerType.NORMAL)
    val gameHandlerStateJson = jsonb("game_handler_state", { s -> s }, { s -> s }).nullable()
}

class Multiverse(id: EntityID<Long>) : LongEntity(id) {
    var board by Multiverses.board
    var seedGroup by SeedGroup optionalReferencedOn Multiverses.seedGroup
    val universes by Universe referrersOn Universes.multiverseId
    val worlds: Collection<World>
        get() = universes.flatMap { it.worlds }
    private val states by GameState referrersOn GameStates.multiverseId
    private val events by BingoEvent referrersOn BingoEvents.multiverseId
    var spectators by User via Spectators

    var gameHandlerType by Multiverses.gameHandlerType
    var gameHandlerActive by Multiverses.gameHandlerActive
    var gameHandlerStateJson by Multiverses.gameHandlerStateJson

    val universeStates
        get() = states.filter { it.world == null }.mapNotNull { it.universe?.let { universe -> universe to it } }
            .toMap()
    val worldStates
        get() = states.mapNotNull { it.world?.let { world -> world to it } }.toMap()
    val players
        get() = worlds.flatMap { it.members }.toSet()
    val members
        get() = players + spectators

    suspend fun updateCompletions(universe: Universe) {
        val board = board ?: return
        val state =
            StateCache.get(ShareScope.UNIVERSE to universe.id.value)//universeStates[universe]?.uberStateData ?: UberStateMap.empty
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

    suspend fun createSyncableBoard(
        universe: Universe?,
        spectator: Boolean = false,
        forceAllVisible: Boolean = false
    ): BingoBoardMessage {
        val board = board ?: return BingoBoardMessage()

        val state =
            if (universe != null) StateCache.get(ShareScope.UNIVERSE to universe.id.value) else UberStateMap.empty//universeStates[universe]?.uberStateData ?: UberStateMap.empty

        var goals = board.goals.map { (position, goal) ->
            Position(position.first, position.second) to BingoSquare(
                goal.title,
                goal.printSubText(state)
                    .map { (text, completed) -> BingoGoal(text, completed) }
            )
        }
        goals = when {
            forceAllVisible -> goals
            spectator -> {
                //spectator board: show everything anyone can see
                goals.filter {
                    states.any { s ->
                        val u = s.universe
                        u != null && board.goalVisible(
                            it.first.x to it.first.y,
                            StateCache.get(ShareScope.UNIVERSE to u.id.value)
                        )
                    }
                }
            }
            else -> {
                goals.filter { board.goalVisible(it.first.x to it.first.y, state) }
            }
        }


        val completions = scoreRelevantCompletionMap()
        goals.forEach {
            it.second.completedBy =
                completions[it.first.x to it.first.y]?.map { it.id.value } ?: emptyList()
        }

        return BingoBoardMessage(
            goals.map { PositionedBingoSquare(it.first, it.second) },
            board.size,
            board.config.lockout,
        )
    }

    fun fillCompletionData(syncedBoard: BingoBoardMessage): BingoBoardMessage {
        val completions = scoreRelevantCompletionMap()
        syncedBoard.squares.forEach { (position, square) ->
            square.completedBy =
                completions[position.x to position.y]?.map { it.id.value } ?: emptyList()
        }
        return syncedBoard
    }

    private fun goalCompletionMap(): Map<Pair<Int, Int>, Set<Universe>> {
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
        val lockout = board.config.lockout
        val completions = scoreRelevantCompletionMap().filterValues { universe in it }.keys.toSet()

        var lines = 0
        (1..board.size).forEach { a ->
            // Rows
            if ((1..board.size).all { b -> completions.contains(a to b) }) {
                lines++
            }

            // Cols
            if ((1..board.size).all { b -> completions.contains(b to a) }) {
                lines++
            }
        }

        // Top left to bottom right
        if ((1..board.size).all { a -> completions.contains(a to a) }) {
            lines++
        }

        // Bottom left to top right
        if ((1..board.size).all { a -> completions.contains(a to (board.size - a + 1)) }) {
            lines++
        }

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

    fun deleteEmptyWorlds(newWorld: World? = null): HashSet<Long> {
        val affectedMultiverseIds = hashSetOf<Long>()

        val universesQueuedForDeletion = mutableSetOf<Universe>()

        for (world in worlds) {
            // Do not delete empty worlds in universes which are attached to a seed
            if (world.members.empty() && world.seed == null) {
                world.delete()
            }

            if (world.universe.worlds.all { world.members.empty() } && newWorld?.universe != world.universe) {
                universesQueuedForDeletion += world.universe
            }
        }

        for (universe in universesQueuedForDeletion) {
            universe.delete()
        }

        return affectedMultiverseIds
    }

    companion object : LongEntityClass<Multiverse>(Multiverses)
}