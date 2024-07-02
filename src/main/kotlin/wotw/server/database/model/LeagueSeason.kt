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
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import wotw.io.messages.UniversePreset
import wotw.io.messages.WorldPreset
import wotw.io.messages.json
import wotw.server.game.handlers.GameHandlerType
import wotw.server.seedgen.SeedGeneratorService
import wotw.server.util.assertTransaction
import java.time.Instant
import java.time.ZoneId
import kotlin.jvm.optionals.getOrNull
import kotlin.math.abs
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
     * Speed points curve X
     * Possible values: 0.0 - 1.0
     * X coordinate of the first curve control point
     *
     * To visualize the speed points curve, visit a cubic bezier calculator (e.g. https://easings.co/)
     * and pretend it is flipped vertically.
     * The first control point there is described by the X and Y coordinates here,
     * the second control point always points to the first control point and can be moved
     * along that line using [speedPointsCurveFalloffFactor].
     */
    val speedPointsCurveX = float("speed_points_curve_x").default(0.5f)

    /**
     * Speed points curve Y
     * Possible values: 0.0 - 1.0
     * Y coordinate of the first curve control point
     */
    val speedPointsCurveY = float("speed_points_curve_y").default(0.5f)

    /**
     * Speed points curve falloff factor
     * Possible values: 0.0 - 1.0
     * The second curve control point will be placed between (1, 0) and the first
     * control point, at this relative position. (i.e. "how far the second control
     * point gets dragged towards the first one")
     */
    val speedPointsCurveFalloffFactor = float("speed_points_curve_falloff_factor").default(0.5f)

    /**
     * Number of worst scores to discard when calculating total points
     */
    val discardWorstGamesCount = integer("discard_worst_games_count").default(2)

    val shortDescription = text("short_description").default("")
    val longDescriptionMarkdown = text("long_description_markdown").default("")
    val rulesMarkdown = text("rules_markdown").default("")
    val universePreset = jsonb<UniversePreset>("universe_preset", json).default(
        UniversePreset(
            worldSettings = listOf(
                WorldPreset(
                    includes = setOf("gorlek"),
                    spawn = "Random",
                    goals = setOf("Trees"),
                )
            ),
        )
    )

    val nextContinuationAtCache = timestamp("next_continuation_at_cache").nullable()
    val backgroundImageUrl = varchar("background_image_url", 256).nullable()
    val announcementSent = bool("announcement_sent").default(false)

    val minimumInGameTimeToAllowBreaks = float("minimum_in_game_time_to_allow_breaks").default(60f * 60f * 2f)
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
    var speedPointsCurveX by LeagueSeasons.speedPointsCurveX
    var speedPointsCurveY by LeagueSeasons.speedPointsCurveY
    var speedPointsCurveFalloffFactor by LeagueSeasons.speedPointsCurveFalloffFactor
    var discardWorstGamesCount by LeagueSeasons.discardWorstGamesCount
    var shortDescription by LeagueSeasons.shortDescription
    var longDescriptionMarkdown by LeagueSeasons.longDescriptionMarkdown
    var rulesMarkdown by LeagueSeasons.rulesMarkdown
    var universePreset by LeagueSeasons.universePreset
    var currentGame by LeagueGame optionalReferencedOn LeagueSeasons.currentGameId
    val games by LeagueGame referrersOn LeagueGames.seasonId
    val memberships by LeagueSeasonMembership referrersOn LeagueSeasonMemberships.seasonId
    private var nextContinuationAtCache by LeagueSeasons.nextContinuationAtCache
    var backgroundImageUrl by LeagueSeasons.backgroundImageUrl
    var announcementSent by LeagueSeasons.announcementSent
    var minimumInGameTimeToAllowBreaks by LeagueSeasons.minimumInGameTimeToAllowBreaks

    val nextContinuationAt: Instant get() = this.nextContinuationAtCache ?: this.updateNextContinuationAtTimestamp()

    // Allow joining before the first game has ended
    val canJoin get() = games.count() <= 1L && currentGame == games.firstOrNull()

    val hasReachedGameCountLimit get() = games.count() >= gameCount

    fun recalculateMembershipPointsAndRanks() {
        assertTransaction()

        val seasonProgress = (games - currentGame).count() / gameCount.toFloat()
        val discardedGameRankingMultiplier = 1.0f - seasonProgress

        for (membership in memberships) {
            val submissions = membership
                .submissions
                .filter { !it.game.isCurrent }
                .sortedBy { it.points }

            val worstSubmissionsToDiscardCount = min(
                discardWorstGamesCount,
                max(submissions.count() - 1, 0),
            )

            val submissionsWithATime = submissions.filter { it.time != null }
            val averagePoints = if (submissionsWithATime.isNotEmpty()) {
                submissionsWithATime.sumOf { it.points }.toFloat() / submissionsWithATime.size.toFloat()
            } else 0f

            val discardingSubmissionWeights = mutableMapOf<LeagueGameSubmission, Float>()

            submissions.take(worstSubmissionsToDiscardCount).forEach { submission ->
                discardingSubmissionWeights[submission] = if (submission.time != null) {
                    1f - abs(submission.points - averagePoints) / (this.basePoints + this.speedPoints).toFloat()
                } else 0f
            }

            val totalDiscardingSubmissionWeight = discardingSubmissionWeights.values.sum()

            submissions.take(worstSubmissionsToDiscardCount).forEach { submission ->
                val weight = discardingSubmissionWeights[submission] ?: throw RuntimeException("This should never happen")

                submission.rankingMultiplier = if (totalDiscardingSubmissionWeight > 0f) {
                    discardedGameRankingMultiplier * (worstSubmissionsToDiscardCount * (weight / totalDiscardingSubmissionWeight))
                } else discardedGameRankingMultiplier
            }

            submissions.drop(worstSubmissionsToDiscardCount).forEach { submission ->
                submission.rankingMultiplier = 1.0f
            }

            membership.points = submissions.sumOf { (it.points * it.rankingMultiplier).toInt() }
        }

        val membershipsGroupedByPointsInDescendingOrder = memberships
            .groupBy { it.points }
            .toSortedMap()
            .values
            .reversed()

        var nextRank = 1
        for (membershipsWithSamePoints in membershipsGroupedByPointsInDescendingOrder) {
            for (membership in membershipsWithSamePoints) {
                val newRank = if (membership.points == 0) {
                    null
                } else {
                    nextRank
                }

                val currentRank = membership.rank

                if (newRank != null && currentRank != null) {
                    membership.lastRankDelta = newRank - currentRank
                } else {
                    membership.lastRankDelta = null
                }

                membership.rank = newRank
            }

            nextRank += membershipsWithSamePoints.size
        }
    }

    fun finishCurrentGame() {
        assertTransaction()

        this.currentGame?.let { currentGame ->
            // Create DNF submissions for players who didn't submit yet
            this.memberships.forEach { membership ->
                if (currentGame.submissions.none { it.membership.id == membership.id }) {
                    LeagueGameSubmission.new {
                        this.game = currentGame
                        this.membership = membership
                        this.time = null
                        this.saveFile = null
                        this.validated = true
                    }.flush()
                }
            }

            currentGame.recalculateSubmissionPointsAndRanks()
            currentGame.refresh(true)
        } ?: throw RuntimeException("Cannot finish current game because there is no current game")

        this.recalculateMembershipPointsAndRanks()
        this.currentGame = null
    }

    suspend fun createScheduledGame(seedGeneratorService: SeedGeneratorService): LeagueGame {
        assertTransaction()

        if (this.currentGame != null) {
            throw RuntimeException("Cannot create scheduled game. Please finish the current game first.")
        }

        val seedGeneratorResult = seedGeneratorService.generateSeed(this.universePreset)

        if (seedGeneratorResult.seed == null) {
            throw RuntimeException("Failed to generate seed for League game")
        }

        seedGeneratorResult.seed.allowDownload = false

        val multiverse = Multiverse.new {
            this.gameHandlerType = GameHandlerType.LEAGUE
            this.isLockable = false
            this.seed = seedGeneratorResult.seed
            this.locked = false
        }

        val game = LeagueGame.new {
            this.gameNumber = (this@LeagueSeason.games.count() + 1).toInt()
            this.season = this@LeagueSeason
            this.createdAt = Instant.now()
            this.multiverse = multiverse
        }

        this.currentGame = game

        return game
    }

    fun updateNextContinuationAtTimestamp(): Instant {
        val cron = cronParser.parse(this.scheduleCron)

        // Find the next schedule time that is after the most recent game, or now
        // if no games have been created yet

        val scheduleStartAtZoned = this.scheduleStartAt.atZone(ZoneId.systemDefault())

        val time = ExecutionTime
            .forCron(cron)
            .nextExecution(this.games.maxOfOrNull { it.createdAt }?.atZone(ZoneId.systemDefault()) ?: scheduleStartAtZoned)
            .getOrNull()
            ?.toInstant()
            ?: throw RuntimeException("Cron expression '$scheduleCron' does not seem to repeat infinitely")

        this.nextContinuationAtCache = time

        return time
    }
}
