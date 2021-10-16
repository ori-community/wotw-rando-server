package wotw.io.messages.protobuf

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

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