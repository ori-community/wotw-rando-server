package wotw.server.seedgen

import io.ktor.features.*
import io.ktor.html.*
import kotlinx.coroutines.future.await
import wotw.io.messages.SeedGenConfig
import wotw.server.database.model.Seed
import wotw.server.exception.ServerConfigurationException
import wotw.server.main.WotwBackendServer
import wotw.server.util.CompletableFuture
import wotw.server.util.logger
import java.io.File
import java.io.FilenameFilter
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SeedGeneratorService(private val server: WotwBackendServer) {
    private val numThreads = System.getenv("SEEDGEN_TRHEADS")?.toIntOrNull() ?: 4
    private val threadPool = Executors.newFixedThreadPool(4)
    private val seedgenExec =
        System.getenv("SEEDGEN_PATH") ?: throw ServerConfigurationException("No seed generator available!")
    val invalidCharacterRegex = Regex("[^a-zA-Z0-9_]")
    fun sanitizedPlayerName(player: String) = player.replace(invalidCharacterRegex, "_")

    fun filesForSeed(seedId: String): List<File> {
        var pathString = "${System.getenv("SEED_DIR")}${File.separator}seed-${seedId}"
        val dir = Path.of(pathString).toFile()
        if(dir.exists() && dir.isDirectory){
            return dir.listFiles(FilenameFilter { _, name -> name.endsWith(".wotwr") })?.toList() ?: emptyList()
        }
        pathString += ".wotwr"
        val file = Path.of(pathString).toFile()
        if (!file.exists() || file.isDirectory)
            return emptyList()
        return listOf(file)
    }

    fun seedFile(seedId: String, player: String? = null): File {
        var pathString = "${System.getenv("SEED_DIR")}${File.separator}seed-${seedId}"
        if (File(pathString).isDirectory && player != null) {
            val sanitized = server.seedGeneratorService.sanitizedPlayerName(player)
            pathString += "${File.separator}$sanitized"
        }
        pathString += ".wotwr"
        val file = Path.of(pathString).toFile()
        if (!file.exists() || file.isDirectory)
            throw NotFoundException()
        return file
    }
    fun seedFile(seed: Seed) = seedFile(seed.id.value.toString())

    fun validate(config: SeedGenConfig) {
    }

    suspend fun generate(fileName: String, config: SeedGenConfig): Result<String> {
        validate(config)
        val commandString = buildSeedGenCommand(fileName, config)

        logger().info("Generating seed using command:")
        logger().info(commandString.joinToString(" "))
        val timeout = System.getenv("SEEDGEN_TIMEOUT")?.toLongOrNull() ?: 30000

        val processBuilder = ProcessBuilder(*commandString)
            .directory(File(seedgenExec.substringBeforeLast(File.separator)))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        var handle: Process? = null

        val future = CompletableFuture.supplyAsync(threadPool) {
            val process = processBuilder.start()
            handle = process

            if (config.customHeaders != null) {
                process.outputStream.writer().use { writer ->
                    config.customHeaders.flatMap { it.split("\n") }.flatMap { it.split("\r") }
                        .filter { it.isNotBlank() }.forEach {
                            writer.write(it)
                            writer.write("\r\n")
                        }
                }
            }
            process.outputStream.close()
            process.inputStream.readAllBytes()

            val err = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
            val exitCode = process.waitFor()

            err.lines().forEach {
                logger().info(it)
            }

            if (exitCode != 0)
                Result.failure(Exception(err))
            else{
                Result.success(err)
            }
        }

        return try {
            future.orTimeout(timeout, TimeUnit.MILLISECONDS).await() as Result<String>
        } catch (e: TimeoutException) {
            handle?.destroyForcibly()
            Result.failure(Exception("seedgen timed out!"))
        }
    }

    private fun buildSeedGenCommand(fileName: String, config: SeedGenConfig): Array<String> {
        var command = "$seedgenExec seed --verbose".split(" ").toTypedArray() + config.flags.flatMap { it.split(" ") }

        command += "--difficulty"
        command += config.difficulty

        if (config.goals.isNotEmpty()) {
            command += "--goals"
            command += config.goals.map { it.lowercase() }
        }
        if (config.glitches.isNotEmpty()) {
            command += "--glitches"
            command += config.glitches.map { it.lowercase() }
        }
        if (config.presets.isNotEmpty()) {
            command += "--presets"
            command += config.presets
        }
        if (config.headers.isNotEmpty()) {
            command += "--headers"
            command += config.headers
        }
        if (!config.multiNames.isNullOrEmpty()) {
            command += "--names"
            command += config.multiNames.map { sanitizedPlayerName(it) }
            command += "--worlds"
            command += config.multiNames.size.toString()
        }
        if (!config.seed.isNullOrBlank()) {
            command += "--seed"
            command += config.seed
        }
        if (!config.spawn.isNullOrBlank()) {
            command += "--spawn"
            command += config.spawn
        }

        if (!config.headerArgs.isNullOrEmpty()) {
            config.headerArgs.map { "${it.key}=${it.value}" }.forEach {
                command += "--args"
                command += it
            }
        }

        command += "--"
        command += fileName

        return command
    }

}
