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

class UserService(private val server: WotwBackendServer) {
    fun generateMultiverseInfoMessage(multiverse: Multiverse) = MultiverseInfoMessage(multiverse.id.value, multiverse.universes.map { generateUniverseInfo(it) }, multiverse.board != null, multiverse.spectators.map { generateUserInfo(it) })
    fun generateUniverseInfo(universe: Universe) = UniverseInfo(universe.id.value, universe.name, universe.worlds.map { generateWorldInfo(it) })
    fun generateWorldInfo(world: World) = WorldInfo(world.id.value,world.name, world.members.map { generateUserInfo(it) })
    fun generateUserInfo(user: User) = UserInfo(user.id.value, user.name, user.avatarId, server.connections.playerMultiverseConnections[user.id.value]?.multiverseId, user.currentMultiverse?.id?.value)
}