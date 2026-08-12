@file:OptIn(ExperimentalSerializationApi::class)

package wotw.io.messages

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import wotw.io.messages.protobuf.LeagueSeasonInfo
import wotw.io.messages.protobuf.UserInfo
import wotw.server.seedgen.SeedgenApiLogRecord

@Serializable
data class SeedInfo(
    val id: Long,
    val worldSeedIds: List<Long>,
    val creator: UserInfo?,
)

@Serializable
data class BingoCreationConfig(
    val discovery: Int? = null,
    val revealFirstNCompletedGoals: Int = 0,
    val lockout: Boolean? = false,
    val size: Int = 5,
)

@Serializable
data class MultiverseCreationConfig(
    val seedId: Long? = null,
    val raceMode: Boolean = false,
    val bingoConfig: BingoCreationConfig? = null,
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
    val universePreset: String,
    val backgroundImageUrl: String?,
)

@Serializable
data class SetSubmissionVideoUrlRequest(
    val videoUrl: String?,
)

@Serializable
data class BingothonTokenRequest(val multiverseId: Long)


@Serializable
data class GeneratedSeedResponse(
    val seedId: Long,
    val worldSeedIds: List<Long>,
    val logs: List<SeedgenApiLogRecord>,
)


@Serializable
data class JoinLeagueSeasonResponse(
    val seasonInfo: LeagueSeasonInfo,
    val promptToJoinLeagueDiscord: Boolean,
)
