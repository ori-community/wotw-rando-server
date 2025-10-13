package wotw.server.api

import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.server.database.model.Multiverses
import wotw.server.main.WotwBackendServer

class UserEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(JWT_AUTH) {
            route("users") {
                get("/me/info") {
                    val userInfo =
                        newSuspendedTransaction { server.infoMessagesService.generateUserInfo(authenticatedUser()) }
                    wotwPrincipal().require(Scope.USER_INFO_VIEW)

                    call.respond(userInfo)
                }

                put<String>("/me/nickname") {
                    if (it.isBlank())
                        throw BadRequestException("Nickname may not be blank!")
                    if (it.length > 32)
                        throw BadRequestException("Nickname too long!")

                    wotwPrincipal().require(Scope.USER_INFO_UPDATE)
                    val userInfo = newSuspendedTransaction {
                        server.infoMessagesService.generateUserInfo(
                            authenticatedUser().apply {
                                name = it
                                isCustomName = true

                                this.multiverses
                                    .orderBy(Multiverses.id to SortOrder.DESC)
                                    .limit(10)
                                    .forEach { multiverse ->
                                        multiverse.updateAutomaticWorldNames()
                                    }
                            }
                        )
                    }

                    server.connections.notifyUserInfoChanged(userInfo.id)

                    call.respond(userInfo)
                }

            }
        }
    }
}



