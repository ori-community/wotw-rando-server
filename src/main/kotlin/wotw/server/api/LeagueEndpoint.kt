package wotw.server.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.notExists
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.SeedGenResponse
import wotw.io.messages.SeedGenResult
import wotw.io.messages.SetSubmissionVideoUrlRequest
import wotw.server.constants.LEAGUE_MAX_DISCONNECTED_TIME
import wotw.server.database.model.*
import wotw.server.exception.ForbiddenException
import wotw.server.game.WotwSaveFileReader
import wotw.server.game.handlers.league.LeagueGameHandler
import wotw.server.main.WotwBackendServer
import wotw.server.util.NTuple5
import wotw.server.util.logger
import wotw.server.util.then
import java.nio.ByteBuffer
import kotlin.math.floor

class LeagueEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(JWT_AUTH, optional = true) {
            get("league/seasons") {
                call.respond(newSuspendedTransaction {
                    LeagueSeason.all()
                        .sortedWith(
                            compareBy(LeagueSeason::nextContinuationAt)
                                .thenByDescending(LeagueSeason::scheduleStartAt)
                        )
                        .map { server.infoMessagesService.generateLeagueSeasonInfo(it) }
                })
            }

            get("league/seasons/upcoming") {
                call.respond(newSuspendedTransaction {
                    LeagueSeason.find {
                        notExists(
                            LeagueGames
                                .selectAll()
                                .where {
                                    (LeagueGames.seasonId eq LeagueSeasons.id) and (LeagueGames.gameNumber greater 1)
                                }
                        )
                    }
                        .sortedBy { it.nextContinuationAt }
                        .map { server.infoMessagesService.generateLeagueSeasonInfo(it) }
                })
            }

            get("league/seasons/{season_id}") {
                val seasonId =
                    call.parameters["season_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable season_id")

                call.respond(newSuspendedTransaction {
                    val season = LeagueSeason.findById(seasonId) ?: throw NotFoundException("Season not found")
                    server.infoMessagesService.generateLeagueSeasonInfo(season, authenticatedUserOrNull())
                })
            }

            get("league/games/{game_id}") {
                val gameId =
                    call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable game_id")

                call.respond(newSuspendedTransaction {
                    val game = LeagueGame.findById(gameId) ?: throw NotFoundException("Game not found")
                    server.infoMessagesService.generateLeagueGameInfo(game, authenticatedUserOrNull())
                })
            }

            get("league/games/{game_id}/submissions") {
                val gameId =
                    call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable game_id")

                call.respond(newSuspendedTransaction {
                    val game = LeagueGame.findById(gameId) ?: throw NotFoundException("Game not found")

                    val user = authenticatedUserOrNull()
                    val handler = server.gameHandlerRegistry.getHandler(game.multiverse) as? LeagueGameHandler
                        ?: throw BadRequestException("This is not a league game")

                    // Return full submissions if the game is over or the user submitted for this game,
                    // otherwise return reduced information

                    val leagueGame = handler.getLeagueGame()

                    if (((user != null && handler.didSubmitForThisGame(user)) || !leagueGame.isCurrent)) {
                        game.submissions.map(server.infoMessagesService::generateLeagueGameSubmissionInfo)
                    } else if (!leagueGame.shouldHideSubmissionsForUnfinishedPlayers()) {
                        game.submissions.map(server.infoMessagesService::generateReducedLeagueGameSubmissionInfo)
                    } else {
                        emptyList()
                    }
                })
            }

            get("league/{multiverse_id}/game") {
                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull()
                    ?: throw BadRequestException("Unparsable MultiverseID")

                call.respond(newSuspendedTransaction {
                    val game = LeagueGame.find { LeagueGames.multiverseId eq multiverseId }.firstOrNull()
                        ?: throw NotFoundException("Game not found")
                    server.infoMessagesService.generateLeagueGameInfo(game, authenticatedUserOrNull())
                })
            }

