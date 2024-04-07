package wotw.server.util

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.SetSeedMessage
import wotw.io.messages.protobuf.UberId
import wotw.io.messages.protobuf.UberStateBatchUpdateMessage
import wotw.io.messages.protobuf.UberStateUpdateMessage
import wotw.server.database.model.User
import wotw.server.database.model.World
import wotw.server.database.model.WorldMembership
import wotw.server.database.model.WorldMemberships
import wotw.server.game.PlayerJoinedEvent
import wotw.server.game.PlayerMovedEvent
import wotw.server.game.handlers.WorldMembershipId
import wotw.server.main.WotwBackendServer
import wotw.server.sync.WorldStateCache

class MultiverseUtil(val server: WotwBackendServer) {
    suspend fun leaveMultiverse(worldMembership: WorldMembership) {
        worldMembership.world.universe.memberships.forEach {
            doAfterTransaction {
                server.worldMembershipEnvironmentCache.invalidate(it.id.value)
            }
        }

        worldMembership.multiverse.let {
            it.cleanup()
            it.updateAutomaticWorldNames()

            doAfterTransaction {
                server.multiverseMemberCache.invalidate(it.id.value)
            }
        }

        worldMembership.delete()

        server.connections.broadcastMultiverseInfoMessage(worldMembership.multiverse)
    }

    suspend fun movePlayerToWorld(player: User, world: World): WorldMembership {
        var worldMembership = WorldMembership.find {
            (WorldMemberships.userId eq player.id) and (WorldMemberships.multiverseId eq world.universe.multiverse.id)
        }.firstOrNull()

        if (worldMembership?.world?.id == world.id) {
            return worldMembership
        }

        val newMultiverseId = world.universe.multiverse.id.value
        val isJoiningMultiverse = worldMembership == null  // If false, we're only moving inside the multiverse

        val universeWorldMembershipIds = world.universe.memberships.map { it.id.value }

        if (worldMembership == null) {
            worldMembership = WorldMembership.new {
                this.user = player
                this.multiverse = world.universe.multiverse
                this.world = world
            }
        } else {
            worldMembership.world = world
        }

        worldMembership.flush()
        val worldMembershipId = worldMembership.id.value

        doAfterTransaction {
            server.worldMembershipEnvironmentCache.invalidate(worldMembershipId)

            universeWorldMembershipIds.forEach { worldMembershipId ->
                server.worldMembershipEnvironmentCache.invalidate(worldMembershipId)
            }

            server.multiverseMemberCache.invalidate(newMultiverseId)

            server.connections.broadcastMultiverseInfoMessage(worldMembershipId)
            server.multiverseUtil.sendWorldStateAfterMovedToAnotherWorld(worldMembershipId)

            newSuspendedTransaction {
                if (isJoiningMultiverse) {
                    server.gameHandlerRegistry.getHandler(newMultiverseId).onMultiverseEvent(PlayerJoinedEvent(player))
                } else {
                    server.gameHandlerRegistry.getHandler(newMultiverseId).onMultiverseEvent(PlayerMovedEvent(player))
                }
            }
        }


        return worldMembership
    }

    suspend fun sendWorldStateAfterMovedToAnotherWorld(worldMembershipId: WorldMembershipId) {
        val (uberStateUpdateMessages, setSeedMessage) = newSuspendedTransaction {
            val worldMembership = WorldMembership.findById(worldMembershipId) ?: throw RuntimeException("world membership not found")

            val uberStateUpdateMessages = WorldStateCache.getOrNull(worldMembership.world.id.value)?.map { (uberId, value) ->
                UberStateUpdateMessage(UberId(uberId.group, uberId.state), value)
            } ?: listOf()

            val setSeedMessage = worldMembership.world.seed?.let { seed ->
                SetSeedMessage(seed.content)
            }

            uberStateUpdateMessages to setSeedMessage
        }

        setSeedMessage?.let {
            server.connections.toPlayers(
                listOf(worldMembershipId),
                it,
            )
        }

        server.connections.toPlayers(
            listOf(worldMembershipId),
            UberStateBatchUpdateMessage(uberStateUpdateMessages, true)
        )
    }
}
