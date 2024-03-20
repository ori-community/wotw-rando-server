package wotw.server.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.BingothonTokenRequest
import wotw.io.messages.protobuf.*
import wotw.server.database.model.*
import wotw.server.main.WotwBackendServer
import wotw.server.util.logger
import wotw.server.util.randomString

class BingothonEndpoint(server: WotwBackendServer) : Endpoint(server) {
    val logger = logger()
    override fun Route.initRouting() {
        get("/bingothon/{token_id}") {
            val tokenId = call.parameters["token_id"].orEmpty()

            call.respond(newSuspendedTransaction {
                val token = BingothonToken.findById(tokenId) ?: throw NotFoundException()
                val player = token.owner
                val multiverse = token.multiverse
                val board = multiverse.board ?: throw BadRequestException("No bingo board attached to current multiverse")
                val playerIsSpectator = multiverse.spectators.contains(player)

                if (board.size != 5) {
                    throw BadRequestException("Bingothon does not support boards sized other than 5x5 yet")
                }

                val currentPlayerUniverseInThisMultiverse = Universe.find {
                    (Universes.id eq Worlds.universeId) and
                            (Worlds.id eq WorldMemberships.worldId) and
                            (WorldMemberships.userId eq player.id.value) and
                            (Universes.multiverseId eq multiverse.id)
                }.firstOrNull()

                val bingoInfo = multiverse.bingoUniverseInfo()
                val bingoData = BingoData(multiverse.createBingoBoardMessage(currentPlayerUniverseInThisMultiverse, playerIsSpectator), bingoInfo)

                val bingothonSquares = bingoData.board.squares.map {
                    var text = it.square.text
                    var html = it.square.text

                    val detailsText =
                        it.square.goals.joinToString("\n") { goal -> goal.text + if (goal.completed) " ✓" else "" }

                    if (detailsText.isNotBlank()) {
                        text += "\n$detailsText"
                        html += "<br><small>${detailsText.replace("\n", "<br>")}</small>"
                    }

                    BingothonBingoSquare(
                        it.position,
                        text,
                        html,
                        it.square.completedBy,
                        it.square.visibleFor,
                    )
                }

                BingothonBingoBoard(
                    board.size,
                    bingoInfo.map {
                        BingothonBingoUniverseInfo(
                            it.universeId,
                            it.squares,
                            it.lines,
                        )
                    },
                    bingothonSquares,
                )
            })
        }

        authenticate(JWT_AUTH) {
            post<BingothonTokenRequest>("/bingothon/token") { request ->
                wotwPrincipal().require(Scope.BINGOTHON_TOKEN_CREATE)

                call.respond(newSuspendedTransaction {
                    val user = authenticatedUser()

                    val multiverse = Multiverse.findById(request.multiverseId) ?: throw NotFoundException("Multiverse not found")

                    // Delete any existing token
                    BingothonToken.find {
                        (BingothonTokens.owner eq user.id.value) and (BingothonTokens.multiverseId eq request.multiverseId)
                    }.firstOrNull()?.delete()

                    var tokenId = ""
                    do {
                        tokenId = randomString(16)
                    } while (BingothonTokens.select { BingothonTokens.id eq tokenId }.count() > 0)

                    BingothonToken.new(tokenId) {
                        this.owner = user
                        this.multiverse = multiverse
                    }

                    tokenId
                })
            }
        }
    }
}
