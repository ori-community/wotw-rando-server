package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.response.*
import io.ktor.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.main.WotwBackendServer
import wotw.server.util.put

class UserEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(JWT_AUTH) {
            route("users") {
                get("/me/info") {
                    val userInfo = newSuspendedTransaction { server.infoMessagesService.generateUserInfo(authenticatedUser()) }
                    wotwPrincipal().require(Scope.USER_INFO_READ)

                    call.respond(userInfo)
                }

                put<String>("/me/nickname") {
                    if(it.isBlank())
                        throw BadRequestException("Nickname may not be blank!")
                    if(it.length > 32)
                        throw BadRequestException("Nickname too long!")

                    wotwPrincipal().require(Scope.USER_INFO_WRITE)
                    val userInfo = newSuspendedTransaction {
                        server.infoMessagesService.generateUserInfo(
                            authenticatedUser().apply {
                                name = it
                                isCustomName = true
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



