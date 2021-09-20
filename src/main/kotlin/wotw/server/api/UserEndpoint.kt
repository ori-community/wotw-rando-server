package wotw.server.api

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.response.*
import io.ktor.routing.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.UserInfo
import wotw.server.main.WotwBackendServer
import wotw.server.util.put

class UserEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(SESSION_AUTH, JWT_AUTH) {
            route("users") {
                get("/me/info") {
                    val user = newSuspendedTransaction { authenticatedUser() }
                    wotwPrincipal().require(Scope.USER_INFO_READ)

                    call.respond(server.userService.generateUserInfo(user))
                }
            }

            route("users") {
                get("/me") {
                    val user = newSuspendedTransaction {
                        authenticatedUser()
                    }
                    wotwPrincipal().require(Scope.USER_INFO_READ)
                    call.respond(user)
                }

                put<String>("/me/nickname") {
                    if(it.isBlank())
                        throw BadRequestException("Nickname may not be blank!")

                    wotwPrincipal().require(Scope.USER_INFO_WRITE)
                    val user = newSuspendedTransaction {
                        authenticatedUser().apply {
                            name = it
                            isCustomName = true
                        }
                    }
                    call.respond(user)
                }

            }
        }
    }
}



