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
data class PresetInfo(
    val name: String? = null,
    val description: String? = null,
    val group: String? = null,
)

@Serializable
data class WorldPresetFile(
    val info: PresetInfo? = null,
    val includes: Set<String> = emptySet(),
    val spawn: String? = null,
    val difficulty: String? = null,
    val tricks: Set<String> = emptySet(),
    val hard: Boolean = false,
    val presetGroup: String? = null,

    @Deprecated("Will be deprecated soon when goals are headerified")
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
            info,
            includes + other.includes,
            other.spawn ?: spawn,
            if (difficultyLevel(other.difficulty) > difficultyLevel(difficulty)) other.difficulty else difficulty,
            tricks + other.tricks,
            hard || other.hard,
            presetGroup,
            goals + other.goals,
            headers + other.headers,
            mergedHeaderConfig,
            inlineHeaders + other.inlineHeaders,
        )
    }

    fun toWorldPreset(): WorldPreset {
        return WorldPreset(
            info,
            includes,
            spawn,
            difficulty,
            tricks,
            hard,
            presetGroup,
            goals,
            headers,
            headerConfig,
            inlineHeaders,
        )
    }

}

@Serializable
data class WorldPreset(
    val info: PresetInfo? = null,
    val includes: Set<String> = emptySet(),
    val spawn: String? = null,
    val difficulty: String? = null,
    val tricks: Set<String> = emptySet(),
    val hard: Boolean = false,
    val presetGroup: String? = null,

    @Deprecated("Will be deprecated soon when goals are headerified")
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
    val config: GamePreset,
)

@Serializable
data class GamePreset(
    val info: PresetInfo? = null,
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