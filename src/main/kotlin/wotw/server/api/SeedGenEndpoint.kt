package wotw.server.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.*
import wotw.server.database.model.Seed
import wotw.server.database.model.WorldSeed
import wotw.server.exception.ForbiddenException
import wotw.server.main.WotwBackendServer
import wotw.server.util.doAfterTransaction
import wotw.server.util.logger
import wotw.server.util.then
import kotlin.io.path.Path

class SeedGenEndpoint(server: WotwBackendServer) : Endpoint(server) {
    private val logger = logger()

    private val seedgenLibrary: SeedgenLibrary
    private val seedgenLibraryVersion: String = System.currentTimeMillis().toString(16)

    init {
        val worldPresetsPath = Path(System.getenv("SEEDGEN_PATH")).parent.resolve("world_presets")
        val worldPresets = worldPresetsPath.toFile().listFiles()!!
            .filter { it.isFile }
            .associate {
                it.name.substringAfterLast("/").substringBeforeLast(".json") to
                        relaxedJson.decodeFromString(WorldPreset.serializer(), it.readText())
            }

        val headersPath = Path(System.getenv("SEEDGEN_PATH")).parent.resolve("headers")
        val headers = headersPath.toFile().listFiles()!!
            .filter { it.isFile }
            .associate {
                val name = it.name.substringAfterLast("/").substringBeforeLast(".wotwrh")
                name to Header(name, it.readText())
            }

        seedgenLibrary = SeedgenLibrary(
            "Official",
            "Official Library of presets and headers",
            seedgenLibraryVersion,
            worldPresets,
            headers,
        )
    }

    override fun Route.initRouting() {
        get("seedgen/library") {
            call.respond(seedgenLibrary)
        }

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
                    seed.seedgenConfig,
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

        authenticate(JWT_AUTH, optional = true) {
            post<UniversePreset>("seeds") { config ->
                val (result, seedId, worldSeedIds) = newSuspendedTransaction {
                    val result = server.seedGeneratorService.generateSeed(config, authenticatedUserOrNull())

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
            }
        }

        authenticate(JWT_AUTH) {
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


