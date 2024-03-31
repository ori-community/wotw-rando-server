package wotw.server.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.*
import wotw.server.game.WotwSaveFileReader
import wotw.server.game.handlers.league.LeagueGameHandler
import wotw.server.main.WotwBackendServer

class LeagueEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(JWT_AUTH) {
            get("league/seasons") {
                call.respond(newSuspendedTransaction {
                    LeagueSeason.all()
                        .sortedByDescending { it.scheduleStartAt }
                        .map { server.infoMessagesService.generateLeagueSeasonInfo(it) }
                })
            }

            get("league/seasons/{season_id}") {
                val seasonId = call.parameters["season_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable season_id")

                call.respond(newSuspendedTransaction {
                    val season = LeagueSeason.findById(seasonId) ?: throw NotFoundException("Season not found")
                    server.infoMessagesService.generateLeagueSeasonInfo(season, authenticatedUserOrNull())
                })
            }

            get("league/games/{game_id}") {
                val gameId = call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable game_id")

                call.respond(newSuspendedTransaction {
                    val game = LeagueGame.findById(gameId) ?: throw NotFoundException("Game not found")
                    server.infoMessagesService.generateLeagueGameInfo(game, authenticatedUser())
                })
            }

            get("league/games/{game_id}/submissions") {
                val gameId = call.parameters["game_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable game_id")

                call.respond(newSuspendedTransaction {
                    val game = LeagueGame.findById(gameId) ?: throw NotFoundException("Game not found")

                    val user = authenticatedUser()
                    val handler = server.gameHandlerRegistry.getHandler(game.multiverse) as? LeagueGameHandler ?: throw BadRequestException("This is not a league game")

                    // Return full submissions if the game is over or the user submitted for this game,
                    // otherwise return reduced information
                    if (handler.didSubmitForThisGame(user) || !handler.getLeagueGame().isCurrent) {
                        game.submissions.map(server.infoMessagesService::generateLeagueGameSubmissionInfo)
                    } else {
                        game.submissions.map(server.infoMessagesService::generateReducedLeagueGameSubmissionInfo)
                    }
                })
            }

            post("league/seasons/{season_id}/membership") {
                val seasonId = call.parameters["season_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

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
            }

            get("league/{multiverse_id}/game") {
                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

                call.respond(newSuspendedTransaction {
                    val game = LeagueGame.find { LeagueGames.multiverseId eq multiverseId }.firstOrNull() ?: throw NotFoundException("Game not found")
                    server.infoMessagesService.generateLeagueGameInfo(game, authenticatedUser())
                })
            }

            post("league/{multiverse_id}/submission") {
                val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull() ?: throw BadRequestException("Unparsable MultiverseID")

                val (handler, canSubmit, expectedSaveGuid) = newSuspendedTransaction {
                    val user = authenticatedUser()
                    val worldMembership = WorldMembership
                        .find { (WorldMemberships.userId eq user.id.value) and (WorldMemberships.multiverseId eq multiverseId) }
                        .firstOrNull() ?: throw BadRequestException("You are not part of that multiverse")

                    val handler = server.gameHandlerRegistry.getHandler(worldMembership.multiverse) as? LeagueGameHandler ?: throw BadRequestException("This is not a league game")
                    Triple(handler, handler.canSubmit(user), handler.getPlayerSaveGuid(worldMembership))
                }

                if (!canSubmit) {
                    call.respond(HttpStatusCode.UnprocessableEntity, "The League game expired or you did submit already")
                    return@post
                }

                // Parse save file and check if the Save File GUID is correct
                val saveFilePacket = call.receiveChannel().readRemaining(1024 * 256 /* max 256 KB */)
                val saveFileBuffer = saveFilePacket.readByteBuffer()
                val saveFileReader = WotwSaveFileReader(saveFileBuffer)
                val saveData = saveFileReader.parse()

                if (saveData == null) {
                    call.respond(HttpStatusCode.UnprocessableEntity, "Invalid save file data")
                    return@post
                }

                if (expectedSaveGuid != saveData.saveFileGuid) {
                    call.respond(HttpStatusCode.UnprocessableEntity, "Invalid save file GUID, expected $expectedSaveGuid but got ${saveData.saveFileGuid}")
                    return@post
                }

                val saveFileArray = ByteArray(saveFileBuffer.limit() - 1)
                saveFileBuffer.rewind()
                saveFileBuffer.get(saveFileArray)

                newSuspendedTransaction {
                    handler.createSubmission(authenticatedUser(), saveData.inGameTime, saveFileArray)
                }

                call.respond(HttpStatusCode.Created)
            }
        }
    }
}
