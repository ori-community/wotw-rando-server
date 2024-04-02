package wotw.server.database.model

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import wotw.io.messages.json
import wotw.io.messages.protobuf.*
import wotw.server.bingo.BingoBoard
import wotw.server.bingo.Point
import wotw.server.bingo.UberStateMap
import wotw.server.bingo.plus
import wotw.server.database.model.LeagueGames.defaultExpression
import wotw.server.game.handlers.GameHandlerType
import wotw.server.sync.UniverseStateCache
import wotw.server.util.assertTransaction
import java.util.*
import kotlin.math.ceil

object Multiverses : LongIdTable("multiverses") {
    val seedId = reference("seed_id", Seeds).nullable()
    val raceId = reference("race_id", Races).nullable()
    val board = jsonb<BingoBoard>("board", json).nullable()

    val gameHandlerActive = bool("game_handler_active").default(false)
    val gameHandlerType = integer("game_handler_type").default(GameHandlerType.NORMAL)
    val gameHandlerStateJson = jsonb("game_handler_state", { s -> s }, { s -> s }).nullable()
    val locked = bool("locked").default(false)
    val isLockable = bool("is_lockable").default(true)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
}

class Multiverse(id: EntityID<Long>) : LongEntity(id) {
    var board by Multiverses.board
    var seed by Seed optionalReferencedOn Multiverses.seedId
    var race by Race optionalReferencedOn Multiverses.raceId
    val universes by Universe referrersOn Universes.multiverseId
    val worlds: Collection<World>
        get() = universes.flatMap { it.worlds }
    private val states by GameState referrersOn GameStates.multiverseId
    val bingoCardClaims by BingoCardClaim referrersOn BingoCardClaims.multiverseId
    var spectators by User via Spectators
    val memberships by WorldMembership referrersOn WorldMemberships.multiverseId
    val createdAt by Multiverses.createdAt

    var gameHandlerType by Multiverses.gameHandlerType
    var gameHandlerActive by Multiverses.gameHandlerActive
    var gameHandlerStateJson by Multiverses.gameHandlerStateJson
    var locked by Multiverses.locked
    var isLockable by Multiverses.isLockable

    val universeStates
        get() = states
            .filter { it.world == null }
            .mapNotNull { it.universe?.let { universe -> universe to it } }
            .toMap()
    val worldStates
        get() = states
            .mapNotNull { it.world?.let { world -> world to it } }
            .toMap()
    val players
        get() = worlds
            .flatMap { it.members }
            .toSet()
    val playersAndSpectators
        get() = players + spectators

    suspend fun getNewBingoCardClaims(universe: Universe): List<BingoCardClaim> {
        val newClaims = mutableListOf<BingoCardClaim>()

        val board = board ?: return emptyList()
        val state =
            UniverseStateCache.get(universe.id.value)//universeStates[universe]?.uberStateData ?: UberStateMap.empty
        val claimedSquarePositions = bingoCardClaims.filter { it.universe == universe }.map { it.x to it.y }

        for (x in 1..board.size) {
            for (y in 1..board.size) {
                val point = x to y
                if (point in claimedSquarePositions) continue
                if (board.goals[point]?.isCompleted(state) == true) {
                    newClaims.add(BingoCardClaim.new {
                        this.universe = universe
                        this.multiverse = this@Multiverse
                        this.manual = false
                        this.time = Date().time
                        this.x = x
                        this.y = y
                    })
                }
            }
        }

        return newClaims
    }

