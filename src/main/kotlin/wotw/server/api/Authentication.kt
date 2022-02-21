package wotw.server.api

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.date.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.TokenRequest
import wotw.io.messages.json
import wotw.server.database.model.User
import wotw.server.exception.ForbiddenException
import wotw.server.main.WotwBackendServer
import java.util.*

const val DISCORD_OAUTH = "discordOAuth"
const val JWT_AUTH = "jwt"

class AuthenticationEndpoint(server: WotwBackendServer) : Endpoint(server) {
    override fun Route.initRouting() {
        authenticate(DISCORD_OAUTH) {
            route("/login") {
                handle {
                    val principal =
                        call.authentication.principal<OAuthAccessTokenResponse.OAuth2>() ?: error("No Principal")
                    val user = handleOAuthToken(principal.accessToken)

                    var redir = call.request.cookies["authRedir"]
                    if (redir != null) {
                        try {
                            val url = Url(redir)
                            val validHosts = System.getenv("VALID_REDIRECT_URLS")?.split(",") ?: emptyList()
                            //                         ↓ Electron
                            if (url.protocol.name != "ori-rando" && !validHosts.any { url.hostWithPort.startsWith(it) }) {
                                throw BadRequestException("$url is not a valid redirect URL")
                            }
                        } catch (e: BadRequestException) {
                            throw e
                        } catch (e: Exception) {
                            throw BadRequestException("Invalid URL")
                        }
                    }

                    call.response.cookies.appendExpired("authRedir")

                    if (redir == null)
                        call.respondText("Hi ${user.name}! Your ID is ${user.id.value}")
                    else {
                        val token = createJWTToken(user, Scope.TOKEN_CREATE) {
                            withExpiresAt(Date(getTimeMillis() + 1000 * 60))
                        }
                        redir += if ("?" in redir) "&" else "?"
                        redir += "jwt=$token"
                        call.respondRedirect(redir)
                    }
                }
            }
        }

        authenticate(JWT_AUTH) {
            route("/tokens") {
                post<TokenRequest>("/") { request ->
                    val principal = wotwPrincipal()
                    val user = newSuspendedTransaction {
                        authenticatedUser()
                    }
                    val scopes = request.scopes.toTypedArray()
                    principal.require(Scope.TOKEN_CREATE)
                    call.respond(createJWTToken(user, *scopes) {
                        request.duration?.let {
                            withExpiresAt(Date(getTimeMillis() + it))
                        } ?: this
                    })

                }
            }
        }
    }

    private suspend fun handleOAuthToken(accessToken: String): User {
        val jsonResponse = HttpClient().get<String>("https://discord.com/api/users/@me") {
            header("Authorization", "Bearer $accessToken")
        }
        val json = json.parseToJsonElement(jsonResponse).jsonObject
        val userId = json["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val discordUserName = json["username"]?.jsonPrimitive?.contentOrNull
        val avatarId = json["avatar"]?.jsonPrimitive?.contentOrNull

        return newSuspendedTransaction {
            User.findById(userId)?.also {
                if (!it.isCustomName && discordUserName != null && it.name != discordUserName)
                    it.name = discordUserName
                it.avatarId = avatarId
            } ?: User.new(userId) {
                this.name = discordUserName ?: "unknown"
                this.avatarId = avatarId
            }
        }
    }


}

data class WotwUserPrincipal(val userId: String, private val scopes: Set<String>) : Principal {
    constructor(userId: String, vararg scopes: String) : this(userId, setOf(*scopes))

    fun hasScope(scope: String): Boolean {
        val queriedSegments = scope.split(".")
        return scopes.any {
            val providedSegments = it.split(".")
            it == "*" || queriedSegments.size >= providedSegments.size
                    && providedSegments == queriedSegments.take(providedSegments.size)
        }
    }

    fun hasAll(vararg scopes: String) = scopes.all { hasScope(it) }

    fun require(vararg scopes: String) {
        val missing = scopes.filter { !hasScope(it) }
        if (missing.isNotEmpty())
            throw ForbiddenException(missing)
    }

    fun requireAny(vararg scopes: String) {
        if (!scopes.any { hasScope(it) })
            throw ForbiddenException()
    }
}

fun createJWTToken(
    user: User,
    vararg scopes: String,
    block: JWTCreator.Builder.() -> JWTCreator.Builder = { this }
): String {
    return JWT.create()
        .withClaim("user_id", user.id.value)
        .withArrayClaim("scopes", scopes)
        .also { block(it) }
        .sign(Algorithm.HMAC256(System.getenv("JWT_SECRET")))

}

object Scope {
    const val MULTIVERSE_CONNECT = "multiverses.connect"
    const val MULTIVERSE_CREATE = "multiverses.create"
    const val MULTIVERSE_SPECTATE = "multiverses.spectate"
    const val WORLD_CREATE = "worlds.create"
    const val WORLD_JOIN = "worlds.join"
    const val USER_INFO_READ = "user.info.read"
    const val USER_INFO_WRITE = "user.info.write"
    const val TOKEN_CREATE = "tokens.create"
    const val BOARDS_READ = "boards.read"
    const val REMOTE_TRACKER_CREATE = "remote_tracker.create"
    const val ALL = "*"
}