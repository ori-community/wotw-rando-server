package wotw.io.messages.admin

import kotlinx.serialization.Serializable
import wotw.io.messages.protobuf.UserInfo


@Serializable
data class RemoteTrackerEndpointDescriptor(
    val endpointId: String,
    val broadcaster: UserInfo?,
    val listeners: List<String>,
    val expires: Long?,
)