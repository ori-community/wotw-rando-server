package wotw.server.database.model

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.timestamp
import wotw.io.messages.UniversePreset
import wotw.io.messages.WorldPreset
import wotw.server.game.handlers.GameHandlerType
import wotw.server.seedgen.SeedGeneratorService
import wotw.server.util.assertTransaction
import wotw.server.util.logger
import java.time.*
import kotlin.jvm.optionals.getOrNull
import kotlin.math.max
import kotlin.math.min

object LeagueSeasons : LongIdTable("league_seasons") {
    val name = varchar("name", 64)

    /**
     * Cron schedule in UNIX format. Schedules that are restricted to a time range
     * and don't repeat infinitely are not allowed.
     * Example: "00 12 * * fri" = at 12:00 every Friday
     */
    val scheduleCron = varchar("schedule_cron", 64)

    /**
     * Point in time from which on games should be created for this season
     */
    val scheduleStartAt = timestamp("schedule_start_at")

    /**
     * How many games this Season should host.
     */
    val gameCount = integer("game_count").default(4)

    val currentGameId = optReference("current_game_id", LeagueGames, ReferenceOption.CASCADE)

    /**
     * Base points awarded for submitting a run
     */
    val basePoints = integer("base_points").default(10)

    /**
     * Points awarded to the fastest runner
     */
    val speedPoints = integer("speed_points").default(190)

    /**
     * Speed points are awarded until x times the fastest time
     */
    val speedPointsRangeFactor = float("speed_points_range_factor").default(2.5f)

    /**
     * Number of worst scores to discard when calculating total points
     */
    val discardWorstGamesCount = integer("discard_worst_games_count").default(2)
}

class LeagueSeason(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<LeagueSeason>(LeagueSeasons) {
        private val cronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    }

    var name by LeagueSeasons.name
    var scheduleCron by LeagueSeasons.scheduleCron
    var scheduleStartAt by LeagueSeasons.scheduleStartAt
    var gameCount by LeagueSeasons.gameCount
    var basePoints by LeagueSeasons.basePoints
    var speedPoints by LeagueSeasons.speedPoints
    var speedPointsRangeFactor by LeagueSeasons.speedPointsRangeFactor
    var discardWorstGamesCount by LeagueSeasons.discardWorstGamesCount
    var currentGame by LeagueGame optionalReferencedOn LeagueSeasons.currentGameId
    val games by LeagueGame referrersOn LeagueGames.seasonId
    val memberships by LeagueSeasonMembership referrersOn LeagueSeasonMemberships.seasonId

    // Allow joining before the first game has ended
    val canJoin get() = games.count() <= 1L && currentGame == games.firstOrNull()

    val hasReachedGameCountLimit get() = games.count() >= gameCount

    fun recalculateMembershipPoints() {
        assertTransaction()

        val gamesCount = games.count().toInt()

        memberships.forEach { membership ->
            val submissions = membership.submissions.sortedBy { it.points }
            val worstSubmissionsToDiscardCount = min(
                max(
                    0,
                    discardWorstGamesCount - (gamesCount - submissions.count())
                ),
                submissions.count(),
            )

            submissions.take(worstSubmissionsToDiscardCount).forEach { submission ->
                submission.discarded = true
            }

            val countingSubmissions = submissions.drop(worstSubmissionsToDiscardCount)
            countingSubmissions.forEach { submission ->
                submission.discarded = false
            }

            membership.points = countingSubmissions.sumOf { it.points }
        }
    }

    fun finishCurrentGame() {
        assertTransaction()

        if (this.currentGame == null) {
            throw RuntimeException("Cannot finish current game because there is no current game")
        }

        this.currentGame?.recalculateSubmissionPoints()
        this.recalculateMembershipPoints()
        this.currentGame = null
    }

    suspend fun createScheduledGame(seedGeneratorService: SeedGeneratorService): LeagueGame {
        assertTransaction()

        if (this.currentGame != null) {
            throw RuntimeException("Cannot create scheduled game. Please finish the current game first.")
        }

        // TODO: Allow customizing seedgen config
        val seedGeneratorResult = seedGeneratorService.generateSeed(UniversePreset(
            worldSettings = listOf(
                WorldPreset(
                    includes = setOf("gorlek"),
                    spawn = "Random",
                    goals = setOf("Trees"),
                )
            ),
            online = true
        ))

        if (seedGeneratorResult.seed == null) {
            throw RuntimeException("Failed to generate seed for League game")
        }

        val multiverse = Multiverse.new {
            this.gameHandlerType = GameHandlerType.LEAGUE
            this.isLockable = false
            this.seed = seedGeneratorResult.seed
            this.locked = false
        }

        val game = LeagueGame.new {
            this.season = this@LeagueSeason
            this.createdAt = Instant.now()
            this.multiverse = multiverse
        }

        this.currentGame = game

        return game
    }

    fun getNextScheduledGameTime(): ZonedDateTime {
        val cron = cronParser.parse(this.scheduleCron)

        // Find the next schedule time that is after the most recent game, or now
        // if no games have been created yet
        val nextScheduledGameTime = ExecutionTime
            .forCron(cron)
            .nextExecution(this.games.maxOfOrNull { it.createdAt }?.atZone(ZoneId.systemDefault()) ?: ZonedDateTime.now())
            .getOrNull() ?: throw RuntimeException("Cron expression '$scheduleCron' does not seem to repeat infinitely")

        val scheduleStartAtZoned = this.scheduleStartAt.atZone(ZoneId.systemDefault())

        return maxOf(scheduleStartAtZoned, nextScheduledGameTime)
    }
}
