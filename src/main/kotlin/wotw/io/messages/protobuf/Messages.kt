@file:OptIn(ExperimentalSerializationApi::class)

package wotw.io.messages.protobuf

import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.math.pow

@Serializable
data class UserInfo(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val avatarId: String?,
    @ProtoNumber(6) val isDeveloper: Boolean,
)

@Serializable
data class WorldInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val color: String,
    @ProtoNumber(4) val memberships: List<WorldMembershipInfo>,
    @ProtoNumber(5) val seedId: Long?,
)

@Serializable
data class WorldMembershipInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val user: UserInfo,
)

@Serializable
data class UniverseInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val color: String,
    @ProtoNumber(4) val worlds: List<WorldInfo>,
)

@Serializable
data class RaceTeamMemberInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val user: UserInfo,
    @ProtoNumber(3) @Required val finishedTime: Float? = null,
)

@Serializable
data class RaceTeamInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val members: List<RaceTeamMemberInfo>,
    @ProtoNumber(4) @Required val finishedTime: Float? = null,
)

@Serializable
data class RaceInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val teams: List<RaceTeamInfo>,
    @ProtoNumber(3) @Required val finishedTime: Float? = null,
)

@Serializable
data class MultiverseMetadataInfoMessage(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val hasBingoBoard: Boolean,
    @ProtoNumber(3) val hasSeed: Boolean,
    @ProtoNumber(4) val members: List<UserInfo>,
    @ProtoNumber(5) val createdAt: Long,
)

@Serializable
data class MultiverseInfoMessage(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val universes: List<UniverseInfo>,
    @ProtoNumber(3) val hasBingoBoard: Boolean = false,
    @ProtoNumber(4) val spectators: List<UserInfo>,
    @ProtoNumber(5) val seedId: Long?,
    @ProtoNumber(6) val gameHandlerType: Int,
    @ProtoNumber(7) @Contextual val gameHandlerClientInfo: ByteArray,
    @ProtoNumber(8) val locked: Boolean = false,
    @ProtoNumber(9) val isLockable: Boolean = true,
    @ProtoNumber(10) @Required val race: RaceInfo? = null,
    @ProtoNumber(11) val seedSpoilerDownloadedBy: List<UserInfo>,
    @ProtoNumber(12) val connectedUserIds: List<String>,
    @ProtoNumber(13) val raceReadyUserIds: List<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MultiverseInfoMessage

        if (id != other.id) return false
        if (universes != other.universes) return false
        if (hasBingoBoard != other.hasBingoBoard) return false
        if (spectators != other.spectators) return false
        if (seedId != other.seedId) return false
        if (gameHandlerType != other.gameHandlerType) return false
        if (!gameHandlerClientInfo.contentEquals(other.gameHandlerClientInfo)) return false
        if (locked != other.locked) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + universes.hashCode()
        result = 31 * result + hasBingoBoard.hashCode()
        result = 31 * result + spectators.hashCode()
        result = 31 * result + (seedId?.hashCode() ?: 0)
        result = 31 * result + gameHandlerType
        result = 31 * result + locked.hashCode()
        result = 31 * result + gameHandlerClientInfo.contentHashCode()
        return result
    }
}

@Serializable
data class UberId(
    @ProtoNumber(1) val group: Int = 0,
    @ProtoNumber(2) val state: Int = 0,
)

@Serializable
data class UberStateBatchUpdateMessage(
    @ProtoNumber(1) val updates: List<UberStateUpdateMessage>,
    @ProtoNumber(2) val resetBeforeApplying: Boolean = false,
) {
    constructor(vararg updates: UberStateUpdateMessage) : this(updates.toList())
}

@Serializable
data class UberStateUpdateMessage(
    @ProtoNumber(1) val uberId: UberId,
    @ProtoNumber(2) val value: Double = 0.0,
)

@Serializable
data class MoodGuid(
    @ProtoNumber(1) val a: Int,
    @ProtoNumber(2) val b: Int,
    @ProtoNumber(3) val c: Int,
    @ProtoNumber(4) val d: Int,
) {
    override fun toString(): String {
        return "[$a, $b, $c, $d]"
    }
}

@Serializable
data class SetSaveGuidRestrictionsMessage(
    @ProtoNumber(1) val playerSaveGuid: MoodGuid?,
    @ProtoNumber(2) val shouldRestrictSaveGuid: Boolean = false,
)

