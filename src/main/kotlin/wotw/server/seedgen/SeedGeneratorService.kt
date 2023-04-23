package wotw.server.seedgen

import kotlinx.coroutines.future.await
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import wotw.io.messages.SeedgenCliOutput
import wotw.io.messages.UniversePreset
import wotw.io.messages.json
import wotw.server.database.model.*
import wotw.server.exception.ServerConfigurationException
import wotw.server.main.WotwBackendServer
import wotw.server.util.CompletableFuture
import wotw.server.util.logger
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class SeedGeneratorGenerationResult(
    val warnings: String,
    val output: SeedgenCliOutput,
)

data class SeedGeneratorSeedResult(
    val generationResult: Result<SeedGeneratorGenerationResult>,
    val seed: Seed?,
)

class SeedGeneratorService(private val server: WotwBackendServer) {

    private val threadPool = Executors.newFixedThreadPool(4)
    private val seedgenExec =
        System.getenv("SEEDGEN_PATH") ?: throw ServerConfigurationException("No seed generator available!")

    suspend fun generate(config: UniversePreset): Result<SeedGeneratorGenerationResult> {
        val timeout = System.getenv("SEEDGEN_TIMEOUT")?.toLongOrNull() ?: 30000

        val processBuilder = ProcessBuilder(
            seedgenExec,
            "seed",
            "--verbose",
            "--tostdout",
            "--json",
        )
            .directory(File(seedgenExec.substringBeforeLast(File.separator)))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        var handle: Process? = null

        val future = CompletableFuture.supplyAsync(threadPool) {
            val process = processBuilder.start()
            handle = process

            process.outputStream.writer().use { writer ->
                writer.write(json.encodeToString(config))
            }

            val stdoutString = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            val stderrString = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
            val exitCode = process.waitFor()

            stderrString.lines().forEach {
                logger().info(it)
            }

            if (exitCode != 0)
                Result.failure(Exception(stderrString))
            else {
                val output = json.decodeFromString<SeedgenCliOutput>(stdoutString)
                Result.success(SeedGeneratorGenerationResult(stderrString, output))
            }
        }

        return try {
            future.orTimeout(timeout, TimeUnit.MILLISECONDS).await() as Result<SeedGeneratorGenerationResult>
        } catch (e: TimeoutException) {
            handle?.destroyForcibly()
            Result.failure(Exception("seedgen timed out!"))
        }
    }

    suspend fun generateSeed(config: UniversePreset, creator: User? = null): SeedGeneratorSeedResult {
        val result = server.seedGeneratorService.generate(config)

        result.getOrNull()?.let { seedgenResult ->
            val seed = Seed.new {
                this.seedgenConfig = config
                this.creator = creator
                this.spoiler = seedgenResult.output.spoiler
                this.spoilerText = seedgenResult.output.spoilerText
            }

            val output = seedgenResult.output

            output.seedFiles.forEachIndexed { index, seedFile ->
                WorldSeed.new {
                    this.content = seedFile
                    this.worldIndex = index
                    this.seed = seed
                }
            }

            seed.refresh(true)

            return SeedGeneratorSeedResult(result, seed)
        }

        return SeedGeneratorSeedResult(result, null)
    }
}
