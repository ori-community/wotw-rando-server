package wotw.server.database.model

import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.json.jsonb
import wotw.io.messages.UniversePreset
import wotw.io.messages.json
import wotw.io.messages.protobuf.GameDifficultySettingsOverrides

object Seeds : LongIdTable("seeds") {
    // TODO: Make seedgen return the used UniverseSettings and save them here instead of the preset
    val seedgenConfig = jsonb<UniversePreset>("seedgen_config", json)
    val spoiler = jsonb<JsonElement>("spoiler", json)
    val spoilerText = text("spoiler_text")
    val creator = optReference("creator_id", Users)
    val created = datetime("created_at").defaultExpression(CurrentDateTime)
    val allowDownload = bool("allow_download").default(true)
}

class Seed(id: EntityID<Long>): LongEntity(id){
    companion object : LongEntityClass<Seed>(Seeds)

    var seedgenConfig by Seeds.seedgenConfig
    var spoiler by Seeds.spoiler
    var spoilerText by Seeds.spoilerText
    var creator by User optionalReferencedOn Seeds.creator
    var allowDownload by Seeds.allowDownload
    val worldSeeds by WorldSeed referrersOn WorldSeeds.seed
    var spoilerDownloads by User via SeedSpoilerDownloads
    val multiverses by Multiverse optionalReferrersOn Multiverses.seedId
}

object WorldSeeds : LongIdTable("world_seeds") {
    val seed = reference("seed_id", Seeds)
    val worldIndex = integer("world_index")
    val content = text("content")
}

class WorldSeed(id: EntityID<Long>): LongEntity(id){
    companion object : LongEntityClass<WorldSeed>(WorldSeeds)

    var seed by Seed referencedOn WorldSeeds.seed
    var worldIndex by WorldSeeds.worldIndex
    var content by WorldSeeds.content

    fun inferGameDifficultySettingsOverrides(): GameDifficultySettingsOverrides {
        // TODO: Temporary.
        //       Replace this with something that doesn't rely on the seedgen config at some point

        if (seed.seedgenConfig.worldSettings[worldIndex].hard) {
            return GameDifficultySettingsOverrides(
                GameDifficultySettingsOverrides.Setting.Deny,
                GameDifficultySettingsOverrides.Setting.Deny,
                GameDifficultySettingsOverrides.Setting.Allow,
            )
        }

        return GameDifficultySettingsOverrides(
            GameDifficultySettingsOverrides.Setting.Deny,
            GameDifficultySettingsOverrides.Setting.Allow,
            GameDifficultySettingsOverrides.Setting.Deny,
        )
    }
}
