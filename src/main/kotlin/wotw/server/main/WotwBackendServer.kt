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
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.*
import io.ktor.util.network.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import wotw.io.messages.protobuf.*
import wotw.server.api.*
import wotw.server.database.PlayerUniversePopulationCache
import wotw.server.database.model.*
import wotw.server.exception.AlreadyExistsException
import wotw.server.exception.ConflictException
import wotw.server.exception.ForbiddenException
import wotw.server.exception.UnauthorizedException
import wotw.server.game.GameHandlerRegistry
import wotw.server.io.ClientConnectionUDPRegistry
import wotw.server.seedgen.SeedGeneratorService
import wotw.server.services.InfoMessagesService
import wotw.server.sync.StateSynchronization
import wotw.server.util.*
import java.time.Duration
import java.util.concurrent.TimeUnit

class WotwBackendServer {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            if (System.getenv("LOG_LEVEL") != null) {
                val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
                root.level = ch.qos.logback.classic.Level.valueOf(System.getenv("LOG_LEVEL"))
            }

            if (System.getenv("SENTRY_DSN") != null) {
                Sentry.init { options ->
                    options.dsn = System.getenv("SENTRY_DSN")
                    options.enableUncaughtExceptionHandler = false
                }

                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    logger().error(throwable)
                    Sentry.captureException(throwable)
                }
                logger().info("Error tracking enabled")
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

        var udpSocket: BoundDatagramSocket? = null
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
                BingoEvents,
                Spectators,
                Seeds,
            )
        }
    }

    val bingoEndpoint = BingoEndpoint(this)
    val multiverseEndpoint = MultiverseEndpoint(this)
    val seedGenEndpoint = SeedGenEndpoint(this)
    val authEndpoint = AuthenticationEndpoint(this)
    val userEndpoint = UserEndpoint(this)
    val remoteTrackerEndpoint = RemoteTrackerEndpoint(this)
    val developerEndpoint = DeveloperEndpoint(this)
    val infoMessagesService = InfoMessagesService(this)
    val multiverseUtil = MultiverseUtil(this)

    val connections = ConnectionRegistry(this)
    val sync = StateSynchronization(this)
    val seedGeneratorService = SeedGeneratorService(this)

    val populationCache = PlayerUniversePopulationCache()
    val cacheScheduler = Scheduler {
        sync.purgeCache(60)

        gameHandlerRegistry.cacheEntries.filter { cacheEntry ->
            !cacheEntry.isDisposable().also { disposable ->
                if (disposable) {
                    cacheEntry.handler.let { handler ->
                        logger.info("Disposed handler for multiverse ${handler.multiverseId}")
                        handler.stop()
                    }
                }
            }
        }
    }

    val shutdownHook = Thread {
        runBlocking {
            connections.toAll(
                false, makeServerTextMessage(
                    text = "Server is going down for maintenance.\nWill be back shortly. Or not. OriShrug",
                    time = 10f,
                )
            )
        }

        runBlocking {
            logger.info("Saving game handler states...")
            gameHandlerRegistry.cacheEntries.forEach { cacheEntry ->
                cacheEntry.handler?.let { handler ->
                    logger.info("Stopping and persisting handler for multiverse ${handler.multiverseId}")

                    handler.stop()
                    handler.persistState()
                }
            }
        }

        cacheScheduler.stop()
        runBlocking {
            sync.purgeCache(-1)
        }
    }

    val gameHandlerRegistry = GameHandlerRegistry(this)

    private fun startServer(args: Array<String>) {
        val cmd = commandLineEnvironment(args)
        cacheScheduler.scheduleExecution(Every(60, TimeUnit.SECONDS))
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        val env = applicationEngineEnvironment {
            config = cmd.config
            connectors += cmd.connectors
            module {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(10)
                    timeout = Duration.ofSeconds(10)
                    maxFrameSize = Long.MAX_VALUE
                }

                if (!System.getenv("ENABLE_HTTPS").isNullOrBlank())
                    install(HttpsRedirect)

                install(CallLogging) {
                    level = Level.INFO
                }
                install(CORS) {
                    method(HttpMethod.Options)
                    method(HttpMethod.Put)
                    allowNonSimpleContentTypes = true
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
                    exception<ConflictException> { _ ->
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
                }

                routing {
                    route("api") {
                        bingoEndpoint.init(this)
                        multiverseEndpoint.init(this)
                        authEndpoint.init(this)
                        userEndpoint.init(this)
                        remoteTrackerEndpoint.init(this)
                        seedGenEndpoint.init(this)
                        developerEndpoint.init(this)
                        get("/") {
                            call.respondText("WOTW-Backend running")
                        }
                    }
                }
            }
        }

        runBlocking {
            logger.info("Loading active game handlers...")
            newSuspendedTransaction {
                Multiverse.find {
                    Multiverses.gameHandlerActive eq true
                }.forEach {
                    logger.info("Loading game handler for game ${it.id.value}")
                    gameHandlerRegistry.cacheAndStartHandler(it)
                }
            }

            val udpSocketBuilder = aSocket(ActorSelectorManager(Dispatchers.IO)).udp()
            udpSocket = udpSocketBuilder.bind(NetworkAddress("0.0.0.0", (System.getenv("UDP_PORT") ?: "31415").toInt()))

            // TODO: Move this out of main class
            udpSocket?.let {
                launch(Dispatchers.IO + it.socketContext) {
                    handleUdpPackets(it)
                }
            }

            launch {
                embeddedServer(Netty, env).start(wait = true)
            }
        }
    }

    private suspend fun handleUdpPackets(udpSocket: BoundDatagramSocket) {
        logger.info("UDP socket listening on port ${udpSocket.localAddress.port}")

        for (datagram in udpSocket.incoming) {
            try {
                val udpPacket = UdpPacket.deserialize(datagram.packet.readBytes())

                if (udpPacket.udpId == null) {
                    logger.debug("Received UDP packet without connection ID")
                    continue
                }

                val connectionId = udpPacket.udpId
                val connection = ClientConnectionUDPRegistry.getById(connectionId)

                if (connection == null) {
                    logger.debug("Received UDP packet for unknown connection")
                    continue
                }

                val message = udpPacket.getPacket(connection.udpKey)

                if (message != null) {
                    connection.handleUdpMessage(datagram, message)
                } else {
                    logger.debug("WotwBackendServer: Could not deserialize UDP packet from connection $connectionId")
                }
            } catch (e: Exception) {
                logger.debug("Failed receiving UDP packet:", e)
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
