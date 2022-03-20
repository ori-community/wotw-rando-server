package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.*
import wotw.io.messages.protobuf.UserInfo
import wotw.server.database.model.Seed
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
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
        get("seeds/{id}/config") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("No Seed ID found")
            val config = newSuspendedTransaction { Seed.findById(id)?.generatorConfig ?: throw NotFoundException() }
            call.respond(config)
        }

        get("seeds/{id}"){
            val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("No Seed ID found")
            val seedInfo = newSuspendedTransaction {
                val seed = Seed.findById(id) ?: throw NotFoundException()
                SeedInfo(
                    seed.id.value,
                    seed.name,
                    server.seedGeneratorService.filesForSeed(id.toString()).map { it.nameWithoutExtension },
                    seed.creator?.let{UserInfo(it.id.value, it.name, it.avatarId, null, null)},
                    seed.generatorConfig
                )
            }
            call.respond(seedInfo)
        }
        get("seeds/{id}/files") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw BadRequestException("No Seed ID found")
            newSuspendedTransaction { Seed.findById(id) ?: throw NotFoundException() }
            call.respond(server.seedGeneratorService.filesForSeed(id.toString()).map { it.nameWithoutExtension })
        }
        get("seeds/{id}/files/{file}") {
            val id = call.parameters["id"] ?: throw BadRequestException("No Seed ID found")
            val player = call.parameters["file"] ?: throw BadRequestException("No Seed-file found!")
            call.respond(server.seedGeneratorService.seedFile(id, player).readBytes())
        }
        authenticate(JWT_AUTH, optional = true) {
            post<SeedGenConfig>("seeds") { config ->

                val seed = newSuspendedTransaction {
                    Seed.new {
                        generatorConfig = config
                        creator = authenticatedUserOrNull()
                        name = config.seed ?: "Unknown!"
                    }
                }
                val result = server.seedGeneratorService.generate("seed-${seed.id.value}", config)

                if (result.isSuccess) {
                    if(config.seed == null){
                        val lines = server.seedGeneratorService.filesForSeed(seed.id.value.toString()).first().readLines()
                        if(lines.size > 3){
                            newSuspendedTransaction {
                                seed.name = lines[lines.size - 3].substringAfter("Seed: ")
                                seed.generatorConfig = seed.generatorConfig.copy(seed = seed.name)
                            }
                        }
                    }

                    call.respond(
                        HttpStatusCode.Created, SeedGenResponse(
                            result = SeedGenResult(
                                seedId = seed.id.value,
                                files = server.seedGeneratorService.filesForSeed(seed.id.value.toString())
                                    .map { it.nameWithoutExtension },
                            ),
                            warnings = result.getOrNull()?.ifBlank { null },
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


