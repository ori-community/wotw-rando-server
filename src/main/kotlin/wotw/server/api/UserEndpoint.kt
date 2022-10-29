package wotw.server.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.main.WotwBackendServer
import wotw.server.util.put

class UserEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(JWT_AUTH) {
            route("users") {
                get("/me/info") {
                    val userInfo =
                        newSuspendedTransaction { server.infoMessagesService.generateUserInfo(authenticatedUser()) }
                    wotwPrincipal().require(Scope.USER_INFO_READ)

                    call.respond(userInfo)
                }

                put<String>("/me/nickname") {
                    if (it.isBlank())
                        throw BadRequestException("Nickname may not be blank!")
                    if (it.length > 32)
                        throw BadRequestException("Nickname too long!")

                    wotwPrincipal().require(Scope.USER_INFO_WRITE)
                    val userInfo = newSuspendedTransaction {
                        server.infoMessagesService.generateUserInfo(
                            authenticatedUser().apply {
                                name = it
                                isCustomName = true

                                this.currentWorld?.universe?.multiverse?.updateAutomaticWorldNames();
                            }
                        )
                    }

                    server.connections.notifyNicknameChanged(userInfo.id)

                    call.respond(userInfo)
                }

            }
        }
    }
}



