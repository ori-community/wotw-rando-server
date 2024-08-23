package wotw.server.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.ClaimBingoCardRequest
import wotw.io.messages.CreateLeagueSeasonRequest
import wotw.io.messages.admin.RemoteTrackerEndpointDescriptor
import wotw.server.database.model.BingoCardClaim
import wotw.server.database.model.LeagueSeason
import wotw.server.database.model.User
import wotw.server.main.WotwBackendServer
import wotw.server.util.doAfterTransaction
import java.time.Instant
import java.util.*

class DeveloperEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(JWT_AUTH) {
            route("dev") {
                get("/caches/population/{world_membership_id}") {
                    requireDeveloper()

                    val worldMembershipId = call.parameters["world_membership_id"]?.toLongOrNull() ?: throw BadRequestException("world_membership_id required")

                    val populationCacheContent = newSuspendedTransaction {
                        server.worldMembershipEnvironmentCache.get(worldMembershipId)
                    }

                    call.respond(populationCacheContent)
                }

                post<ClaimBingoCardRequest>("/bingo/claim") { request ->
                    requireDeveloper()

                    newSuspendedTransaction {
                        val user = authenticatedUser()
                        val multiverse = user.mostRecentMultiverse ?: throw BadRequestException("You are currently not in a multiverse")
                        val universe = user.mostRecentWorldMembership?.world?.universe ?: throw BadRequestException("You are currently not in a multiverse")
                        val board = multiverse.cachedBoard ?: throw NotFoundException("The multiverse you are in does not have a bingo board")
                        val claims = multiverse.bingoCardClaims
                        val multiverseId = multiverse.id.value

                        if (
                            claims.none { it.universe.id.value == universe.id.value && it.x == request.x && it.y == request.y } && (
                                !board.config.lockout || claims.none { it.x == request.x && it.y == request.y }
                            )
                        ) {
                            BingoCardClaim.new {
                                this.universe = universe
                                this.multiverse = multiverse
                                this.manual = false
                                this.time = Date().time
                                this.x = request.x
                                this.y = request.y
                            }

                            doAfterTransaction {
                                server.sync.syncMultiverseProgress(multiverseId)
                            }
                        }
                    }

                    call.respond(HttpStatusCode.Created)
                }

                get("/remote-trackers") {
                    requireDeveloper()

                    call.respond(newSuspendedTransaction {
                        server.connections.remoteTrackerEndpoints.map {
                            RemoteTrackerEndpointDescriptor(
                                it.key,
                                it.value.broadcasterConnection?.principal?.userId?.let { userId ->
                                    User.findById(userId)?.let { user ->
                                        server.infoMessagesService.generateUserInfo(user)
                                    }
                                },
                                it.value.listeners.map { listener ->
                                    listener.webSocket.call.request.origin.remoteHost
                                },
                                it.value.expires,
                            )
                        }
                    })
                }

                get("/handlers/{multiverse_id}/state") {
                    requireDeveloper()

                    val multiverseId = call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("multiverse_id required")

                    val handler = server.gameHandlerRegistry.getHandler(multiverseId)
                    var state = handler.serializeState() ?: "{}"

                    handler.getAdditionalDebugInformation()?.let { debugInfo ->
                        state += "\n\n$debugInfo"
                    }

                    call.respondText(state, ContentType("text", "plain"))
                }

                post<CreateLeagueSeasonRequest>("/league/season") { request ->
                    requireDeveloper()

                    val seasonId = newSuspendedTransaction {
                        LeagueSeason.new {
                            name = request.name
                            scheduleCron = request.cron
                            scheduleStartAt = Instant.now()
                            gameCount = request.gameCount
                            shortDescription = request.shortDescription
                            longDescriptionMarkdown = request.longDescriptionMarkdown
                            rulesMarkdown = request.rulesMarkdown
                            backgroundImageUrl = request.backgroundImageUrl?.ifBlank { null }
                        }.id.value
                    }

                    server.leagueManager.recacheLeagueSeasonSchedules()

                    call.respondText(seasonId.toString(), status = HttpStatusCode.Created)
                }

                post("/league/season/{season_id}/continue") {
                    requireDeveloper()

                    val seasonId = call.parameters["season_id"]?.toLongOrNull() ?: throw BadRequestException("Invalid season ID")

                    newSuspendedTransaction {
                        val season = LeagueSeason.findById(seasonId) ?: throw NotFoundException("Season $seasonId not found")
                        server.leagueManager.continueSeason(season)
                    }

                    server.leagueManager.recacheLeagueSeasonSchedules()

                    call.respond(HttpStatusCode.Created)
                }

                post("/league/season/recalculate-points") {
                    requireDeveloper()

                    newSuspendedTransaction {
                        LeagueSeason.all().forEach { season ->
                            season.games.forEach { game ->
                                game.recalculateSubmissionPointsAndRanks()
                            }

                            season.recalculateMembershipPointsAndRanks()
                        }
                    }

                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }
}



