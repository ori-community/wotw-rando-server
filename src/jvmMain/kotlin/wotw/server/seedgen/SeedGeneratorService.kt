package wotw.server.seedgen

import kotlinx.coroutines.future.await
import wotw.io.messages.SeedGenConfig
import wotw.server.exception.ServerConfigurationException
import wotw.server.main.WotwBackendServer
import wotw.server.util.CompletableFuture
import java.io.File
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


    fun validate(config: SeedGenConfig) {
    }

    suspend fun generate(fileName: String, config: SeedGenConfig): Result<String> {
        validate(config)
        val commandString = buildSeedGenCommand(fileName, config)

        println("Generating seed using command:")
        println(commandString.joinToString(" "))
        val timeout = System.getenv("SEEDGEN_TIMEOUT")?.toLongOrNull() ?: 30000

        val processBuilder = ProcessBuilder(*commandString)
            .directory(File(seedgenExec.substringBeforeLast(File.separator)))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.INHERIT)

        var handle: Process? = null

        val future = CompletableFuture.supplyAsync(threadPool) {
            val process = processBuilder.start()
            handle = process

            if (config.custom_headers != null) {
                process.outputStream.writer().use { writer ->
                    config.custom_headers.flatMap { it.split("\n") }.flatMap { it.split("\r") }
                        .filter { it.isNotBlank() }.forEach {
                            writer.write(it)
                            writer.write("\r\n")
                        }
                }
            }
            process.outputStream.close()
            process.inputStream.readAllBytes()

            val err = process.errorStream.readAllBytes().toString(Charsets.UTF_8)

            if (process.waitFor() != 0)
                Result.failure(Exception(err))
            else
                Result.success("yay")
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
        if (config.goals.isNotEmpty()) {
            command += "--goals"
            command += config.goals.map { it.lowercase() }
        }
        if (config.logic.isNotEmpty()) {
            command += "--logic"
            command += config.logic.map { it.lowercase() }
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

        command += "--"
        command += fileName

        return command
    }

}
