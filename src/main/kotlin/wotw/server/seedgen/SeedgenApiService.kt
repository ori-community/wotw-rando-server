@file:OptIn(ExperimentalSerializationApi::class)

package wotw.server.seedgen

import dev.kord.rest.request.isError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.cbor.cbor
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import wotw.io.messages.json
import wotw.server.database.model.Seed
import wotw.server.database.model.User
import wotw.server.database.model.WorldSeed
import wotw.server.util.assertTransaction

data class SeedgenApiGenerateResult(
    val seed: Seed?,
)

@Serializable
data class SeedgenApiGenerateResponse(
    @SerialName("worlds") val worlds: List<ByteArray>,
    @SerialName("json_spoiler") val jsonSpoiler: String,
    @SerialName("text_spoiler") val textSpoiler: String,
)

class SeedgenException(message: String) : Exception(message)

class SeedgenApiService {
    val seedgenHttpClient = HttpClient(CIO) {
        defaultRequest {
            // TODO: Make this configurable to be able to route traffic internally
            //       to not make the reverse proxy sad
            url("https://seedgen-api.wotw.orirando.com")
        }

        install(ContentNegotiation) {
            json()
            cbor(Cbor {
                alwaysUseByteString = true
            })
        }
    }

    suspend fun generateSeedFromPreset(preset: JsonObject, creator: User? = null): SeedgenApiGenerateResult {
        val httpResponse: HttpResponse = seedgenHttpClient.post("/presets/universe/apply") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(JsonObject(mapOf("presets" to JsonArray(listOf(preset)))))
        }

        if (httpResponse.isError) {
            throw SeedgenException(httpResponse.bodyAsText())
        }

        val settings = httpResponse.body<JsonObject>()

        return generateSeed(settings, creator)
    }

    suspend fun generateSeed(settings: JsonObject, creator: User? = null): SeedgenApiGenerateResult {
        assertTransaction()

        val httpResponse: HttpResponse = seedgenHttpClient.post("/generate") {
            url {
                parameters.append("json_spoiler", "true")
                parameters.append("text_spoiler", "true")
            }
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Cbor)
            setBody(settings)
        }

        if (httpResponse.isError) {
            throw SeedgenException(httpResponse.bodyAsText())
        }

        val response = httpResponse.body<SeedgenApiGenerateResponse>()

        val seed = Seed.new {
            this.seedgenConfig = settings
            this.creator = creator
            this.spoiler = json.decodeFromString(response.jsonSpoiler)
            this.spoilerText = response.textSpoiler
        }

        response.worlds.forEachIndexed { index, seedFile ->
            WorldSeed.new {
                this.content = seedFile
                this.worldIndex = index
                this.seed = seed
            }
        }

        seed.refresh(true)

        return SeedgenApiGenerateResult(seed)
    }
}
