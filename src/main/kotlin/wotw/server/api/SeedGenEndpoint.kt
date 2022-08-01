package wotw.server.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.*
import wotw.server.database.model.Seed
import wotw.server.database.model.WorldSeed
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.server.util.then
import kotlin.io.path.Path

class SeedGenEndpoint(server: WotwBackendServer) : Endpoint(server) {
    val logger = logger()
    override fun Route.initRouting() {
        get("seedgen/headers") {
            val dir = Path(System.getenv("SEEDGEN_PATH")).parent.resolve("headers")

            val result = dir.toFile().listFiles()
                ?.filter { it.isFile }
                ?.map {
                    val lines = it.readText().split(System.lineSeparator())
                    val description = lines.filter { line -> line.startsWith("/// ") }.map { line -> line.substringAfter("/// ") }
                    val params = lines.mapIndexedNotNull { i, s ->
                        if (s.startsWith("!!parameter ")) {
                            val (name, info) = s.substringAfter("!!parameter ").split(" ", limit = 2)
                            val (type, default) = if (info.contains(":")) {
                                info.split(":", limit = 2)
                            } else listOf("string", info)
                            HeaderParameterDef(
                                name,
                                default,
                                type,
                                lines.subList(0, i).takeLastWhile { it.startsWith("//// ") }
                                    .map { it.substringAfter("//// ") })
                        } else null
                    }

                    HeaderFileEntry(
                        it.name.substringAfterLast("/").substringBeforeLast("."),
                        lines.firstOrNull()?.startsWith("#hide") == true,
                        description.firstOrNull(),
                        description,
                        params
                    )
                }
                ?.toList() ?: emptyList()
            call.respond(result)
        }

        get("seedgen/headers/{name}/file") {
            val headersDir = Path(System.getenv("SEEDGEN_PATH")).parent.resolve("headers")
            val headerFile = headersDir.resolve(call.parameters["name"] + ".wotwrh")

            if (headerFile.parent != headersDir) {
                throw BadRequestException("Invalid header requested")
            }

            call.respond(HttpStatusCode.OK, headerFile.toFile().readText())
        }

        get("seedgen/world-presets") {
            val dir = Path(System.getenv("SEEDGEN_PATH")).parent.resolve("presets")

            val worldPresetFileMap = dir.toFile().listFiles()
                .filter { it.isFile }
                .associate {
                    it.name.substringAfterLast("/").substringBeforeLast(".json") to
                            relaxedJson.decodeFromString(WorldPresetFile.serializer(), it.readText())
                }

            val result = worldPresetFileMap.mapValues {
                it.value.resolveAndMergeIncludes(worldPresetFileMap).toWorldPreset()
            }

            call.respond(result)
        }

        get("seeds/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("No Seed ID found")
            val seedInfo = newSuspendedTransaction {
                val seed = Seed.findById(id) ?: throw NotFoundException()

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
                worldSeed.content
            }

            call.respond(worldSeedContent)
        }

        authenticate(JWT_AUTH, optional = true) {
            post<Preset>("seeds") { config ->
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
    }

}