@Serializable
data class SetEnforceSeedDifficultyMessage(
    @ProtoNumber(1) val shouldEnforceSeedDifficulty: Boolean,
)

@Serializable
data class InitGameSyncMessage(
    @ProtoNumber(1) val uberStates: List<UberId> = emptyList(),
    @ProtoNumber(2) val blockStartingNewGame: Boolean = false,
    @ProtoNumber(3) val saveGuidRestrictions: SetSaveGuidRestrictionsMessage,
    @ProtoNumber(4) val preventCheats: Boolean = false,
    @ProtoNumber(5) val enforceSeedDifficulty: SetEnforceSeedDifficultyMessage,
)

@Serializable
data class SetPlayerSaveGuidMessage(
    @ProtoNumber(1) val playerSaveGuid: MoodGuid,
)

@Serializable
data class Vector2(
    @ProtoNumber(1) val x: Float = 0.0f,
    @ProtoNumber(2) val y: Float = 0.0f,
) {
    fun distanceSquaredTo(other: Vector2): Float {
        return (x - other.x).pow(2) + (y - other.y).pow(2)
    }

    fun lerp(other: Vector2, t: Float): Vector2 = Vector2(
        wotw.server.util.lerp(x, other.x, t),
        wotw.server.util.lerp(y, other.y, t),
    )

    override fun toString(): String {
        return "($x, $y)"
    }

    operator fun plus(other: Vector2): Vector2 = Vector2(x + other.x, y + other.y)
    operator fun minus(other: Vector2): Vector2 = Vector2(x - other.x, y - other.y)
}

@Serializable
data class PrintTextMessage(
    @ProtoNumber(1) val text: String,
    @ProtoNumber(2) val position: Vector2,
    @ProtoNumber(3) @Required val id: Int? = null,
    @ProtoNumber(4) @Required val time: Float? = null,
    @ProtoNumber(5) val screenPosition: Int = SCREEN_POSITION_TOP_CENTER,
    @ProtoNumber(6) val useInGameCoordinates: Boolean = false,
    @ProtoNumber(7) val fadeInLength: Float = 0.5f,
    @ProtoNumber(8) val fadeOutLength: Float = 0.5f,
    @ProtoNumber(9) val alignment: Int = ALIGNMENT_CENTER,
    @ProtoNumber(10) val horizontalAnchor: Int = HORIZONTAL_ANCHOR_CENTER,
    @ProtoNumber(11) val verticalAnchor: Int = VERTICAL_ANCHOR_MIDDLE,
    @ProtoNumber(12) val withSound: Boolean = true,
    @ProtoNumber(13) val withBox: Boolean = true,
    @ProtoNumber(14) @Required var queue: String? = null,
    @ProtoNumber(15) val prioritized: Boolean = false,
) {
    companion object {
        const val SCREEN_POSITION_TOP_LEFT = 0
        const val SCREEN_POSITION_TOP_CENTER = 1
        const val SCREEN_POSITION_TOP_RIGHT = 2
        const val SCREEN_POSITION_MIDDLE_LEFT = 3
        const val SCREEN_POSITION_MIDDLE_CENTER = 4
        const val SCREEN_POSITION_MIDDLE_RIGHT = 5
        const val SCREEN_POSITION_BOTTOM_LEFT = 6
        const val SCREEN_POSITION_BOTTOM_CENTER = 7
        const val SCREEN_POSITION_BOTTOM_RIGHT = 8

        const val HORIZONTAL_ANCHOR_LEFT = 0
        const val HORIZONTAL_ANCHOR_CENTER = 1
        const val HORIZONTAL_ANCHOR_RIGHT = 2

        const val VERTICAL_ANCHOR_TOP = 0
        const val VERTICAL_ANCHOR_MIDDLE = 1
        const val VERTICAL_ANCHOR_BOTTOM = 2

        const val ALIGNMENT_LEFT = 0
        const val ALIGNMENT_CENTER = 1
        const val ALIGNMENT_RIGHT = 2
        const val ALIGNMENT_JUSTIFY = 3
    }
}

@Serializable
data class PrintPickupMessage(
    @ProtoNumber(1) val time: Float,
    @ProtoNumber(2) val text: String,
    @ProtoNumber(3) @Required val worldOrigin: Vector2? = null,
)

