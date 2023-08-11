package wotw.server.util

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.SetSeedMessage
import wotw.io.messages.protobuf.UberId
import wotw.io.messages.protobuf.UberStateBatchUpdateMessage
import wotw.io.messages.protobuf.UberStateUpdateMessage
import wotw.server.database.PlayerEnvironmentCacheEntry
import wotw.server.database.model.User
import wotw.server.database.model.World
import wotw.server.game.PlayerJoinedEvent
import wotw.server.game.PlayerLeftEvent
import wotw.server.game.handlers.PlayerId
import wotw.server.main.WotwBackendServer
import wotw.server.sync.WorldStateCache

class MultiverseUtil(val server: WotwBackendServer) {
    suspend fun removePlayerFromCurrentWorld(player: User, cleanupCurrentMultiverse: Boolean = false) {
        player.currentWorld?.let { world ->
            val previousMultiverseId = world.universe.multiverse.id.value

            doAfterTransaction {
                server.multiverseMemberCache.invalidate(previousMultiverseId)
            }

            player.currentWorld = null

            world.universe.members.forEach { user ->
                doAfterTransaction {
                    server.playerEnvironmentCache.invalidate(user.id.value)
                }
            }

            if (cleanupCurrentMultiverse) {
                world.universe.multiverse.let { multiverse ->
                    multiverse.cleanup()
                    multiverse.updateAutomaticWorldNames()

                    doAfterTransaction {
                        server.multiverseMemberCache.invalidate(multiverse.id.value)
                    }
                }

                server.connections.broadcastMultiverseInfoMessage(previousMultiverseId)
            }
        }
    }

    suspend fun movePlayerToWorld(player: User, world: World) {
        if (player.currentWorld != world) {
            val previousMultiverseId = player.currentWorld?.universe?.multiverse?.id?.value
            val newMultiverseId = world.universe.multiverse.id.value

            removePlayerFromCurrentWorld(player, player.currentWorld?.universe?.multiverse?.id?.value != world.universe.multiverse.id.value)

            player.currentWorld = world
            player.flush()

            server.playerEnvironmentCache.put(player.id.value, PlayerEnvironmentCacheEntry(
                player.id.value,
                world.id.value,
                world.universe.members.map { it.id.value }.toSet(),
                world.members.map { it.id.value }.toSet(),
            ))

            val universeMemberIds = world.universe.members.map { it.id.value }

            logger().info("Moving ${player.name} to world ${world.id.value}")

            world.universe.multiverse.let { multiverse ->
                multiverse.refresh()
                multiverse.cleanup()
                multiverse.updateAutomaticWorldNames()

                doAfterTransaction {
                    server.multiverseMemberCache.invalidate(multiverse.id.value)
                }
            }

            doAfterTransaction {
                server.playerEnvironmentCache.invalidate(player.id.value)
                universeMemberIds.forEach { universeMemberId ->
                    server.playerEnvironmentCache.invalidate(universeMemberId)
                }

                server.connections.broadcastMultiverseInfoMessage(world.universe.multiverse.id.value)
                server.multiverseUtil.sendWorldStateAfterMovedToAnotherWorld(
                    world.id.value,
                    player.id.value,
                )

                newSuspendedTransaction {
                    if (previousMultiverseId != newMultiverseId) {
                        previousMultiverseId?.let {
                            server.gameHandlerRegistry.getHandler(it).onMultiverseEvent(PlayerLeftEvent(player))
                        }

                        server.gameHandlerRegistry.getHandler(newMultiverseId).onMultiverseEvent(PlayerJoinedEvent(player))
                    }
                }
            }
        }
    }

    suspend fun sendWorldStateAfterMovedToAnotherWorld(worldId: Long, playerId: PlayerId) {
        val (uberStateUpdateMessages, setSeedMessage) = newSuspendedTransaction {
            val uberStateUpdateMessages = WorldStateCache.getOrNull(worldId)?.map { (uberId, value) ->
                UberStateUpdateMessage(UberId(uberId.group, uberId.state), value)
            } ?: listOf()

            val setSeedMessage = World.findById(worldId)?.seed?.let { seed ->
                SetSeedMessage("${seed.id.value}.wotwr", seed.content)
            }

            uberStateUpdateMessages to setSeedMessage
        }

        setSeedMessage?.let {
            server.connections.toPlayers(
                listOf(playerId),
                it,
            )
        }

        server.connections.toPlayers(
            listOf(playerId),
            UberStateBatchUpdateMessage(uberStateUpdateMessages, true)
        )
    }
}