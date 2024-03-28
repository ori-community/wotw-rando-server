package wotw.server.game.handlers.league

import dev.kord.common.entity.Snowflake
import dev.kord.rest.builder.component.ActionRowBuilder
import dev.kord.rest.builder.message.allowedMentions
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.dao.EntityChangeType
import org.jetbrains.exposed.dao.EntityHook
import org.jetbrains.exposed.dao.toEntity
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.*
import wotw.server.game.WorldCreatedEvent
import wotw.server.game.WorldDeletedEvent
import wotw.server.main.WotwBackendServer
import wotw.server.util.Every
import wotw.server.util.Scheduler
import wotw.server.util.logger
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit


/**
 * Manages scheduling and initialization of League games
 */
class LeagueManager(val server: WotwBackendServer) {
    /**
     * Times when Seasons need to be processed.
     * Processing = finishing current game and eventually creating next game.
     * {time -> [LeagueSeason IDs]
     */
    private var upcomingSeasonProcessingTimes = sortedMapOf<ZonedDateTime, MutableList<Long>>()
    private var upcomingSeasonReminderTimes = sortedMapOf<ZonedDateTime, MutableList<Long>>()

    private val scheduler = Scheduler {
        val now = ZonedDateTime.now()

        for (time in upcomingSeasonProcessingTimes.keys) {
            if (now < time) {  // We can break here because the keys are sorted in ascending order
                break
            }

            newSuspendedTransaction {
                upcomingSeasonProcessingTimes[time]?.let { seasonIds ->
                    val successfullyContinuedSeasons = mutableListOf<LeagueSeason>()

                    seasonIds.forEach { seasonId ->
                        val season = LeagueSeason.findById(seasonId)

                        if (season == null) {
                            logger().error("LeagueManager: Tried to continue season that does not exist: $seasonId")
                            return@forEach
                        }

                        try {
                            continueSeason(season)
                            successfullyContinuedSeasons.add(season)
                        } catch (e: Exception) {
                            logger().error("LeagueManager: Failed to create next game for season $seasonId. Will retry next time...")
                            e.printStackTrace()
                        }
                    }

                    // Remove all successfully continued seasons...
                    seasonIds.removeAll(successfullyContinuedSeasons.map { it.id.value })

                    // ...and cache upcoming schedules for these seasons
                    successfullyContinuedSeasons.forEach { season ->
                        cacheLeagueSeasonSchedule(season)
                    }
                }
            }
        }

        for (time in upcomingSeasonReminderTimes.keys) {
            if (now < time) {  // We can break here because the keys are sorted in ascending order
                break
            }

            newSuspendedTransaction {
                upcomingSeasonReminderTimes[time]?.let { seasonIds ->
                    seasonIds.forEach { seasonId ->
                        val season = LeagueSeason.findById(seasonId)

                        if (season == null) {
                            logger().error("LeagueManager: Tried to send reminder for season that does not exist: $seasonId")
                            return@forEach
                        }

                        season.currentGame?.let { currentGame ->
                            if (currentGame.reminderSent) {
                                return@forEach
                            }

                            val missingMemberships = season.memberships.filter { membership ->
                                currentGame.submissions.none { submission -> submission.membership.id == membership.id }
                            }

                            if (missingMemberships.isEmpty()) {  // No reminder necessary...
                                return@forEach
                            }

                            server.ifKord { kord ->
                                kord.rest.channel.createMessage(Snowflake(1223009403347669042)) {
                                    val memberSnowflakes = missingMemberships.map { Snowflake(it.user.id.value) }
                                    val submittableUntilTimestamp = season.getNextScheduledGameTime().toEpochSecond()

                                    this.content = """
                                        ## ${season.name}: Reminder for Game ${currentGame.gameNumber}
                                        
                                        Remaining players: ${memberSnowflakes.joinToString(", ") { "<@${it.value}>" }}
                                        
                                        **You have time to play this game until <t:${submittableUntilTimestamp}:f> (<t:${submittableUntilTimestamp}:R>)!**
                                    """.trimIndent()

                                    this.suppressEmbeds = true

                                    this.allowedMentions {
                                        this.users.addAll(memberSnowflakes)
                                    }
                                }
                            }

                            currentGame.reminderSent = true
                        }
                    }

                    seasonIds.removeAll(seasonIds)
                }
            }
        }

        // Remove empty time slots
        upcomingSeasonProcessingTimes.keys.removeAll { upcomingSeasonProcessingTimes[it]?.isEmpty() == true }
        upcomingSeasonReminderTimes.keys.removeAll { upcomingSeasonReminderTimes[it]?.isEmpty() == true }
    }

