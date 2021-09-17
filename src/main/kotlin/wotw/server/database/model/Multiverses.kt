package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.SizedCollection
import wotw.io.messages.VerseProperties
import wotw.io.messages.protobuf.*
import wotw.server.bingo.BingoCard
import wotw.server.bingo.Line
import wotw.server.bingo.UberStateMap
import wotw.server.database.jsonb
import java.util.*
import kotlin.math.ceil
import kotlin.to

object Multiverses : LongIdTable("multiverse") {
    override val primaryKey = PrimaryKey(id)
    val seed = reference("seed", Seeds).nullable()
    val board = jsonb("board", BingoCard.serializer()).nullable()
    val props = jsonb("props", VerseProperties.serializer()).nullable()
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
        get() = states.mapNotNull { it.universe?.let{universe -> universe to it}}.toMap()
    val worldStates
        get() = states.mapNotNull { it.world?.let{world -> world to it}}.toMap()
    val players
        get() = worlds.flatMap { it.members }
    val multiverseInfoMessage
        get() = MultiverseInfoMessage(id.value, universes.map { it.universeInfo }, board != null, spectators.map { it.userInfo })
    val members
        get() = players + spectators

    fun updateCompletions(world: World) {
        val board = board ?: return
        val state = worldStates[world]?.uberStateData ?: UberStateMap.empty
        val completions = events.filter { it.world == world }.map { it.x to it.y }

        for (x in 1..board.size) {
            for (y in 1..board.size) {
                val point = x to y
                if (point in completions) continue
                if (board.goalCompleted(point, state)) {
                    BingoEvent.new {
                        this.world = world
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

    fun createSyncableBoard(world: World?, spectator: Boolean = false): BingoBoard {
        val board = board ?: return BingoBoard()
        val state = worldStates[world]?.uberStateData ?: UberStateMap.empty

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
                completions[it.first.x to it.first.y]?.map { it.worldInfo } ?: emptyList()
        }

        return BingoBoard(goals.map{ PositionedBingoSquare(it.first, it.second) }, board.size)
    }

    fun fillCompletionData(syncedBoard: BingoBoard): BingoBoard {
        val completions = scoreRelevantCompletionMap()
        syncedBoard.squares.forEach { (position, square) ->
            square.completedBy =
                completions[position.x to position.y]?.map { it.worldInfo } ?: emptyList()
        }
        return syncedBoard
    }

    fun goalCompletionMap(): Map<Pair<Int, Int>, Set<World>> {
        val board = board ?: return emptyMap()
        val events = events
        return (1..board.size).flatMap { x ->
            (1..board.size).map { y ->
                x to y
            }
        }.map { (x, y) ->
            (x to y) to events.filter { it.x == x && it.y == y }.map { it.world }.toSet()
        }.toMap()
    }

    fun lockoutGoalOwnerMap(): Map<Pair<Int, Int>, World?> {
        val board = board ?: return emptyMap()
        val owners = (1..board.size).flatMap { x ->
            (1..board.size).map { y ->
                x to y
            }
        }.map { (x, y) ->
            (x to y) to events.filter { it.x == x && it.y == y }.minByOrNull { it.time }?.world
        }.toMap()
        return owners
    }

    fun scoreRelevantCompletionMap() =
        (if (board?.config?.lockout == true) lockoutGoalOwnerMap().mapValues {
            val world = it.value ?: return@mapValues emptySet()
            setOf(world)
        } else goalCompletionMap())

    fun bingoWorldInfo(world: World): BingoUniverseInfo {
        val board = board ?: return BingoUniverseInfo(world.id.value, "")
        val lockout = board.config?.lockout ?: false
        val completions = scoreRelevantCompletionMap().filterValues { world in it }.keys.toSet()

        val lines = Line.values().count { l -> l.cards(board.size).all { it in completions } }
        val squares = completions.count { it in completions }
        val scoreLine =
            if (lockout) "$squares / ${ceil((board.goals.size).toFloat() / 2f).toLong()}" else "$lines line${(if (lines == 1) "" else "s")} | $squares / ${board.goals.size}"
        return BingoUniverseInfo(
            world.id.value,
            scoreLine,
            if (lockout) squares else lines * board.size * board.size + squares,
            squares,
            if (lockout) 0 else lines
        )
    }

    fun bingoWorldInfo(): List<BingoUniverseInfo> {
        return worlds.map { world ->
            bingoWorldInfo(world)
        }.sortedByDescending { it.rank }
    }

    fun removePlayerFromWorlds(player: User) {
        val existingWorld = World.find(this.id.value, player.id.value)
        if (existingWorld != null) {
            existingWorld.members = SizedCollection(existingWorld.members.minus(player))

            if (existingWorld.members.count() == 0L) {
                existingWorld.delete()
            }

            if (existingWorld.universe.worlds.empty()) {
                existingWorld.universe.delete()
            }
        }
    }

    companion object : LongEntityClass<Multiverse>(Multiverses)
}