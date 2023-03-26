package wotw.server.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.*
import wotw.server.database.model.BingothonToken
import wotw.server.database.model.BingothonTokens
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
                val world = player.currentWorld ?: throw NotFoundException("Player is not part of a world")
                val multiverse = world.universe.multiverse
                val board =
                    multiverse.board ?: throw BadRequestException("No bingo board attached to current multiverse")
                val playerIsSpectator = multiverse.spectators.contains(player)

                if (board.size != 5) {
                    throw BadRequestException("Bingothon does not support boards sized other than 5x5 yet")
                }

                val bingoInfo = multiverse.bingoUniverseInfo()
                val bingoData = BingoData(multiverse.createBingoBoardMessage(world.universe, playerIsSpectator), bingoInfo)

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
            post("/bingothon/token") {
                wotwPrincipal().require(Scope.BINGOTHON_TOKEN_CREATE)

                call.respond(newSuspendedTransaction {
                    val user = authenticatedUser()

                    // Delete any existing token
                    BingothonToken.find {
                        BingothonTokens.owner eq user.id.value
                    }.firstOrNull()?.delete()

                    var tokenId = ""
                    do {
                        tokenId = randomString(16)
                    } while (BingothonTokens.select { BingothonTokens.id eq tokenId }.count() > 0)

                    BingothonToken.new(tokenId) {
                        this.owner = user
                    }

                    tokenId
                })
            }
        }
    }
}
