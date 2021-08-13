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
data class TeamInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val members: List<UserInfo>
)

@Serializable
data class GameInfo(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val teams: List<TeamInfo>,
)

@Serializable
data class UberId(
    @ProtoNumber(1) val group: Int,
    @ProtoNumber(2) val state: Int
)

@Serializable
data class UberStateBatchUpdateMessage(
    @ProtoNumber(1) val updates: List<UberStateUpdateMessage>
){
    constructor(vararg updates: UberStateUpdateMessage): this(updates.toList())
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