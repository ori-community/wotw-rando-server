package wotw.io.messages

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max

@Serializable
data class BingoGenProperties(
    val seed: String? = null,
    val discovery: Int? = null,
    val lockout: Boolean? = false,
    val manualGoalCompletion: Boolean? = false
)

@Serializable
@Deprecated("Kept around for historical preservation of monuments")
data class VerseProperties(val isMulti: Boolean = false, val isCoop: Boolean = false)

@Serializable
data class HeaderParameterDef(val name: String, val default: String, val type: String, val description: List<String>)

@Serializable
data class HeaderFileEntry(val headerName: String, val hidden: Boolean, val name: String?, val description: List<String>?, val params: List<HeaderParameterDef>)


enum class SeedgenDifficulty(val level: Int) {
    MOKI(1),
    GORLEK(2),
    UNSAFE(3),
}

fun Collection<Preset>.implies(preset: Preset): Boolean {
    return preset in this ||
            (preset.webConn != true || any { it.webConn == true })
            && (preset.hard != true || any { it.hard == true })
            && !any { it.difficultyEnum.level < preset.difficultyEnum.level }
            && (preset.spoilers != false || any { it.spoilers == false })
            && (preset.worlds == null || any { it.worlds != null && it.worlds >= preset.worlds })
            && (preset.goalmodes.all { g -> any { g in it.goalmodes } })
            && (preset.headerList.all { h -> any { h in it.headerList } })
            && (preset.glitches.all { h -> any { h in it.glitches } })
            && (preset.players.all { p -> any { p in it.players } })
            && (preset.presets.all { p -> any { it.name == p || p in it.presets } })
}

@Serializable
data class PresetFile(
    val worlds: Int,
    val presets: Set<String>,
    val players: List<String>,
    val glitches: Set<String>,
    val difficulty: String,
    val goalmodes: Set<String>,
    val headerList: Set<String>,
    val spoilers: Boolean,
    val webConn: Boolean,
    val hard: Boolean,
    val spawnLoc: JsonElement,
    val headerArgs: List<String>,
) {
    private val spawnLocString: String = if (spawnLoc is JsonPrimitive) {
        spawnLoc.content.lowercase() // Random → random
    } else {
        spawnLoc.jsonObject["Set"]!!.jsonPrimitive.content
    }

    private val difficultyEnum: SeedgenDifficulty
        get() = SeedgenDifficulty.valueOf(difficulty.uppercase())

    fun fullResolve(presets: Map<String, PresetFile>): PresetFile {
        val merged = this.presets.mapNotNull {
            presets[it]?.fullResolve(presets)
        }.reduceOrNull { p1, p2 -> p1.merge(p2) }
        return if (merged == null) this else merge(merged)
    }

    private fun merge(other: PresetFile): PresetFile {
        return PresetFile(
            max(1, (players + other.players).distinct().size),
            emptySet(),
            (players + other.players).distinct(),
            glitches + other.glitches,
            if (difficultyEnum.level > other.difficultyEnum.level) difficulty else other.difficulty,
            goalmodes + other.goalmodes,
            headerList + other.headerList,
            spoilers or other.spoilers,
            webConn or other.webConn,
            hard or other.hard,
            spawnLoc,
            headerArgs + other.headerArgs
        )
    }

    fun toPreset(name: String): Preset {
        val argMap = hashMapOf<String, String>()

        headerArgs.forEach {
            val parts = it.split("=")
            val value = parts.getOrNull(1) ?: "true"
            argMap[parts[0]] = value
        }

        return Preset(
            worlds,
            presets,
            players,
            glitches.map { Glitch.valueOf(it.uppercase()) }.toSet(),
            difficulty,
            goalmodes.map { GoalMode.valueOf(it.uppercase()) }.toSet(),
            headerList,
            spoilers,
            webConn,
            hard,
            name = name,
            wrapper = false,
            spawnLoc = spawnLocString,
            headerArgs = argMap,
        )
    }

}

@Serializable
data class Preset(
    val worlds: Int? = null,
    val presets: Set<String> = emptySet(),
    val players: List<String> = emptyList(),
    val glitches: Set<Glitch> = emptySet(),
    val difficulty: String = "moki",
    val goalmodes: Set<GoalMode> = emptySet(),
    val headerList: Set<String> = emptySet(),
    val spoilers: Boolean? = null,
    val webConn: Boolean? = null,
    val hard: Boolean? = null,
    val description: List<String> = emptyList(),
    val name: String = "",
    val wrapper: Boolean = false,
    val spawnLoc: String = "MarshSpawn.Main",
    val headerArgs: Map<String, String> = emptyMap(),
) {
    val difficultyEnum: SeedgenDifficulty
        get() = SeedgenDifficulty.valueOf(difficulty.uppercase())
}

infix fun Boolean?.or(other: Boolean?): Boolean? =
    if (this == null) other else if (other == null) this else this || other

enum class Glitch {
    SWORDSENTRYJUMP,
    HAMMERSENTRYJUMP,
    SHURIKENBREAK,
    SENTRYBREAK,
    SPEARBREAK,
    HAMMERBREAK,
    SENTRYBURN,
    REMOVEKILLPLANE,
}

enum class GoalMode(private val displayName: String, private val description: String) {
    TREES("All Trees", "Requires all Ancestral Trees to be activated before finishing the game"),
    WISPS("All Wisps", "Requires all Wisps to be collected before finishing the game."),
    QUESTS("All Quests", "Requires all Quests to be completed before finishing the game."),
    RELICS(
        "World Tour",
        "Spreads special relic pickups throughout certain zones. All relics must be collected before finishing the game"
    ),

}

@Serializable
data class SeedGenConfig(
    val flags: List<String> = emptyList(),
    val headers: List<String> = emptyList(),
    val presets: List<String> = emptyList(),
    val glitches: List<String> = emptyList(),
    val difficulty: String = "moki",
    val goals: List<String> = emptyList(),
    val multiNames: List<String>? = null,
    val seed: String? = null,
    val spawn: String? = null,
    val customHeaders: List<String>? = null,
    val headerArgs: Map<String, String>? = null,
)

@Serializable
data class SeedGenResponse(
    val seedId: Long,
    val worldList: List<String> = emptyList(),
    val multiverseId: Long? = null,
)

@Serializable
data class TokenRequest(val scopes: Set<String>, val duration: Long? = null)