package wotw.server.game

data class WorldCreatedEvent(
    val worldId: Long,
)
data class WorldDeletedEvent(
    val worldId: Long,
)
data class PlayerJoinedEvent(
    val playerId: String,
    val worldId: Long,
    val universeId: Long,
)
data class PlayerLeftEvent(
    val playerId: String,
    val worldId: Long,
    val universeId: Long,
)

data class CustomEvent(
    val event: String,
    val payload: Any? = null,
)