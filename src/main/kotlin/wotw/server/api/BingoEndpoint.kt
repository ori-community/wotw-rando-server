package wotw.server.api

import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.BingoData
import wotw.server.database.model.*
import wotw.server.main.WotwBackendServer

class BingoEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(JWT_AUTH) {
            get("multiverses/{multiverse_id}/bingo") {
                val multiverseId =
                    call.parameters["multiverse_id"]?.toLongOrNull()
                        ?: throw BadRequestException("Cannot parse multiverse_id")

                val playerIsSpectator = newSuspendedTransaction {
                    val multiverse = Multiverse.findById(multiverseId)
                    val player = authenticatedUserOrNull()

                    player != null && multiverse?.spectators?.contains(player) ?: false
                }

                newSuspendedTransaction {
                    val boardData = newSuspendedTransaction {
                        val player = authenticatedUserOrNull()

                        val multiverse = Multiverse.findById(multiverseId) ?: throw NotFoundException()
                        multiverse.cachedBoard ?: throw NotFoundException()
                        val info = multiverse.bingoUniverseInfo()

                        val currentPlayerUniverseInThisMultiverse = player?.id?.value?.let { playerId ->
                            Universe.wrapRows(
                                Universes
                                    .innerJoin(Worlds)
                                    .innerJoin(WorldMemberships)
                                    .selectAll()
                                    .where {
                                        (Universes.id eq Worlds.universeId) and
                                                (Worlds.id eq WorldMemberships.worldId) and
                                                (WorldMemberships.userId eq playerId) and
                                                (Universes.multiverseId eq multiverseId)
                                    }
                            ).firstOrNull()
                        }

                        BingoData(multiverse.createBingoBoardMessage(currentPlayerUniverseInThisMultiverse, playerIsSpectator), info)
                    }
                    call.respond(boardData)
                }
            }
        }
    }
}
