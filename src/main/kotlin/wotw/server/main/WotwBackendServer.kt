package wotw.server.main

import ch.qos.logback.classic.Logger
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.zaxxer.hikari.HikariDataSource
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.on
import io.ktor.client.*
import io.ktor.client.engine.java.*
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
import io.ktor.server.plugins.calllogging.CallLogging
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
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import wotw.io.messages.protobuf.UdpPacket
import wotw.server.api.*
import wotw.server.database.MultiverseMemberCache
import wotw.server.database.WorldMembershipEnvironmentCache
import wotw.server.database.model.*
import wotw.server.exception.ConflictException
import wotw.server.exception.ForbiddenException
import wotw.server.exception.MissingScopeException
import wotw.server.exception.UnauthorizedException
import wotw.server.game.GameHandlerRegistry
import wotw.server.game.handlers.league.LeagueManager
import wotw.server.io.ClientConnectionUDPRegistry
import wotw.server.opher.OpherAutobanController
import wotw.server.seedgen.SeedGeneratorService
import wotw.server.services.DiscordService
import wotw.server.services.InfoMessagesService
import wotw.server.sync.StateSynchronization
import wotw.server.util.*
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

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
                    options.isEnableUncaughtExceptionHandler = false
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

        val udpPort: Int get() = System.getenv("UDP_PORT").toIntOrNull() ?: 31415
        val announcedUdpPort: Int get() = System.getenv().getOrElse("ANNOUNCED_UDP_PORT", { null })?.toInt() ?: udpPort
    }

    val logger: org.slf4j.Logger = logger()

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
                WorldMemberships,
                GameStates,
                Users,
                BingoCardClaims,
                SeedSpoilerDownloads,
                Spectators,
                Seeds,
                WorldSeeds,
                BingothonTokens,
                Races,
                RaceTeams,
                RaceTeamMembers,
                LeagueSeasons,
                LeagueGames,
                LeagueGameSubmissions,
                LeagueSeasonMemberships,
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
    val serverEndpoint = ServerEndpoint(this)
    val leagueEndpoint = LeagueEndpoint(this)
    val infoMessagesService = InfoMessagesService(this)
    val multiverseUtil = MultiverseUtil(this)
    val discordService = DiscordService(this)
    val opherAutobanController = OpherAutobanController(this)
    private var kord: Kord? = null  // Use ifKord if you want to use it

    val connections = ConnectionRegistry(this)
    val sync = StateSynchronization(this)
    val seedGeneratorService = SeedGeneratorService(this)

    val worldMembershipEnvironmentCache = WorldMembershipEnvironmentCache()
    val multiverseMemberCache = MultiverseMemberCache()

    val cacheScheduler = Scheduler("Cache scheduler") {
        sync.purgeCache(60)
        bingoBoardCache.garbageCollect()

        gameHandlerRegistry.cacheEntries.filter { cacheEntry ->
            cacheEntry.isDisposable().also { disposable ->
                if (disposable) {
                    cacheEntry.handler.let { handler ->
                        logger.info("Disposed handler for multiverse ${handler.multiverseId}")
                        handler.stop()
                        handler.persistState()

                        newSuspendedTransaction {
                            handler.getMultiverse().gameHandlerActive = false
                        }
                    }
                }
            }
        }.forEach { cacheEntry ->
            gameHandlerRegistry.purgeFromCache(cacheEntry.handler.multiverseId)
        }
    }

    val bingothonEndpointCleanupScheduler = Scheduler("Bingothon endpoint cleanup scheduler") {
        newSuspendedTransaction {
            BingothonToken.all().forEach { token ->
                if (token.created.isBefore(LocalDateTime.now().minusDays(7))) {
                    token.delete()
                }
            }
        }
    }

    val userProfileUpdateScheduler = Scheduler("User profile update scheduler") {
        logger.debug("Updating profile pictures...")

        ifKord { kord ->
            val userIds = newSuspendedTransaction {
                Users.select(Users.id).map { it[Users.id] }
            }

            userIds.forEach { userId ->
                try {
                    val profile = kord.getUser(Snowflake(userId.value))

                    newSuspendedTransaction {
                        Users.update({ Users.id eq userId }) {
                            it[avatarId] = profile?.avatarHash
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Could not update profile picture of ${userId.value}", e)
                }

                delay(1000)
            }
        }

        logger.debug("Profile picture update task done.")
    }

    val shutdownHook = Thread {
        runBlocking {
            connections.toAll(
                false, makeServerTextMessage(
                    text = "Server is restarting.\nJust *keep playing*, the game will\nreconnect automatically in a few seconds.",
                    time = 6f,
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
        bingothonEndpointCleanupScheduler.stop()
        userProfileUpdateScheduler.stop()
        leagueManager.stop()

        runBlocking {
            sync.purgeCache(-1)
        }
    }

    val gameHandlerRegistry = GameHandlerRegistry(this)

    val leagueManager = LeagueManager(this)

    /**
     * Run [block] if there's an active Kord (Discord API) instance
     */
    suspend fun ifKord(block: suspend (Kord) -> Unit) {
        kord?.let {
            if (it.isActive) {
                block(it)
            }
        }
    }

    fun getUiUrl(path: String): String {
        val baseUrl = System.getenv("UI_BASE_URL") ?: throw RuntimeException("UI_BASE_URL not set")
        return "$baseUrl$path"
    }

    private fun startServer(args: Array<String>) {
        val server = embeddedServer(
            factory = Netty,
            environment = applicationEnvironment(),
            configure = {
                connectors.add(EngineConnectorBuilder().apply {
                    host = "0.0.0.0"
                    port = System.getenv("PORT")?.toIntOrNull() ?: 8081
                })
            },
            module = {
                install(WebSockets) {
                    pingPeriod = 10.seconds
                    timeout = 10.seconds
                    maxFrameSize = Long.MAX_VALUE
                }

                if (!System.getenv("ENABLE_HTTPS").isNullOrBlank()) {
                    install(HttpsRedirect)
                }

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
                    exception<ConflictException> { call, exception ->
                        call.respond(HttpStatusCode.Conflict, exception.message ?: "")
                    }
                    exception<UnauthorizedException> { call, exception ->
                        call.respond(HttpStatusCode.Unauthorized, exception.message ?: "")
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
                        client = HttpClient(Java) {
                            engine {
                                protocolVersion = java.net.http.HttpClient.Version.HTTP_2
                            }
                        }
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
                        serverEndpoint.init(this)
                        leagueEndpoint.init(this)

                        get("/ping") {
                            call.respondText("pong")
                        }
                    }
                }
            }
        )

        cacheScheduler.scheduleExecution(Every(60, TimeUnit.SECONDS))
        bingothonEndpointCleanupScheduler.scheduleExecution(Every(1, TimeUnit.HOURS))

        Runtime.getRuntime().addShutdownHook(shutdownHook)

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

            logger.info("Initializing League service...")
            leagueManager.recacheLeagueSeasonSchedules()
            leagueManager.setup()

            val udpSocketBuilder = aSocket(ActorSelectorManager(Dispatchers.IO)).udp()

            udpSocket = udpSocketBuilder.bind(InetSocketAddress("0.0.0.0", udpPort))

            // TODO: Move this out of main class
            udpSocket?.let {
                launch(Dispatchers.IO + it.socketContext) {
                    handleUdpPackets(it)
                }
            }

            launch {
                server.start(wait = true)
            }

            launch(Dispatchers.Default) {
                val token = System.getenv("DISCORD_BOT_TOKEN")

                if (token.isNullOrBlank()) {
                    logger.warn("DISCORD_BOT_TOKEN not set, continuing without Discord Bot integration")
                    return@launch
                }

                logger.info("Setting up Discord Bot...")
                kord = Kord(token)

                var handledConnectEventOnce = false
                kord?.on<ReadyEvent> {
                    logger.info("Discord Bot is ready")

                    if (!handledConnectEventOnce) {
                        handledConnectEventOnce = true
                        userProfileUpdateScheduler.scheduleExecution(Every(24 * 60, TimeUnit.MINUTES, 1))
                    }
                }

                kord?.login()
            }

            launch(Dispatchers.Default) {
                val token = System.getenv("DISCORD_OPHER_BOT_TOKEN")

                if (token.isNullOrBlank()) {
                    logger.warn("DISCORD_OPHER_BOT_TOKEN not set, continuing without Opher Autoban Bot integration")
                    return@launch
                }

                logger.info("Setting up Opher Autoban Bot...")
                opherAutobanController.start(token)
            }
        }
    }

    private suspend fun handleUdpPackets(udpSocket: BoundDatagramSocket) {
        logger.info("UDP socket listening on port ${udpSocket.localAddress.toJavaAddress().port}")

        for (datagram in udpSocket.incoming) {
            try {
                val udpPacket = UdpPacket.deserialize(datagram.packet.readByteArray())

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
                logger.trace("Failed receiving UDP packet:", e)
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