            post("league/seasons/{season_id}/training-seed") {
                val seasonId =
                    call.parameters["season_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable season_id")

                val (result, seedId, worldSeedIds) = newSuspendedTransaction {
                    val season = LeagueSeason.findById(seasonId) ?: throw NotFoundException("Season not found")

                    val result =
                        server.seedGeneratorService.generateSeed(season.universePreset, authenticatedUserOrNull())

                    result.generationResult then
                            (result.seed?.id?.value ?: 0L) then
                            (result.seed?.worldSeeds?.map { it.id.value } ?: listOf())
                }

                if (result.isSuccess) {
                    call.respond(
                        HttpStatusCode.Created, SeedGenResponse(
                            result = SeedGenResult(
                                seedId = seedId,
                                worldSeedIds = worldSeedIds,
                            ),
                            warnings = result.getOrNull()?.warnings?.ifBlank { null },
                        )
                    )
                } else {
                    call.respondText(
                        result.exceptionOrNull()?.message ?: "Unknown seedgen error",
                        ContentType.Text.Plain,
                        HttpStatusCode.InternalServerError,
                    )
                }

                return@post
            }

            get("league/submissions/{submission_id}/gamestats") {
                val submissionId = call.parameters["submission_id"]?.toLongOrNull()
                    ?: throw BadRequestException("Unparsable submission_id")

                newSuspendedTransaction {
                    val submission =
                        LeagueGameSubmission.findById(submissionId) ?: throw NotFoundException("Submission not found")
                    val user = authenticatedUserOrNull()

                    val handler =
                        server.gameHandlerRegistry.getHandler(submission.game.multiverse) as? LeagueGameHandler
                            ?: throw BadRequestException("This is not a league game")

                    val leagueGame = handler.getLeagueGame()

                    if (((user != null && handler.didSubmitForThisGame(user)) || !leagueGame.isCurrent)) {
                        submission.saveFile?.let { saveFile ->
                            val saveFileData = WotwSaveFileReader(ByteBuffer.wrap(saveFile))

                            call.respond(
                                server.infoMessagesService.generateSaveFileGameStatsInfo(
                                    saveFileData.parse() ?: throw RuntimeException("Unable to parse save file data")
                                )
                            )
                        } ?: run {
                            call.respond(HttpStatusCode.NotFound, "Save file not found")
                            return@newSuspendedTransaction
                        }
                    } else {
                        call.respond(HttpStatusCode.Unauthorized, "Unauthorized to fetch game stats")
                    }
                }
            }
        }

        authenticate(JWT_AUTH) {
            get("league/seasons/pending") {
                call.respond(newSuspendedTransaction {
                    val user = authenticatedUser()

                    val pendingSeasons = LeagueSeason.wrapRows(
                        LeagueSeasons
                            .innerJoin(LeagueSeasonMemberships)
                            .leftJoin(LeagueGameSubmissions)
                            .innerJoin(LeagueGames)
                            .select(LeagueSeasons.columns)
                            .where {
                                (LeagueSeasons.currentGameId neq null) and
                                        (LeagueSeasons.id eq LeagueSeasonMemberships.seasonId) and
                                        (LeagueSeasonMemberships.userId eq user.id) and
                                        (LeagueGames.id eq LeagueSeasons.currentGameId) and
                                        notExists(
                                            LeagueGameSubmissions
                                                .selectAll()
                                                .where {
                                                    (LeagueGameSubmissions.gameId eq LeagueSeasons.currentGameId) and
                                                            (LeagueGameSubmissions.membershipId eq LeagueSeasonMemberships.id)
                                                }
                                        )
                            }
                            .groupBy(LeagueSeasons.id)
                    )

                    pendingSeasons.map {
                        server.infoMessagesService.generateLeagueSeasonInfo(it, user)
                    }
                })
            }

            post("league/seasons/{season_id}/membership") {
                val seasonId =
                    call.parameters["season_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

                wotwPrincipal().require(Scope.LEAGUE_SEASON_JOIN)

                val seasonInfo = newSuspendedTransaction {
                    val season = LeagueSeason.findById(seasonId) ?: throw NotFoundException("Season not found")
                    val user = authenticatedUser()

                    if (!season.canJoin) {
                        throw BadRequestException("You cannot join this season")
                    }

                    if (season.memberships.any { it.user.id == user.id }) {
                        throw BadRequestException("User is already part of that season")
                    }

                    LeagueSeasonMembership.new {
                        this.season = season
                        this.user = user
                    }

                    season.refresh()

                    server.infoMessagesService.generateLeagueSeasonInfo(season, user)
                }

                call.respond(HttpStatusCode.Created, seasonInfo)
                return@post
            }

            post("league/{multiverse_id}/submission") {
                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull()
                    ?: throw BadRequestException("Unparsable MultiverseID")

                wotwPrincipal().require(Scope.LEAGUE_SUBMISSION_CREATE)

                val (handler, canSubmit, expectedSaveGuid, playerDisconnectedTime, minimumInGameTimeToAllowBreaks) = newSuspendedTransaction {
                    val user = authenticatedUser()
                    val worldMembership = WorldMembership
                        .find { (WorldMemberships.userId eq user.id.value) and (WorldMemberships.multiverseId eq multiverseId) }
                        .firstOrNull() ?: throw BadRequestException("You are not part of that multiverse")

                    val handler =
                        server.gameHandlerRegistry.getHandler(worldMembership.multiverse) as? LeagueGameHandler
                            ?: throw BadRequestException("This is not a league game")

                    NTuple5(
                        handler,
                        handler.canSubmit(user),
                        handler.getPlayerSaveGuid(worldMembership),
                        handler.getPlayerDisconnectedTime(worldMembership),
                        handler.getLeagueGame().season.minimumInGameTimeToAllowBreaks,
                    )
                }

                if (!canSubmit) {
                    call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        "The League game expired or you did submit already"
                    )
                    return@post
                }

                // Parse save file and check if the Save File GUID is correct
                val saveFilePacket = call.receiveChannel().readRemaining(1024 * 1024 /* max 1 MB */)
                val saveFileArray = saveFilePacket.readByteArray()
                val saveFileReader = WotwSaveFileReader(ByteBuffer.wrap(saveFileArray))
                val saveData = saveFileReader.parse()

                if (saveData == null) {
                    call.respond(HttpStatusCode.UnprocessableEntity, "Invalid save file data")
                    return@post
                }

                if (expectedSaveGuid != saveData.saveFileGuid) {
                    call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        "Invalid save file GUID, expected $expectedSaveGuid but got ${saveData.saveFileGuid}"
                    )
                    return@post
                }

                val autoValidationErrors = mutableListOf<String>()
                var timeOverride: Float? = null

                if (
                    saveData.inGameTime < minimumInGameTimeToAllowBreaks &&
                    playerDisconnectedTime > LEAGUE_MAX_DISCONNECTED_TIME
                ) {
                    timeOverride = minimumInGameTimeToAllowBreaks
                    autoValidationErrors += """
                        Taking breaks during the run is only allowed after ${floor(minimumInGameTimeToAllowBreaks / 60f)} minutes of in-game time.
                        You were disconnected for $playerDisconnectedTime seconds.
                    """.trimIndent()
                }

                newSuspendedTransaction {
                    handler.createSubmission(authenticatedUser()) {
                        if (timeOverride != null) {
                            it.time = timeOverride
                            it.originalTime = saveData.inGameTime
                        } else {
                            it.time = saveData.inGameTime
                        }

                        it.saveFile = saveFileArray

                        if (autoValidationErrors.isNotEmpty()) {
                            it.autoValidationErrors = autoValidationErrors.joinToString("\n")
                            it.validated = false
                        } else {
                            it.validated = true
                        }
                    }
                }

                if (autoValidationErrors.isNotEmpty()) {
                    call.respond(HttpStatusCode(298, "Auto Validation failed"), autoValidationErrors.joinToString("\n"))
                } else {
                    call.respond(HttpStatusCode.Created)
                }

                return@post
            }

            post<SetSubmissionVideoUrlRequest>("league/submissions/{submission_id}/video-url") { request ->
                val submissionId = call.parameters["submission_id"]?.toLongOrNull()
                    ?: throw BadRequestException("Unparsable Submission ID")

                wotwPrincipal().require(Scope.LEAGUE_VIDEO_MODIFY)

                newSuspendedTransaction {
                    val submission =
                        LeagueGameSubmission.findById(submissionId) ?: throw NotFoundException("Submission not found")

                    if (submission.membership.user.id != authenticatedUser().id) {
                        throw ForbiddenException("You can only set the video URL of your own submissions")
                    }

                    request.videoUrl?.let { url ->
                        val allowedRegexes = listOf(
                            Regex("""^(https?://)?(www\.)?(youtube\.com/watch|youtu\.be/)"""),
                            Regex("""^(https?://)?(www\.)?twitch\.tv/videos"""),
                        )

                        if (allowedRegexes.none { it.containsMatchIn(url) }) {
                            throw BadRequestException("Currently, only videos on YouTube and Twitch are supported.\nIf you want to use another video platform, let us know!")
                        }
                    }

                    submission.videoUrl = request.videoUrl
                }

                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
