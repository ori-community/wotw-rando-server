package wotw.server.game.handlers.league

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
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
class LeagueManager(server: WotwBackendServer) {
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
                    val successfullyScheduledSeasons = mutableListOf<LeagueSeason>()

                    seasonIds.forEach { seasonId ->
                        val season = LeagueSeason.findById(seasonId)

                        if (season == null) {
                            logger().error("LeagueManager: Tried to schedule season that does not exist: $seasonId")
                            return@forEach
                        }

                        try {
                            newSuspendedTransaction {
                                if (season.currentGame != null) {
                                    season.finishCurrentGame()
                                }

                                // Don't create more games if we reached this season's end.
                                // If we don't create a new game, this season won't be scheduled
                                // here anymore.
                                if (!season.hasReachedGameCountLimit) {
                                    season.createScheduledGame(server.seedGeneratorService)
                                }

                                successfullyScheduledSeasons.add(season)
                            }
                        } catch (e: Exception) {
                            logger().error("LeagueManager: Failed to create scheduled game for season $seasonId. Will retry next time...")
                            e.printStackTrace()
                        }
                    }

                    // Remove all successfully scheduled seasons...
                    seasonIds.removeAll(successfullyScheduledSeasons.map { it.id.value })

                    // ...and cache upcoming schedules for these seasons
                    successfullyScheduledSeasons.forEach { season ->
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

    private fun cacheLeagueSeasonSchedule(season: LeagueSeason) {
        // If we have a current game, we need to schedule to clean up the potentially last game.
        // If there's no current game, we only need to schedule if more games are supposed to be
        // created for that season.
        if (season.currentGame != null || !season.hasReachedGameCountLimit) {
            val time = season.getNextScheduledGameTime()

            logger().debug("LeagueManager: Scheduled season for processing {} at {}", season.id.value, time)

            upcomingSeasonProcessingTimes[time]?.also { cache ->
                cache.add(season.id.value)
            } ?: {
                upcomingSeasonProcessingTimes[time] = mutableListOf(season.id.value)
            }
        }
    }

    suspend fun cacheLeagueSeasonSchedules() {
        upcomingSeasonProcessingTimes.clear()
        newSuspendedTransaction {
            LeagueSeason.all().forEach(::cacheLeagueSeasonSchedule)
        }
    }
}
