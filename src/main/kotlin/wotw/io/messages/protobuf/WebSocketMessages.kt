package wotw.io.messages.protobuf

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class UserInfo(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val avatarId: String?,
)


@Serializable
data class WorldInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val members: List<UserInfo>
)

@Serializable
data class UniverseInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val members: List<WorldInfo>
)

@Serializable
data class MultiverseInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val teams: List<UniverseInfo>,
    @ProtoNumber(3) val hasBingoBoard: Boolean,
    @ProtoNumber(4) val spectators: List<UserInfo>,
)

@Serializable
data class UberId(
    @ProtoNumber(1) val group: Int,
    @ProtoNumber(2) val state: Int
)

@Serializable
data class UberStateBatchUpdateMessage(
    @ProtoNumber(1) val updates: List<UberStateUpdateMessage>
) {
    constructor(vararg updates: UberStateUpdateMessage) : this(updates.toList())
}

@Serializable
data class UberStateUpdateMessage(
    @ProtoNumber(1) val uberId: UberId,
    @ProtoNumber(2) val value: Double
)

@Serializable
data class InitGameSyncMessage(
    @ProtoNumber(1) val uberStates: List<UberId> = emptyList()
)

@Serializable
data class PrintTextMessage(
    @ProtoNumber(1) val frames: Int,
    @ProtoNumber(2) val ypos: Float,
    @ProtoNumber(3) val text: String
)

@Serializable
data class SyncBoardMessage(
    @ProtoNumber(1) val board: BingoBoard,
    @ProtoNumber(2) val replace: Boolean = false
)

@Serializable
data class BingoTeamInfo(
    @ProtoNumber(1) val teamId: Long,
    @ProtoNumber(3) val score: String,
    @ProtoNumber(4) val rank: Int = 0,
    @ProtoNumber(5) val squares: Int = 0,
    @ProtoNumber(6) val lines: Int = 0,
)

@Serializable
data class SyncBingoTeamsMessage(
    @ProtoNumber(1) val teams: List<BingoTeamInfo>
)

@Serializable
data class RequestUpdatesMessage(
    @ProtoNumber(1) val playerId: String
)

@Serializable
data class Authenticate(
    @ProtoNumber(1) val jwt: String,
)