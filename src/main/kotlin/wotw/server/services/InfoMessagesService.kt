package wotw.server.services

import wotw.io.messages.protobuf.MultiverseInfoMessage
import wotw.io.messages.protobuf.UniverseInfo
import wotw.io.messages.protobuf.UserInfo
import wotw.io.messages.protobuf.WorldInfo
import wotw.server.database.model.Multiverse
import wotw.server.database.model.Universe
import wotw.server.database.model.User
import wotw.server.database.model.World
import wotw.server.main.WotwBackendServer

class InfoMessagesService(private val server: WotwBackendServer) {
    val COLORS = arrayOf(
        "#1565c0",
        "#388e3c",
        "#ad1457",
        "#d32f2f",
        "#f57c00",
        "#00796b",
        "#303f9f",
        "#6d4c41",
    )

    fun generateMultiverseInfoMessage(multiverse: Multiverse) = MultiverseInfoMessage(
        multiverse.id.value,
        multiverse.universes.sortedBy { it.id }
            .mapIndexed { index, universe -> generateUniverseInfo(universe, COLORS[index % COLORS.size]) },
        multiverse.board != null,
        multiverse.spectators.map { generateUserInfo(it) },
        multiverse.seed?.id?.value
    )

    fun generateUniverseInfo(universe: Universe, color: String? = null) = UniverseInfo(
        universe.id.value,
        universe.name,
        color ?: COLORS[universe.multiverse.universes.sortedBy { it.id }.indexOf(universe) % COLORS.size],
        universe.worlds.mapIndexed { index, world ->
            generateWorldInfo(world, COLORS[index % COLORS.size])
        }
    )

    fun generateWorldInfo(world: World, color: String? = null) =
        WorldInfo(
            world.id.value,
            world.name,
            color ?: COLORS[world.universe.worlds.sortedBy { it.id }.indexOf(world) % COLORS.size],
            world.members.map { generateUserInfo(it) }
        )

    fun generateUserInfo(user: User) = UserInfo(
        user.id.value,
        user.name,
        user.avatarId,
        server.connections.playerMultiverseConnections[user.id.value]?.multiverseId,
        user.currentMultiverse?.id?.value
    )
}