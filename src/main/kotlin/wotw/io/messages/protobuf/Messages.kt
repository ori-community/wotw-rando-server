package wotw.io.messages.protobuf

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.math.pow
import kotlin.math.sqrt

@Serializable
data class UserInfo(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val avatarId: String?,
    @ProtoNumber(4) val connectedMultiverseId: Long?,
    @ProtoNumber(5) val currentMultiverseId: Long?,
    @ProtoNumber(6) val isDeveloper: Boolean,
)

@Serializable
data class WorldInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val color: String,
    @ProtoNumber(4) val members: List<UserInfo>,
    @ProtoNumber(5) val seedId: Long?,
)

@Serializable
data class UniverseInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val color: String,
    @ProtoNumber(4) val worlds: List<WorldInfo>,
)

@Serializable
data class MultiverseInfoMessage(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val universes: List<UniverseInfo>,
    @ProtoNumber(3) val hasBingoBoard: Boolean,
    @ProtoNumber(4) val spectators: List<UserInfo>,
    @ProtoNumber(5) val seedGroupId: Long?,
    @ProtoNumber(6) val gameHandlerType: Int,
    @ProtoNumber(7) val gameHandlerClientInfo: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MultiverseInfoMessage

        if (id != other.id) return false
        if (universes != other.universes) return false
        if (hasBingoBoard != other.hasBingoBoard) return false
        if (spectators != other.spectators) return false
        if (seedGroupId != other.seedGroupId) return false
        if (gameHandlerType != other.gameHandlerType) return false
        if (!gameHandlerClientInfo.contentEquals(other.gameHandlerClientInfo)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + universes.hashCode()
        result = 31 * result + hasBingoBoard.hashCode()
        result = 31 * result + spectators.hashCode()
        result = 31 * result + (seedGroupId?.hashCode() ?: 0)
        result = 31 * result + gameHandlerType
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
data class InitGameSyncMessage(
    @ProtoNumber(1) val uberStates: List<UberId> = emptyList()
)

@Serializable
data class Vector2(
    @ProtoNumber(1) val x: Float,
    @ProtoNumber(2) val y: Float,
) {
    fun distanceSquaredTo(other: Vector2): Float {
        return (x - other.x).pow(2) + (y - other.y).pow(2)
    }

    operator fun plus(other: Vector2): Vector2 = Vector2(x + other.x, y + other.y)

    override fun toString(): String {
        return "($x, $y)"
    }
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
    @ProtoNumber(1) val board: BingoBoard,
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
)

@Serializable
data class UpdatePlayerPositionMessage(
    @ProtoNumber(1) val playerId: String,
    @ProtoNumber(2) val x: Float,
    @ProtoNumber(3) val y: Float,
)

@Serializable
data class UpdatePlayerWorldPositionMessage(
    @ProtoNumber(1) val playerId: String,
    @ProtoNumber(2) val x: Float,
    @ProtoNumber(3) val y: Float,
)

@Serializable
data class UpdatePlayerMapPositionMessage(
    @ProtoNumber(1) val playerId: String,
    @ProtoNumber(2) val x: Float,
    @ProtoNumber(3) val y: Float,
)

@Serializable
data class SetVisibilityMessage(
    @ProtoNumber(1) val hiddenInWorld: List<String>,
    @ProtoNumber(2) val hiddenOnMap: List<String>,
)

@Serializable
data class PlayerPositionMessage(
    @ProtoNumber(1) val x: Float,
    @ProtoNumber(2) val y: Float,
)

@Serializable
data class AuthenticatedMessage(
    @ProtoNumber(1) val user: UserInfo,
    @ProtoNumber(2) val udpId: Int,
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
data class RequestSeedMessage(
    @ProtoNumber(1) val init: Boolean, // This is something for the client, ask wolf. We just pipe it through
)

@Serializable
data class SetSeedMessage(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val seedContent: String,
    @ProtoNumber(3) val init: Boolean = false, // This is something for the client, ask wolf. We just pipe it through
)

@Serializable
class PlayerUseCatchingAbilityMessage()

@Serializable
data class PlayerUsedCatchingAbilityMessage(
    @ProtoNumber(1) val playerId: String,
)

@Serializable
data class PlayerCaughtMessage(
    @ProtoNumber(1) val playerId: String,
)