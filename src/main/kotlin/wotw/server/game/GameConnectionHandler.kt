package wotw.server.game

import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.InitGameSyncMessage
import wotw.io.messages.protobuf.SetGameDifficultySettingsOverridesMessage
import wotw.io.messages.protobuf.SetSaveGuidRestrictionsMessage
import wotw.io.messages.protobuf.ShowUINotificationMessage
import wotw.server.database.model.User
import wotw.server.database.model.WorldMembership
import wotw.server.database.model.WorldMemberships
import wotw.server.io.ClientConnection
import wotw.server.main.WotwBackendServer
import wotw.server.util.makeServerTextMessage

data class GameConnectionHandlerSyncResult(
    val worldId: Long,
    val universeId: Long,
    val multiverseId: Long,
    val worldMembershipId: Long,
)

class GameConnectionHandler(
    private var playerId: String,
    private val multiverseId: Long,
    private val connection: ClientConnection,
    private val server: WotwBackendServer,
) {
    var worldMembershipId: Long? = null
        private set

    val currentPlayerId
        get() = playerId

    suspend fun onMessage(message: Any) {
        worldMembershipId?.let {
            server.gameHandlerRegistry.getHandler(multiverseId).onMessage(message, it)
        }
    }

    suspend fun onMultiverseEvent(message: Any) {
        server.gameHandlerRegistry.getHandler(multiverseId).onMultiverseEvent(message)
    }

    suspend fun setup(): GameConnectionHandlerSyncResult? {
        return newSuspendedTransaction {
            val worldMembership = WorldMembership.find {
                (WorldMemberships.userId eq playerId) and (WorldMemberships.multiverseId eq multiverseId)
            }.firstOrNull()

            val player = User.findById(playerId)

            if (player == null || worldMembership == null) {
                return@newSuspendedTransaction null
            }

            val world = worldMembership.world

            val universe = world.universe
            val multiverse = universe.multiverse
            this@GameConnectionHandler.worldMembershipId = worldMembership.id.value

            val handler = server.gameHandlerRegistry.getHandler(multiverse.id.value)
            val states = handler.generateStateAggregationRegistry(world).getSyncedStates()

            if (!handler.allowPrereleaseClientVersions() && (this@GameConnectionHandler.connection.clientVersion?.isPreRelease ?: true)) {
                val text = "This online game cannot be played on\ndevelopment versions of the randomizer."
                this@GameConnectionHandler.connection.sendMessage(makeServerTextMessage(text, 10f), ignoreAuthentication = true)
                this@GameConnectionHandler.connection.sendMessage(ShowUINotificationMessage(text, "error"), ignoreAuthentication = true)

                this@GameConnectionHandler.connection.webSocket.flush()
                this@GameConnectionHandler.connection.webSocket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid client version"))
                return@newSuspendedTransaction null
            }

            this@GameConnectionHandler.connection.sendMessage(
                InitGameSyncMessage(
                    states.toList(),
                    handler.shouldBlockStartingNewGame(),
                    SetSaveGuidRestrictionsMessage(
                        handler.getPlayerSaveGuid(worldMembership),
                        true,
                    ),
                    handler.shouldPreventCheats(worldMembership),
                    SetGameDifficultySettingsOverridesMessage(handler.getDifficultySettingsOverrides(worldMembership.id.value))
                )
            )

            val worldMemberNames = world.members.map { it.name }
            var greeting = "${player.name} - Connected to multiverse ${multiverse.id.value}"
            greeting += "\nWorld: ${world.name}\n" + worldMemberNames.joinToString()

            this@GameConnectionHandler.connection.sendMessage(makeServerTextMessage(greeting))

            this@GameConnectionHandler.connection.sendMessage(server.infoMessagesService.generateMultiverseInfoMessage(
                multiverse
            ))

            val setupResult = GameConnectionHandlerSyncResult(
                world.id.value,
                universe.id.value,
                multiverse.id.value,
                worldMembership.id.value,
            )

            return@newSuspendedTransaction setupResult
        }
    }
}
