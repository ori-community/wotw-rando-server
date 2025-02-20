@file:OptIn(ExperimentalSerializationApi::class)

package wotw.io.messages

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import wotw.io.messages.protobuf.UserInfo

@Serializable
@Deprecated("Kept around for historical preservation of monuments")
data class VerseProperties(val isMulti: Boolean = false, val isCoop: Boolean = false)


@Serializable
data class SeedgenLibrary(
    val name: String,
    val description: String,
    val version: String,
    val worldPresets: Map<String, WorldPreset>,
    val headers: Map<String, Header>,
) {
    val apiVersion: String = "1.0.0"
}


@Serializable
data class Header(
    val name: String?,
    val content: String,
)

@Serializable
data class HeaderConfig(
    @JsonNames("header_name") val headerName: String,
    @JsonNames("config_name") val configName: String,
    @JsonNames("config_value") var configValue: String,
)

@Serializable
data class PresetInfo(
    val name: String? = null,
    val description: String? = null,
    val group: String? = null,
)

@Serializable
data class WorldPreset(
    val info: PresetInfo? = null,
    val includes: Set<String> = emptySet(),
    val spawn: String? = null,
    val difficulty: String? = null,
    val tricks: Set<String> = emptySet(),
    val hard: Boolean = false,
    val randomizeDoors: Boolean = false,

    @Deprecated("Will be deprecated soon when goals are headerified")
    val goals: Set<String> = emptySet(),

    val headers: Set<String> = emptySet(),
    val headerConfig: List<HeaderConfig> = emptyList(),
    val inlineHeaders: List<Header> = emptyList(),
)

@Serializable
data class WorldSettings(
    val spawn: String,
    val difficulty: String,
    val tricks: Set<String> = emptySet(),
    val hard: Boolean = false,
    val randomizeDoors: Boolean = false,
    val headers: Set<String> = emptySet(),
    val headerConfig: List<HeaderConfig> = emptyList(),
    val inlineHeaders: List<Header> = emptyList(),
)

@Serializable
data class SeedInfo(
    val id: Long,
    val worldSeedIds: List<Long>,
    val creator: UserInfo?,
    val config: UniversePreset, // TODO: Save used settings, not preset
)

@Serializable
data class UniversePreset(
    val info: PresetInfo? = null,
    val worldSettings: List<WorldPreset>,
    val disableLogicFilter: Boolean = false,
    val seed: String? = null,
    @Deprecated("The online flag is deprecated since 4.0") val online: Boolean = false,
)

@Serializable
data class UniverseSettings(
    val worldSettings: List<WorldSettings>,
    val disableLogicFilter: Boolean = false,
    val seed: String,
    val online: Boolean = false,
)

@Serializable
data class SeedgenCliOutput(
    val seedFiles: List<String>,
    val spoiler: JsonObject, // Don't need structured data here since we just put that as a json file somewhere
    val spoilerText: String,
)

@Serializable
data class BingoCreationConfig(
    val discovery: Int? = null,
    val revealFirstNCompletedGoals: Int = 0,
    val lockout: Boolean? = false,
    val manualGoalCompletion: Boolean? = false,
    val size: Int = 5,
)

@Serializable
data class MultiverseCreationConfig(
    val seedId: Long? = null,
    val bingoConfig: BingoCreationConfig? = null,
)

@Serializable
data class SeedGenResult(
    val seedId: Long,
    val worldSeedIds: List<Long> = emptyList(),
)

@Serializable
data class SeedGenResponse(
    val result: SeedGenResult,
    val warnings: String? = null,
)

@Serializable
data class TokenRequest(val scopes: Set<String>, val duration: Long? = null)

@Serializable
data class ImpersonateRequest(val userId: String)

@Serializable
data class ClaimBingoCardRequest(val x: Int, val y: Int)

@Serializable
data class CreateLeagueSeasonRequest(
    val name: String,
    val cron: String,
    val gameCount: Int,
    val shortDescription: String,
    val longDescriptionMarkdown: String,
    val rulesMarkdown: String,
    val backgroundImageUrl: String?,
)

@Serializable
data class SetSubmissionVideoUrlRequest(
    val videoUrl: String?,
)

@Serializable
data class BingothonTokenRequest(val multiverseId: Long)

@Serializable
data class CreateLeagueSubmissionRequest(
    val saveFileBase64: String,
)
