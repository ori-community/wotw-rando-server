package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.*
import wotw.server.database.model.Seed
import wotw.server.database.model.SeedGroup
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.server.util.then
import kotlin.io.path.Path

class SeedGenEndpoint(server: WotwBackendServer) : Endpoint(server) {
    val logger = logger()
    override fun Route.initRouting() {
        get("seedgen/headers") {
            val dir = Path(System.getenv("SEEDGEN_PATH")).parent.resolve("headers")
            val result = dir.toFile().listFiles()?.map {
                val lines = it.readText().split(System.lineSeparator())
                val descrLines = lines.filter { it.startsWith("/// ") }.map { it.substringAfter("/// ") }
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
                    descrLines.firstOrNull(),
                    descrLines,
                    params
                )
            }?.toList() ?: emptyList()
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

        get("seedgen/presets") {
            val dir = Path(System.getenv("SEEDGEN_PATH")).parent.resolve("presets")
            val presetMap = dir.toFile().listFiles()?.map {
                it.name.substringAfterLast("/").substringBeforeLast(".json") to
                        relaxedJson.decodeFromString(PresetFile.serializer(), it.readText())
            }?.toMap() ?: emptyMap()
            val result = presetMap.map { it.value.fullResolve(presetMap).toPreset(it.key) }
            call.respond(result)
        }

        get("seed-groups/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("No Seed ID found")
            val seedGroupInfo = newSuspendedTransaction {
                val seedGroup = SeedGroup.findById(id) ?: throw NotFoundException()
                SeedGroupInfo(
                    seedGroup.id.value,
                    seedGroup.seeds.map { it.id.value },
                    seedGroup.creator?.let { server.infoMessagesService.generateUserInfo(it) },
                    seedGroup.generatorConfig
                )
            }
            call.respond(seedGroupInfo)
        }

        get("seeds/{id}/file") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("No Seed ID found")

            val (seedGroupFile, seedFile) = newSuspendedTransaction {
                val seed = Seed.findById(id) ?: throw NotFoundException("Seed not found")

                seed.group.file to seed.file
            }

            call.respond(server.seedGeneratorService.seedFile(seedGroupFile, seedFile).readBytes())
        }

        authenticate(JWT_AUTH, optional = true) {
            post<SeedGenConfig>("seeds") { config ->
                val (result, seedGroupId, seedIds) = newSuspendedTransaction {
                    val seedGroup = SeedGroup.new {
                        generatorConfig = config
                        creator = authenticatedUserOrNull()
                    }

                    val seedGroupFile = "${seedGroup.id.value}"
                    val result = server.seedGeneratorService.generate(seedGroupFile, config)

                    if (result.isSuccess) {
                        val seedGroupGeneratedFiles = server.seedGeneratorService.filesForSeed(seedGroup.id.value.toString())

                        // If the user did not explicitly request a seed, read it from the generated seed and save it
                        if (config.seed == null) {
                            val firstSeedLines = seedGroupGeneratedFiles.first().readLines()

                            if (firstSeedLines.size > 3) {
                                val actualSeed = firstSeedLines[firstSeedLines.size - 3].substringAfter("Seed: ")
                                seedGroup.generatorConfig = seedGroup.generatorConfig.copy(seed = actualSeed)
                            }
                        }

                        seedGroup.file = seedGroupFile

                        val seeds = seedGroupGeneratedFiles.map { seedGroupGeneratedFile ->
                            Seed.new {
                                group = seedGroup
                                file = seedGroupGeneratedFile.nameWithoutExtension
                            }
                        }
                        val seedIds = seeds.map { it.id.value }

                        result then seedGroup.id.value then seedIds
                    } else {
                        seedGroup.delete()
                        result then 0L then emptyList()
                    }
                }

                if (result.isSuccess) {
                    call.respond(
                        HttpStatusCode.Created, SeedGenResponse(
                            result = SeedGenResult(
                                seedGroupId = seedGroupId,
                                seedIds = seedIds,
                            ),
                            warnings = result.getOrNull()?.warnings?.ifBlank { null },
                        )
                    )
                } else {
                    call.respondText(
                        result.exceptionOrNull()?.message ?: "Unknown seedgen error",
                        ContentType.Text.Plain,
                        HttpStatusCode.InternalServerError
                    )
                }
            }
        }
    }

}


