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
     * {time -> [LeagueSeason IDs]
     */
    private var upcomingSeasonGames = sortedMapOf<ZonedDateTime, MutableList<Long>>()

    private val scheduler = Scheduler {
        val now = ZonedDateTime.now()

        for (time in upcomingSeasonGames.keys) {
            if (now < time) {  // We can break here because the keys are sorted in ascending order
                break
            }

            newSuspendedTransaction {
                upcomingSeasonGames[time]?.let { seasonIds ->
                    val successfullyScheduledSeasons = mutableListOf<LeagueSeason>()

                    seasonIds.forEach { seasonId ->
                        val season = LeagueSeason.findById(seasonId)

                        if (season == null) {
                            logger().error("LeagueManager: Tried to schedule season that does not exist: $seasonId")
                            return@forEach
                        }

                        season.createScheduledGame(server.seedGeneratorService)
                        successfullyScheduledSeasons.add(season)
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
        upcomingSeasonGames.keys.removeAll { upcomingSeasonGames[it]?.isEmpty() == true }
    }

    fun startScheduler() {
        scheduler.scheduleExecution(Every(60, TimeUnit.SECONDS))
    }

    private fun cacheLeagueSeasonSchedule(season: LeagueSeason) {
        season.getNextScheduledGameTime()?.let { time ->
            upcomingSeasonGames[time]?.also { cache ->
                cache.add(season.id.value)
            } ?: {
                upcomingSeasonGames[time] = mutableListOf(season.id.value)
            }
        }
    }

    suspend fun cacheLeagueSeasonSchedules() {
        upcomingSeasonGames.clear()
        newSuspendedTransaction {
            LeagueSeason.all().forEach(::cacheLeagueSeasonSchedule)
        }
    }
}
