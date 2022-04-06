package wotw.server.util

import wotw.server.database.model.User
import wotw.server.main.WotwBackendServer

class MultiverseUtil(val server: WotwBackendServer) {
    suspend fun removePlayerFromCurrentWorld(player: User, dontNotifyMultiverseId: Long? = null) {
        player.currentWorld?.let { world ->
            val previousMultiverseId = world.universe.multiverse.id.value
            player.currentWorld = null

            if (previousMultiverseId != dontNotifyMultiverseId) {
                server.connections.broadcastMultiverseInfoMessage(previousMultiverseId)
            }
        }
    }
}