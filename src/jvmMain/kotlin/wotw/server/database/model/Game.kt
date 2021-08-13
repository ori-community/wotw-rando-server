package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import wotw.io.messages.GameProperties
import wotw.io.messages.protobuf.*
import wotw.server.bingo.BingoCard
import wotw.server.bingo.Line
import wotw.server.bingo.UberStateMap
import wotw.server.database.jsonb
import java.util.*
import kotlin.math.ceil
import kotlin.to

object Games : LongIdTable("game") {
    override val primaryKey = PrimaryKey(id)
    val seed = reference("seed", Seeds).nullable()
    val board = jsonb("board", BingoCard.serializer()).nullable()
    val props = jsonb("props", GameProperties.serializer()).nullable()
}

class Game(id: EntityID<Long>) : LongEntity(id) {
    //    var seed by Games.seed
    var board by Games.board
    var props by Games.props
    val teams by Team referrersOn Teams.gameId
    private val states by GameState referrersOn GameStates.gameId
    private val events by BingoEvent referrersOn BingoEvents.gameId
    val teamStates
        get() = states.map { it.team to it }.toMap()
    val players
        get() = teams.flatMap { it.members }
    val gameInfo
        get() = GameInfo(teams.map {
            val members = it.members.map { m -> UserInfo(m.id.value, m.name, m.avatarId) }
            TeamInfo(it.id.value, it.name, members)
        })

    fun updateCompletions(team: Team) {
        val board = board ?: return
        val state = teamStates[team]?.uberStateData ?: UberStateMap.empty
        val completions = events.filter { it.team == team }.map { it.x to it.y }

        for (x in 1..board.size) {
            for (y in 1..board.size) {
                val point = x to y
                if (point in completions) continue
                if (board.goalCompleted(point, state)) {
                    BingoEvent.new {
                        this.team = team
                        this.game = this@Game
                        this.manual = false
                        this.time = Date().time
                        this.x = x
                        this.y = y
                    }
                }
            }
        }
    }

    fun createSyncableBoard(team: Team?, spectator: Boolean = false): BingoBoard {
        val board = board ?: return BingoBoard()
        val state = teamStates[team]?.uberStateData ?: UberStateMap.empty

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
                completions[it.first.x to it.first.y]?.map { it.teamInfo } ?: emptyList()
        }

        return BingoBoard(goals.map{ PositionedBingoSquare(it.first, it.second) }, board.size)
    }

    fun fillCompletionData(syncedBoard: BingoBoard): BingoBoard {
        val completions = scoreRelevantCompletionMap()
        syncedBoard.squares.forEach { (position, square) ->
            square.completedBy =
                completions[position.x to position.y]?.map { it.teamInfo } ?: emptyList()
        }
        return syncedBoard
    }

    fun goalCompletionMap(): Map<Pair<Int, Int>, Set<Team>> {
        val board = board ?: return emptyMap()
        val events = events
        return (1..board.size).flatMap { x ->
            (1..board.size).map { y ->
                x to y
            }
        }.map { (x, y) ->
            (x to y) to events.filter { it.x == x && it.y == y }.map { it.team }.toSet()
        }.toMap()
    }

    fun lockoutGoalOwnerMap(): Map<Pair<Int, Int>, Team?> {
        val board = board ?: return emptyMap()
        val owners = (1..board.size).flatMap { x ->
            (1..board.size).map { y ->
                x to y
            }
        }.map { (x, y) ->
            (x to y) to events.filter { it.x == x && it.y == y }.minByOrNull { it.time }?.team
        }.toMap()
        return owners
    }

    fun scoreRelevantCompletionMap() =
        (if (board?.config?.lockout == true) lockoutGoalOwnerMap().mapValues {
            val team = it.value ?: return@mapValues emptySet()
            setOf(team)
        } else goalCompletionMap())


    fun teamInfo(team: Team): BingoTeamInfo {
        val board = board ?: return BingoTeamInfo(team.id.value, "")
        val lockout = board.config?.lockout ?: false
        val completions = scoreRelevantCompletionMap().filterValues { team in it }.keys.toSet()

        val lines = Line.values().count { l -> l.cards(board.size).all { it in completions } }
        val squares = completions.count { it in completions }
        val scoreLine =
            if (lockout) "$squares / ${ceil((board.goals.size).toFloat() / 2f).toLong()}" else "$lines line${(if (lines == 1) "" else "s")} | $squares / ${board.goals.size}"
        return BingoTeamInfo(
            team.id.value,
            scoreLine,
            if (lockout) squares else lines * board.size * board.size + squares,
            squares,
            if (lockout) 0 else lines
        )
    }

    fun teamInfo(): List<BingoTeamInfo> {
        return teams.map { team ->
            teamInfo(team)
        }.sortedByDescending { it.rank }
    }

    companion object : LongEntityClass<Game>(Games)
}