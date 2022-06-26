package wotw.io.messages

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import wotw.io.messages.protobuf.UserInfo

@Serializable
@Deprecated("Kept around for historical preservation of monuments")
data class VerseProperties(val isMulti: Boolean = false, val isCoop: Boolean = false)

@Serializable
data class HeaderParameterDef(val name: String, val default: String, val type: String, val description: List<String>)

@Serializable
data class HeaderFileEntry(
    val headerName: String,
    val hidden: Boolean,
    val name: String?,
    val description: List<String>?,
    val params: List<HeaderParameterDef>
)


@Serializable
data class HeaderConfig(
    @JsonNames("header_name") val headerName: String,
    @JsonNames("config_name") val configName: String,
    @JsonNames("config_value") var configValue: String,
)

@Serializable
data class InlineHeader(
    val name: String?,
    val content: String,
)

fun difficultyLevel(difficulty: String?): Int {
    return when (difficulty?.lowercase()) {
        "moki" -> 1
        "gorlek" -> 2
        "kii" -> 3
        "unsafe" -> 4
        else -> 0
    }
}

@Serializable
data class WorldPresetFile(
    val includes: Set<String> = emptySet(),
    val worldName: String? = null,
    val spawn: String? = null,
    val difficulty: String? = null,
    val tricks: Set<String> = emptySet(),
    val goals: Set<String> = emptySet(),
    val headers: Set<String> = emptySet(),
    val headerConfig: List<HeaderConfig> = emptyList(),
    val inlineHeaders: List<InlineHeader> = emptyList(),
) {
    fun resolveAndMergeIncludes(availablePresets: Map<String, WorldPresetFile>): WorldPresetFile {
        val resolvedIncludes = this.includes
            .mapNotNull { includedPresetId ->
                availablePresets[includedPresetId]?.resolveAndMergeIncludes(availablePresets)
            }
            .reduceOrNull { p1, p2 -> p1.mergeWith(p2) } ?: return this

        return mergeWith(resolvedIncludes)
    }

    private fun mergeWith(other: WorldPresetFile): WorldPresetFile {
        val mergedHeaderConfig = headerConfig.toMutableList()

        // Override values for existing header configs
        other.headerConfig.forEach { otherHeaderConfig ->
            mergedHeaderConfig
                .find { h -> h.headerName == otherHeaderConfig.headerName && h.configName == otherHeaderConfig.configName }
                ?.let { headerConfig ->
                    headerConfig.configValue = otherHeaderConfig.configValue
                } ?: mergedHeaderConfig.add(otherHeaderConfig)
        }

        return WorldPresetFile(
            includes + other.includes,
            other.worldName ?: worldName,
            other.spawn ?: spawn,
            if (difficultyLevel(other.difficulty) > difficultyLevel(difficulty)) other.difficulty else difficulty,
            tricks + other.tricks,
            goals + other.goals,
            headers + other.headers,
            mergedHeaderConfig,
            inlineHeaders + other.inlineHeaders,
        )
    }

    fun toWorldPreset(): WorldPreset {
        return WorldPreset(
            includes,
            worldName,
            spawn,
            difficulty,
            tricks,
            goals,
            headers,
            headerConfig,
            inlineHeaders,
        )
    }

}

@Serializable
data class WorldPreset(
    val includes: Set<String> = emptySet(),
    val worldName: String? = null,
    val spawn: String? = null,
    val difficulty: String? = null,
    val tricks: Set<String> = emptySet(),
    val goals: Set<String> = emptySet(),
    val headers: Set<String> = emptySet(),
    val headerConfig: List<HeaderConfig> = emptyList(),
    val inlineHeaders: List<InlineHeader> = emptyList(),
)

@Serializable
data class SeedInfo(
    val id: Long,
    val worldSeedIds: List<Long>,
    val creator: UserInfo?,
    val config: Preset,
)

@Serializable
data class Preset(
    val worldSettings: List<WorldPreset>,
    val disableLogicFilter: Boolean = false,
    val seed: String? = null,
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
    val lockout: Boolean? = false,
    val manualGoalCompletion: Boolean? = false,
    val size: Int = 5,
)

@Serializable
data class HideAndSeekConfig(
    val secondsUntilCatchPhase: Int = 15 * 60,
)

@Serializable
data class MultiverseCreationConfig(
    val seedId: Long? = null,
    val bingoConfig: BingoCreationConfig? = null,
    val hideAndSeekConfig: HideAndSeekConfig? = null,
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