    suspend fun createBingoBoardMessage(
        targetUniverse: Universe?,
        spectator: Boolean = false
    ): BingoBoardMessage {
        val board = board ?: return BingoBoardMessage()

        val targetUniverseState = targetUniverse?.let { u -> UniverseStateCache.get(u.id.value) } ?: UberStateMap.empty

        var goals = board.goals.map { (position, goal) ->
            (position.first to position.second) to BingoSquare(
                goal.title,
                goal.printSubText(targetUniverseState)
                    .map { (text, completed) -> BingoGoal(text, completed) }
            )
        }.toMap()

        // Populate completedBy in completion order
        bingoCardClaims
            .sortedBy { claim -> claim.time }
            .forEach { claim ->
                goals[claim.x to claim.y]?.completedBy?.add(claim.universe.id.value)
            }

        // Populate visibleFor
        this.universes.forEach { universe ->
            val ownCardClaims = this.bingoCardClaims
                .filter { claim -> claim.universe.id.value == universe.id.value }
                .sortedBy { claim -> claim.time }
                .toList()

            val initiallyVisibleGoalsPositions = board.config.discovery?.toMutableSet()

            if (initiallyVisibleGoalsPositions == null) {
                goals.values.forEach { goal -> goal.visibleFor.add(universe.id.value) }
                return@forEach
            }

            // Reveal first {config.revealFirstNCompletedGoals} claimed goals
            ownCardClaims
                .take(board.config.revealFirstNCompletedGoals)
                .forEach { claim ->
                    initiallyVisibleGoalsPositions += Point(claim.x, claim.y)
                }

            // In lockout, also reveal opponent cards and their neighbors
            val lockoutOpponentVisibleGoals = mutableListOf<Point>()
            if (board.config.lockout) {
                val opponentCardClaims = this.bingoCardClaims
                    .filter { claim -> claim.universe.id.value != universe.id.value }
                    .sortedBy { claim -> claim.time }
                    .toList()

                opponentCardClaims.forEach { claim ->
                    lockoutOpponentVisibleGoals.add(claim.x to claim.y)
                }
            }

            fun collectGoalsRecursively(start: Point, forceRevealNeighbors: Boolean = false) {
                goals[start]?.let { goal ->
                    if (goal.visibleFor.contains(universe.id.value) && !forceRevealNeighbors) {
                        return
                    }

                    goal.visibleFor.add(universe.id.value)

                    if (forceRevealNeighbors || goal.completedBy.contains(universe.id.value)) {
                        if (start.first > 1) {
                            collectGoalsRecursively(start + (-1 to 0))
                        }

                        if (start.first < board.config.boardSize) {
                            collectGoalsRecursively(start + (1 to 0))
                        }

                        if (start.second > 1) {
                            collectGoalsRecursively(start + (0 to -1))
                        }

                        if (start.second < board.config.boardSize) {
                            collectGoalsRecursively(start + (0 to 1))
                        }
                    }
                }
            }

            for (position in initiallyVisibleGoalsPositions) {
                collectGoalsRecursively(position)
            }

            for (position in lockoutOpponentVisibleGoals) {
                collectGoalsRecursively(position, true)
            }

            // In lockout, always show own goals but don't reveal neighbors for
            // all of them. Goals with revealed neighbors are handled above.
            if (board.config.lockout) {
                ownCardClaims.forEach { claim ->
                    goals[claim.x to claim.y]?.let { goal ->
                        goal.visibleFor.add(universe.id.value)
                    }
                }
            }
        }

        goals = when {
            spectator -> goals
            else -> {
                goals.filter { (_, goal) -> goal.visibleFor.contains(targetUniverse?.id?.value) }
            }
        }

        return BingoBoardMessage(
            goals.map { PositionedBingoSquare(Position(it.key.first, it.key.second), it.value) },
            board.size,
            board.config.lockout,
        )
    }

    private fun goalCompletionMap(): Map<Pair<Int, Int>, Set<Universe>> {
        val board = board ?: return emptyMap()
        val events = bingoCardClaims
        return (1..board.size).flatMap { x ->
            (1..board.size).map { y ->
                x to y
            }
        }.map { (x, y) ->
            (x to y) to events.filter { it.x == x && it.y == y }.map { it.universe }.toSet()
        }.toMap()
    }

    fun getLockoutGoalOwnerMap(): Map<Pair<Int, Int>, Universe?> {
        val board = board ?: return emptyMap()
        val owners = (1..board.size).flatMap { x ->
            (1..board.size).map { y ->
                x to y
            }
        }.map { (x, y) ->
            (x to y) to bingoCardClaims.filter { it.x == x && it.y == y }.minByOrNull { it.time }?.universe
        }.toMap()
        return owners
    }

    fun scoreRelevantCompletionMap() =
        (if (board?.config?.lockout == true) getLockoutGoalOwnerMap().mapValues {
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

    /**
     * Deletes empty worlds and universes and unlocks the game
     * if no members are left
     */
    fun cleanup(exceptWorld: World? = null) {
        val universesQueuedForDeletion = mutableSetOf<Universe>()

        for (world in worlds) {
            // Do not delete empty worlds in universes which are attached to a seed
            if (world.members.empty() && world.seed == null) {
                world.delete()
            }

            if (world.universe.worlds.all { it.members.empty() } && exceptWorld?.universe != world.universe) {
                universesQueuedForDeletion += world.universe
            }
        }

        for (universe in universesQueuedForDeletion) {
            universe.delete()
        }

        this.refresh(true)

        if (players.isEmpty()) {
            this.locked = false
        }
    }

    fun updateAutomaticWorldNames() {
        assertTransaction()

        var nextEmptyWorldNumber = 1;
        for (world in worlds) {
            if (world.hasCustomName) {
                return
            }

            val memberNames = world.members.map { member -> member.name }

            when (memberNames.size) {
                0 -> {
                    world.name = "Empty world $nextEmptyWorldNumber"
                    nextEmptyWorldNumber++
                }

                1 -> {
                    world.name = memberNames[0]
                }

                2 -> {
                    world.name = "${memberNames[0]} and ${memberNames[1]}"
                }

                else -> {
                    world.name = "${memberNames[0]} & Co."
                }
            }
        }
    }

    companion object : LongEntityClass<Multiverse>(Multiverses)
}
