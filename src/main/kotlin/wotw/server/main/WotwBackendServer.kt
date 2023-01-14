package wotw.server.main

import ch.qos.logback.classic.Logger
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.httpsredirect.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.network.*
import io.ktor.utils.io.core.*
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
import wotw.io.messages.protobuf.UdpPacket
import wotw.server.api.*
import wotw.server.database.MultiverseMemberCache
import wotw.server.database.PlayerEnvironmentCache
import wotw.server.database.model.*
import wotw.server.exception.*
import wotw.server.game.GameHandlerRegistry
import wotw.server.io.ClientConnectionUDPRegistry
import wotw.server.seedgen.SeedGeneratorService
import wotw.server.services.InfoMessagesService
import wotw.server.sync.StateSynchronization
import wotw.server.util.*
import java.time.Duration
import java.time.LocalDateTime
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
                    options.setEnableUncaughtExceptionHandler(false)
                }

                Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
                    Sentry.captureException(throwable)
                    throw throwable
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
        this.db = Database.connect(ds)

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
                WorldSeeds,
                BingothonTokens,
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
    val bingothonEndpoint = BingothonEndpoint(this)
    val infoMessagesService = InfoMessagesService(this)
    val multiverseUtil = MultiverseUtil(this)

    val connections = ConnectionRegistry(this)
    val sync = StateSynchronization(this)
    val seedGeneratorService = SeedGeneratorService(this)

    val playerEnvironmentCache = PlayerEnvironmentCache()
    val multiverseMemberCache = MultiverseMemberCache()

    val cacheScheduler = Scheduler {
        sync.purgeCache(60)

        gameHandlerRegistry.cacheEntries.filter { cacheEntry ->
            cacheEntry.isDisposable().also { disposable ->
                if (disposable) {
                    cacheEntry.handler.let { handler ->
                        logger.info("Disposed handler for multiverse ${handler.multiverseId}")
                        handler.stop()
                    }
                }
            }
        }.forEach { cacheEntry ->
            gameHandlerRegistry.purgeFromCache(cacheEntry.handler.multiverseId)
        }
    }

    val bingothonEndpointCleanupScheduler = Scheduler {
        newSuspendedTransaction {
            BingothonToken.all().forEach { token ->
                if (token.created.isBefore(LocalDateTime.now().minusDays(7))) {
                    token.delete()
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
                cacheEntry.handler.let { handler ->
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
        bingothonEndpointCleanupScheduler.scheduleExecution(Every(1, TimeUnit.HOURS))

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
                    methods.add(HttpMethod.Options)
                    methods.add(HttpMethod.Put)
                    headers.add(HttpHeaders.Authorization)
                    headers.add(HttpHeaders.AccessControlAllowOrigin)
                    headers.add(HttpHeaders.Origin)
                    allowNonSimpleContentTypes = true
                    allowXHttpMethodOverride()
                    anyHost()
                }

                install(ContentNegotiation) {
                    json(wotw.io.messages.json)
                }

                install(AutoHeadResponse)
                install(StatusPages) {
                    exception<Throwable> { call, exception ->
                        exception.printStackTrace()
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                    exception<AlreadyExistsException> { call, _ ->
                        call.respond(HttpStatusCode.Conflict)
                    }
                    exception<ConflictException> { call, _ ->
                        call.respond(HttpStatusCode.Conflict)
                    }
                    exception<UnauthorizedException> { call, _ ->
                        call.respond(HttpStatusCode.Unauthorized)
                    }
                    exception<BadRequestException> { call, exception ->
                        call.respond(HttpStatusCode.BadRequest, exception.message ?: "")
                    }
                    exception<NotFoundException> { call, exception ->
                        call.respond(HttpStatusCode.NotFound, exception.message ?: "")
                    }
                    exception<MissingScopeException> { call, exception ->
                        call.respond(HttpStatusCode.Forbidden, exception.message ?: "")
                    }
                    exception<ForbiddenException> { call, exception ->
                        call.respond(HttpStatusCode.Forbidden, exception.message ?: "")
                    }
                }

                val discordOauthProvider = OAuthServerSettings.OAuth2ServerSettings(
                    name = "discord",
                    clientId = System.getenv("DISCORD_CLIENT_ID"),
                    clientSecret = System.getenv("DISCORD_SECRET"),
                    authorizeUrl = "https://discord.com/api/oauth2/authorize",
                    accessTokenUrl = "https://discord.com/api/oauth2/token",
                    defaultScopes = listOf("identify"),
                    requestMethod = HttpMethod.Post,
                )

                install(Authentication) {
                    oauth(DISCORD_OAUTH) {
                        client = HttpClient()
                        providerLookup = { discordOauthProvider }
                        urlProvider = { redirectUrl("/api/auth/handle-login") }
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
                        bingothonEndpoint.init(this)

                        get("/ping") {
                            call.respondText("pong")
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

            udpSocket =
                udpSocketBuilder.bind(InetSocketAddress("0.0.0.0", (System.getenv("UDP_PORT") ?: "31415").toInt()))

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
        logger.info("UDP socket listening on port ${udpSocket.localAddress.toJavaAddress().port}")

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