@Serializable
data class SyncBoardMessage(
    @ProtoNumber(1) val board: BingoBoardMessage,
    @ProtoNumber(2) val replace: Boolean = false
)

@Serializable
data class BingoUniverseInfo(
    @ProtoNumber(1) val universeId: Long,
    @ProtoNumber(3) val score: String,
    @ProtoNumber(4) val rank: Int = 0,
    @ProtoNumber(5) val squares: Int = 0,
    @ProtoNumber(6) val lines: Int = 0,
)

@Serializable
data class SyncBingoUniversesMessage(
    @ProtoNumber(1) val bingoUniverses: List<BingoUniverseInfo>,
)

@Serializable
data class RequestUpdatesMessage(
    @ProtoNumber(1) val playerId: String,
)

@Serializable
data class AuthenticateMessage(
    @ProtoNumber(1) val jwt: String,
    @ProtoNumber(2) val clientVersion: String,
)

@Serializable
data class UpdatePlayerPositionMessage(
    @ProtoNumber(1) val playerId: String,
    @ProtoNumber(2) val x: Float = 0.0f,
    @ProtoNumber(3) val y: Float = 0.0f,
    @ProtoNumber(4) val ghostFrameData: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UpdatePlayerPositionMessage

        if (playerId != other.playerId) return false
        if (x != other.x) return false
        if (y != other.y) return false
        if (!ghostFrameData.contentEquals(other.ghostFrameData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = playerId.hashCode()
        result = 31 * result + x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + ghostFrameData.contentHashCode()
        return result
    }
}

@Serializable
data class UpdatePlayerWorldPositionMessage(
    @ProtoNumber(1) val playerId: String,
    @ProtoNumber(2) val x: Float = 0.0f,
    @ProtoNumber(3) val y: Float = 0.0f,
    @ProtoNumber(4) val ghostFrameData: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UpdatePlayerWorldPositionMessage

        if (playerId != other.playerId) return false
        if (x != other.x) return false
        if (y != other.y) return false
        if (!ghostFrameData.contentEquals(other.ghostFrameData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = playerId.hashCode()
        result = 31 * result + x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + ghostFrameData.contentHashCode()
        return result
    }
}

@Serializable
data class UpdatePlayerMapPositionMessage(
    @ProtoNumber(1) val playerId: String,
    @ProtoNumber(2) val x: Float = 0.0f,
    @ProtoNumber(3) val y: Float = 0.0f,
)

@Serializable
data class SetVisibilityMessage(
    @ProtoNumber(1) val hiddenInWorld: List<String>,
    @ProtoNumber(2) val hiddenOnMap: List<String>,
)

@Serializable
data class PlayerPositionMessage(
    @ProtoNumber(1) val x: Float = 0.0f,
    @ProtoNumber(2) val y: Float = 0.0f,
    @ProtoNumber(3) val ghostFrameData: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PlayerPositionMessage

        if (x != other.x) return false
        if (y != other.y) return false
        if (!ghostFrameData.contentEquals(other.ghostFrameData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + ghostFrameData.contentHashCode()
        return result
    }
}

@Serializable
data class AuthenticatedMessage(
    @ProtoNumber(1) val user: UserInfo,
    @ProtoNumber(2) val udpId: Int = 0,
    @ProtoNumber(3) val udpKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AuthenticatedMessage

        if (user != other.user) return false
        if (udpId != other.udpId) return false
        if (!udpKey.contentEquals(other.udpKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = user.hashCode()
        result = 31 * result + udpId
        result = 31 * result + udpKey.contentHashCode()
        return result
    }
}

@Serializable
data class TrackerUpdate(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val value: Int = 0,
)

@Serializable
class ResetTracker()

@Serializable
data class TrackerFlagsUpdate(
    @ProtoNumber(1) val flags: List<String>,
)

@Serializable
class RequestFullUpdate()

@Serializable
data class SetTrackerEndpointId(
    @ProtoNumber(1) val endpointId: String,
)

@Serializable
data class TrackerTimerStateUpdate(
    @ProtoNumber(1) val inGameTime: Float = 0.0f,
    @ProtoNumber(2) val asyncLoadingTime: Float = 0.0f,
    @ProtoNumber(3) val timerShouldRun: Boolean = false,
)

@Serializable
data class RequestSeedMessage(
    @ProtoNumber(1) val init: Boolean, // This is something for the client, ask wolf. We just pipe it through
)

@Serializable
data class SetSeedMessage(
    @ProtoNumber(1) val seedContent: String,
)

@Serializable
class PlayerUseCatchingAbilityMessage()

@Serializable
class PlayerTeleportMessage()

@Serializable
data class PlayerUsedCatchingAbilityMessage(
    @ProtoNumber(1) val playerId: String,
)

@Serializable
data class PlayerCaughtMessage(
    @ProtoNumber(1) val playerId: String,
)

@Serializable
data class SpendResourceTarget(
    @ProtoNumber(1) val uberId: UberId,
    @ProtoNumber(2) val value: Double,
    @ProtoNumber(3) val updateIf: Int = UPDATE_IF_LARGER,
) {
    companion object {
        const val UPDATE_IF_LARGER = 0
        const val UPDATE_IF_SMALLER = 1
        const val UPDATE_IF_DIFFERENT = 2
    }
}

@Serializable
data class ResourceRequestMessage(
    @ProtoNumber(1) val resourceUberId: UberId,
    @ProtoNumber(2) val relative: Boolean,
    @ProtoNumber(3) val amount: Int,
    @ProtoNumber(4) @Required val target: SpendResourceTarget? = null,
)

@Serializable
data class ReportInGameTimeMessage(
    @ProtoNumber(1) val inGameTime: Float = 0f,
    @ProtoNumber(2) val isFinished: Boolean = false,
)

@Serializable
data class OverrideInGameTimeMessage(
    @ProtoNumber(1) val inGameTime: Float = 0f,
)

@Serializable
data class ShowUINotificationMessage(
    @ProtoNumber(1) val text: String,
    @ProtoNumber(2) val color: String,
)

@Serializable
data class SetBlockStartingNewGameMessage(
    @ProtoNumber(1) val blockStartingNewGame: Boolean = false,
)

@Serializable
data class ReportPlayerRaceReadyMessage(
    @ProtoNumber(1) val raceReady: Boolean = false,
)

@Serializable
data class LeagueSeasonMembershipInfo(
    @ProtoNumber(1) val user: UserInfo,
    @ProtoNumber(2) val points: Int,
    @ProtoNumber(3) @Required val rank: Int? = null,
    @ProtoNumber(4) @Required val lastRankDelta: Int? = null,
    @ProtoNumber(5) val joinedAt: Long,
)

@Serializable
data class LeagueGameInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val multiverseId: Long,
    @ProtoNumber(3) val seasonId: Long,
    @ProtoNumber(4) val submissionCount: Long,
    @ProtoNumber(5) val gameNumber: Int,
    @ProtoNumber(6) val isCurrent: Boolean,
    @ProtoNumber(7) @Required val userMetadata: LeagueGameUserMetadata? = null,
)

@Serializable
data class LeagueGameUserMetadata(
    @ProtoNumber(1) val canSubmit: Boolean,
    @ProtoNumber(2) @Required val ownSubmission: LeagueGameSubmissionInfo? = null,
)

@Serializable
data class LeagueSeasonInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val memberships: List<LeagueSeasonMembershipInfo>,
    @ProtoNumber(4) val gameCount: Int,
    @ProtoNumber(5) val games: List<LeagueGameInfo>,
    @ProtoNumber(6) val canJoin: Boolean,
    @ProtoNumber(7) @Required val currentGameId: Long? = null,
    @ProtoNumber(8) val shortDescription: String,
    @ProtoNumber(9) val longDescriptionMarkdown: String,
    @ProtoNumber(10) val rulesMarkdown: String,
    @ProtoNumber(11) val nextContinuationAt: Long,
    @ProtoNumber(12) @Required val backgroundImageUrl: String? = null,
    @ProtoNumber(13) val discardWorstGamesCount: Int,
)

@Serializable
data class LeagueGameSubmissionInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val membership: LeagueSeasonMembershipInfo,
    @ProtoNumber(3) val submittedAt: Long,
    @ProtoNumber(4) @Required val rankingData: LeagueGameSubmissionRankingData? = null
)

@Serializable
data class LeagueGameSubmissionRankingData(
    @ProtoNumber(1) @Required val time: Float? = null,
    @ProtoNumber(2) val points: Int,
    @ProtoNumber(3) @Required val rank: Int? = null,
    @ProtoNumber(5) @Required val videoUrl: String? = null,
    @ProtoNumber(6) val rankingMultiplier: Float,
)
