package wotw.server.game.handlers.league

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.LeagueGame
import wotw.server.database.model.LeagueSeason
import wotw.server.main.WotwBackendServer
import wotw.server.util.Every
import wotw.server.util.Scheduler
import wotw.server.util.logger
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

        // Remove empty time slots
        upcomingSeasonProcessingTimes.keys.removeAll { upcomingSeasonProcessingTimes[it]?.isEmpty() == true }
    }

    fun startScheduler() {
        scheduler.scheduleExecution(Every(60, TimeUnit.SECONDS))
    }

    suspend fun continueSeason(season: LeagueSeason): LeagueGame? {
        return newSuspendedTransaction {
            if (season.currentGame != null) {
                season.finishCurrentGame()
                logger().info("LeagueManager: Finished current game for season ${season.id.value}")
            }

            // Don't create more games if we reached this season's end.
            // If we don't create a new game, this season won't be scheduled
            // here anymore.
            if (!season.hasReachedGameCountLimit) {
                val game = season.createScheduledGame(server.seedGeneratorService)
                logger().info("LeagueManager: Created game ${game.id} (Multiverse ${game.multiverse.id.value}) for season ${season.id.value}")
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
        }
    }

    suspend fun recacheLeagueSeasonSchedules() {
        upcomingSeasonProcessingTimes.clear()
        newSuspendedTransaction {
            LeagueSeason.all().forEach(::cacheLeagueSeasonSchedule)
        }
    }
}