    fun setup() {
        scheduler.scheduleExecution(Every(60, TimeUnit.SECONDS))

        EntityHook.subscribe {
            runBlocking {
                it.toEntity(LeagueSeason.Companion)?.let { season ->
                    if (it.changeType == EntityChangeType.Created) {
                        server.ifKord { kord ->
                            kord.rest.channel.createMessage(Snowflake(1223009403347669042)) {
                                val joinableUntilTimestamp = season.getNextScheduledGameTime().toEpochSecond()

                                this.content = """
                                    # ${season.name}: Season signups opened! <:orilurk:883216525140574218>
                                    
                                    A new season of the Ori and the Will of the Wisps Randomizer League is coming up!
                                    
                                    **You can join until <t:${joinableUntilTimestamp}:f> (<t:${joinableUntilTimestamp}:R>)**
                                    
                                    Read more about the seed settings and rules on the season page.
                                """.trimIndent()

                                this.suppressEmbeds = true

                                this.components = mutableListOf(
                                    ActionRowBuilder().also {
                                        it.linkButton(server.getUiUrl("/league/seasons/${season.id.value}")) {
                                            this.label = "Open Season"
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun continueSeason(season: LeagueSeason): LeagueGame? {
        return newSuspendedTransaction {
            val previousGame = season.currentGame

            if (season.currentGame != null) {
                season.finishCurrentGame()
                logger().info("LeagueManager: Finished current game for season ${season.id.value}")
            }

            // Don't create more games if we reached this season's end.
            // If we don't create a new game, this season won't be scheduled
            // here anymore.
            if (season.hasReachedGameCountLimit) {
                server.ifKord { kord ->
                    kord.rest.channel.createMessage(Snowflake(1223009403347669042)) {
                        val memberSnowflakes = season.memberships
                            .filter { it.points > 0 }
                            .map { Snowflake(it.user.id.value) }

                        this.content = """
                            ## Season '${season.name}' has ended <:orihype:895977640131964928>
                            
                            Season players: ${memberSnowflakes.joinToString(", ") { "<@${it.value}>" }}
                            
                            **Thanks for playing!**
                        """.trimIndent()

                        this.suppressEmbeds = true

                        this.allowedMentions {
                            this.users.addAll(memberSnowflakes)
                        }
                    }
                }
            } else {
                val game = season.createScheduledGame(server.seedGeneratorService)
                logger().info("LeagueManager: Created game ${game.id} (Multiverse ${game.multiverse.id.value}) for season ${season.id.value}")

                server.ifKord { kord ->
                    kord.rest.channel.createMessage(Snowflake(1223009403347669042)) {
                        val memberSnowflakes = season.memberships.map { Snowflake(it.user.id.value) }

                        val gameNumberOrdinal = when (game.gameNumber.toString().last()) {
                            '1' -> "${game.gameNumber}st"
                            '2' -> "${game.gameNumber}nd"
                            '3' -> "${game.gameNumber}rd"
                            else -> "${game.gameNumber}th"
                        }

                        this.content = """
                            ## ${season.name}: Game ${game.gameNumber}
                            
                            The $gameNumberOrdinal of ${season.gameCount} games is now available to play. Have fun!
                        """.trimIndent()

                        if (season.canJoin) {
                            this.content = """
                                
                                If you did not join this season yet, you can **still join until this game ended**!
                            """.trimIndent()
                        }

                        this.content += """
                            
                            Season players: ${memberSnowflakes.joinToString(", ") { "<@${it.value}>" }}
                        """.trimIndent()

                        this.allowedMentions {
                            this.users.addAll(memberSnowflakes)
                        }

                        this.suppressEmbeds = true

                        this.components = mutableListOf(
                            ActionRowBuilder().also {
                                it.linkButton(server.getUiUrl("/league/game/${game.id.value}")) {
                                    this.label = "Open Game"
                                }

                                if (previousGame != null) {
                                    it.linkButton(server.getUiUrl("/league/game/${previousGame.id.value}")) {
                                        this.label = "Previous Game"
                                    }
                                }

                                it.linkButton(server.getUiUrl("/league/seasons/${season.id.value}")) {
                                    this.label = if (season.canJoin) {
                                        "Join Season"
                                    } else {
                                        "Season Leaderboard"
                                    }
                                }
                            }
                        )
                    }
                }

                return@newSuspendedTransaction game
            }

            return@newSuspendedTransaction null
        }
    }

    private fun cacheLeagueSeasonSchedule(season: LeagueSeason) {
        // If we have a current game, we need to schedule to clean up the potentially last game.
        // If there's no current game, we only need to schedule if more games are supposed to be
        // created for that season.
        if (season.currentGame != null || !season.hasReachedGameCountLimit) {
            val time = season.getNextScheduledGameTime()

            logger().debug("LeagueManager: Scheduled season for processing {} at {}", season.id.value, time)

            upcomingSeasonProcessingTimes[time]?.also { cache ->
                cache.add(season.id.value)
            } ?: run {
                upcomingSeasonProcessingTimes[time] = mutableListOf(season.id.value)
            }

            season.currentGame?.let { currentGame ->
                // Remind at 80% of the time, but at most 24 hours before submission ends
                val oneDayBeforeEnd = time.minusHours(24)
                val eightyPercentBeforeEnd = ZonedDateTime.ofInstant(
                    Instant.ofEpochSecond(
                        (currentGame.createdAt.epochSecond + (time.toEpochSecond() - currentGame.createdAt.epochSecond) * 0.8).toLong()
                    ), ZoneId.systemDefault(),
                )

                val reminderTime = if (eightyPercentBeforeEnd.isAfter(oneDayBeforeEnd)) {
                    eightyPercentBeforeEnd
                } else {
                    oneDayBeforeEnd
                }

                logger().debug("LeagueManager: Scheduled season reminder for season {} at {}", season.id.value, time)

                upcomingSeasonReminderTimes[reminderTime]?.also { cache ->
                    cache.add(season.id.value)
                } ?: run {
                    upcomingSeasonReminderTimes[reminderTime] = mutableListOf(season.id.value)
                }
            }
        }
    }

    suspend fun recacheLeagueSeasonSchedules() {
        upcomingSeasonProcessingTimes.clear()
        upcomingSeasonReminderTimes.clear()
        newSuspendedTransaction {
            LeagueSeason.all().forEach(::cacheLeagueSeasonSchedule)
        }
    }
}