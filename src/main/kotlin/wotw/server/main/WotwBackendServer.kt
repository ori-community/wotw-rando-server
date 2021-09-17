package wotw.server.main

import ch.qos.logback.classic.Logger
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.zaxxer.hikari.HikariDataSource
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.auth.jwt.*
import io.ktor.client.*
import io.ktor.features.*
import io.ktor.html.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.sessions.*
import io.ktor.util.network.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.html.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import wotw.io.messages.protobuf.Packet
import wotw.server.api.*
import wotw.server.database.PlayerUniversePopulationCache
import wotw.server.database.model.*
import wotw.server.exception.AlreadyExistsException
import wotw.server.exception.ForbiddenException
import wotw.server.exception.UnauthorizedException
import wotw.server.io.ClientConnectionUDPRegistry
import wotw.server.seedgen.SeedGeneratorService
import wotw.server.sync.StateSynchronization
import wotw.server.util.logger
import java.io.File
import java.nio.ByteBuffer
import kotlin.experimental.xor

class WotwBackendServer {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            if (System.getenv("LOG_LEVEL") != null) {
                val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
                root.level = ch.qos.logback.classic.Level.valueOf(System.getenv("LOG_LEVEL"))
            }
            WotwBackendServer().start(args)
        }

        fun getJwtVerifier(): JWTVerifier {
            return JWT
                .require(Algorithm.HMAC256(System.getenv("JWT_SECRET")))
                .build()
        }

        suspend fun validateJwt(credential: JWTCredential): WotwUserPrincipal? {
            val userId = credential.payload.getClaim("user_id").asString()
            val scopes =
                credential.payload.getClaim("scopes").asArray(String::class.java) ?: emptyArray()

            return newSuspendedTransaction {
                User.findById(userId)?.id?.value
            }?.let { WotwUserPrincipal(userId, *scopes) }
        }
    }

    val logger = logger()

    fun start(args: Array<String>) {
        initDatabase()
        startServer(args)
    }

    lateinit var db: Database
    private fun initDatabase() {
        val host = System.getenv("WOTW_DB_HOST")
        val port = System.getenv("WOTW_DB_PORT")
        val db = System.getenv("WOTW_DB")
        val user = System.getenv("WOTW_DB_USER")
        val password = System.getenv("WOTW_DB_PW")

        val ds = HikariDataSource().apply {
            jdbcUrl = "jdbc:postgresql://$host:$port/$db"
            username = user
            this.password = password
            driverClassName = "org.postgresql.Driver"
        }
        this.db =
            Database.connect(ds)//"jdbc:postgresql://$host:$port/$db?user=$user&password=$password", "org.postgresql.Driver")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Multiverses,
                Universes,
                Worlds,
                GameStates,
                Users,
                WorldMemberships,
                BingoEvents,
                Spectators
            )
        }

    }

    val proxyEndpoint = ProxyEndpoint(this)
    val bingoEndpoint = BingoEndpoint(this)
    val multiverseEndpoint = MultiverseEndpoint(this)
    val seedGenEndpoint = SeedGenEndpoint(this)
    val authEndpoint = AuthenticationEndpoint(this)
    val userEndpoint = UserEndpoint(this)

    val connections = ConnectionRegistry()
    val sync = StateSynchronization(this)
    val seedGeneratorService = SeedGeneratorService(this)

    val populationCache = PlayerUniversePopulationCache()

    private fun startServer(args: Array<String>) {
        val cmd = commandLineEnvironment(args)
        val env = applicationEngineEnvironment {
            config = cmd.config
            connectors += cmd.connectors
            module {
                install(WebSockets) {
                    maxFrameSize = Long.MAX_VALUE
                }

                if (!System.getenv("ENABLE_HTTPS").isNullOrBlank())
                    install(HttpsRedirect)

                install(CallLogging) {
                    level = Level.INFO
                }
                /*install(Compression) {
                    gzip {
                        condition {
                            request.uri.startsWith("http") && !request.uri.startsWith("https")
                                    || request.headers[HttpHeaders.Referrer]?.startsWith("https://wotw.orirando.com/") == true
                                    || request.headers[HttpHeaders.Referrer]?.startsWith("https://discord.com/oauth2/authorize") == true
                        }
                    }
                }*/
                install(CORS) {
                    method(HttpMethod.Options)
                    allowNonSimpleContentTypes = true
                    allowCredentials = true
                    header(HttpHeaders.Authorization)
                    header(HttpHeaders.AccessControlAllowOrigin)
                    header(HttpHeaders.Origin)
                    allowXHttpMethodOverride()
                    anyHost()
                }
                install(ContentNegotiation) {
                    json(wotw.io.messages.json)
                }
                install(AutoHeadResponse)
                install(StatusPages) {
                    exception<Throwable> { exception ->
                        exception.printStackTrace()
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                    exception<AlreadyExistsException> { _ ->
                        call.respond(HttpStatusCode.Conflict)
                    }
                    exception<UnauthorizedException> { _ ->
                        call.respond(HttpStatusCode.Unauthorized)
                    }
                    exception<BadRequestException> {
                        call.respond(HttpStatusCode.BadRequest, it.message ?: "")
                    }
                    exception<NotFoundException> {
                        call.respond(HttpStatusCode.NotFound, it.message ?: "")
                    }
                    exception<ForbiddenException> {
                        call.respond(HttpStatusCode.Forbidden, it.message ?: "")
                    }
                }
                val discordOauthProvider = OAuthServerSettings.OAuth2ServerSettings(
                    name = "discord",
                    clientId = System.getenv("DISCORD_CLIENT_ID"),
                    clientSecret = System.getenv("DISCORD_SECRET"),
                    authorizeUrl = "https://discord.com/api/oauth2/authorize",
                    accessTokenUrl = "https://discord.com/api/oauth2/token",
                    defaultScopes = listOf("identify"),
                    requestMethod = HttpMethod.Post
                )

                val redirectCookiePahse = PipelinePhase("RedirCookiePhase")
                install(Authentication) {
                    oauth(DISCORD_OAUTH) {
                        client = HttpClient()
                        providerLookup = { discordOauthProvider }
                        urlProvider = { redirectUrl("/api/login") }
                        pipeline.insertPhaseBefore(AuthenticationPipeline.RequestAuthentication, redirectCookiePahse)
                        pipeline.intercept(redirectCookiePahse) {
                            call.request.queryParameters["redir"]?.also {
                                call.response.cookies.append("authRedir", it)
                            }
                        }
                    }
                    jwt(JWT_AUTH) {
                        realm = "wotw-backend-server"
                        verifier(getJwtVerifier())
                        validate { jwtCredential ->
                            validateJwt(jwtCredential)
                        }
                    }
                    session<UserSession>(SESSION_AUTH) {
                        challenge {
                            call.respond(HttpStatusCode.Unauthorized)
                        }
                        validate { session ->
                            newSuspendedTransaction {
                                User.findById(session.user)?.id?.value
                            }?.let { WotwUserPrincipal(it, Scope.ALL) }
                        }
                    }
                }

                install(Sessions) {
                    cookie<UserSession>(SESSION_AUTH, directorySessionStorage(File(".sessions"), cached = true)) {
                        cookie.path = "/"
                    }
                }

                routing {
                    route("api") {
                        bingoEndpoint.init(this)
                        multiverseEndpoint.init(this)
                        authEndpoint.init(this)
                        userEndpoint.init(this)
                        seedGenEndpoint.init(this)
                        get("/") {
                            call.respondText("WOTW-Backend running")
                        }
                    }
                    proxyEndpoint.init(this)
                    static("static") {
                        resource("flex-helper.css")
                        resource("style.css")
                        resource("wotw-server.js")
                        resource("wotw-server.js.map")
                        defaultResource("index.html")
                    }
                    get("{...}") {
                        call.respondHtml {
                            head {
                                link("/static/flex-helper.css", rel = "stylesheet")
                                link("/static/style.css", rel = "stylesheet")
                            }
                            body {
                                div {
                                    id = "content"
                                }
                                script(src = "/static/wotw-server.js") {}
                            }
                        }
                    }
                }
            }
        }

        runBlocking {
            val udpSocketBuilder = aSocket(ActorSelectorManager(Dispatchers.IO)).udp()
            val udpSocket = udpSocketBuilder.bind(NetworkAddress("0.0.0.0", (System.getenv("UDP_PORT") ?: "31415").toInt()))

            // TODO: Move this out of main class
            launch(Dispatchers.IO + udpSocket.socketContext) {
                logger.info("UDP socket listening on port ${udpSocket.localAddress.port}")

                for (datagram in udpSocket.incoming) {
                    try {
                        if (datagram.packet.remaining < 4) {
                            logger.debug("Receive invalid packet (too small)")
                            continue
                        }

                        val connectionId = datagram.packet.readInt()
                        val connection = ClientConnectionUDPRegistry.getById(connectionId)

                        if (connection == null) {
                            logger.debug("Received UDP packet for unknown connection")
                            continue
                        }

                        val byteBuffer = ByteBuffer.allocate(datagram.packet.remaining.toInt())
                        datagram.packet.readAvailable(byteBuffer)

                        for (i in 0 until byteBuffer.capacity() - 1) {
                            byteBuffer.put(i, byteBuffer[i].xor(connection.udpKey[i % connection.udpKey.size]))
                        }

                        val message = Packet.deserialize(byteBuffer.array())

                        if (message != null) {
                            connection.handleUdpMessage(datagram, message)
                        } else {
                            logger.debug("WotwBackendServer: Could not deserialize UDP packet from connection $connectionId")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            launch {
                embeddedServer(Netty, env).start(wait = true)
            }
        }
    }

    private fun ApplicationCall.redirectUrl(path: String): String {
        return if (!System.getenv("PUBLIC_URL").isNullOrBlank()) {
            System.getenv("PUBLIC_URL") + path
        } else {
            val defaultPort = if (request.origin.scheme == "http") 80 else 443
            val hostPort = request.host() + request.port().let { port -> if (port == defaultPort) "" else ":$port" }
            val protocol = request.origin.scheme
            "$protocol://$hostPort$path"
        }
    }
}
