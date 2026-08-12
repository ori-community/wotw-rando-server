package wotw.server.api

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.*
import wotw.server.database.model.Seed
import wotw.server.database.model.WorldSeed
import wotw.server.exception.ForbiddenException
import wotw.server.main.WotwBackendServer
import wotw.server.seedgen.SeedgenException
import wotw.server.util.doAfterTransaction
import wotw.server.util.logger
import wotw.server.util.then

class SeedGenEndpoint(server: WotwBackendServer) : Endpoint(server) {
    private val logger = logger()

    override fun Route.initRouting() {
        authenticate(JWT_AUTH) {
            get("seeds/{id}") {
                val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("No Seed ID found")
                val seedInfo = newSuspendedTransaction {
                    val seed = Seed.findById(id) ?: throw NotFoundException()

                    if (!seed.allowDownload) {
                        throw ForbiddenException("You cannot download this seed")
                    }

                    SeedInfo(
                        seed.id.value,
                        seed.worldSeeds.map { it.id.value },
                        seed.creator?.let { server.infoMessagesService.generateUserInfo(it) },
                    )
                }
                call.respond(seedInfo)
            }

            get("world-seeds/{id}/file") {
                val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("No Seed ID found")

                val worldSeedContent = newSuspendedTransaction {
                    val worldSeed = WorldSeed.findById(id) ?: throw NotFoundException("World seed not found")

                    if (!worldSeed.seed.allowDownload) {
                        throw ForbiddenException("You cannot download this seed")
                    }

                    worldSeed.content
                }

                call.respond(worldSeedContent)
            }

            post<JsonObject>("seeds") { config ->
                try {
                    val (seedId, worldSeedIds, logs) = newSuspendedTransaction {
                        val result = server.seedgenApiService.generateSeed(config, authenticatedUser())

                        (result.seed?.id?.value ?: 0L) then
                                (result.seed?.worldSeeds?.map { it.id.value } ?: listOf()) then
                                result.logs
                    }

                    call.respond(
                        HttpStatusCode.Created, GeneratedSeedResponse(
                            seedId,
                            worldSeedIds,
                            logs,
                        )
                    )
                } catch (e: SeedgenException) {
                    call.respondText(
                        e.message ?: "Unknown seedgen error",
                        ContentType.Text.Plain,
                        HttpStatusCode.InternalServerError,
                    )
                }
            }

            get("seeds/{id}/spoiler") {
                val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("No Seed ID found")

                val acceptItems = call.request.acceptItems()

                val (contentType, body) = newSuspendedTransaction {
                    val seed = Seed.findById(id) ?: throw NotFoundException()
                    val user = authenticatedUser()

                    if (!seed.allowDownload) {
                        throw ForbiddenException("You cannot download this seed/spoiler")
                    }

                    if (!seed.spoilerDownloads.contains(user)) {
                        seed.spoilerDownloads = SizedCollection(seed.spoilerDownloads + user)

                        val affectedMultiverseIds = seed.multiverses.map { m -> m.id.value }

                        doAfterTransaction {
                            affectedMultiverseIds.forEach { multiverseId ->
                                server.gameHandlerRegistry.getHandler(multiverseId).notifyMultiverseOrClientInfoChanged()
                            }
                        }
                    }

                    for (acceptItem in acceptItems) {
                        if (acceptItem.value == "text/plain") {
                            return@newSuspendedTransaction "text/plain" to  seed.spoilerText
                        } else if (acceptItem.value == "application/json") {
                            return@newSuspendedTransaction "application/json" to seed.spoiler.toString()
                        }
                    }

                    return@newSuspendedTransaction "application/json" to seed.spoiler.toString()
                }

                call.response.header("Content-Type", contentType)
                call.respond(body)
            }
        }
    }

}


