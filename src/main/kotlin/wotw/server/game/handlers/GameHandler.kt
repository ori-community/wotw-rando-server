@file:OptIn(ExperimentalSerializationApi::class)

package wotw.server.game.handlers

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protoBuf
import wotw.io.messages.protobuf.GameDifficultySettingsOverrides
import wotw.io.messages.protobuf.MoodGuid
import wotw.io.messages.protobuf.SetBlockStartingNewGameMessage
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.database.model.*
import wotw.server.game.GameConnectionHandler
import wotw.server.game.GameConnectionHandlerSyncResult
import wotw.server.game.handlers.league.LeagueGameHandler
import wotw.server.main.WotwBackendServer
import wotw.util.EventBus
import wotw.util.EventBusWithMetadata
import wotw.util.biMapOf
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

typealias PlayerId = String
typealias WorldMembershipId = Long

object GameHandlerType {
    const val NORMAL = 0
    @Deprecated("unmaintained")
    const val HIDE_AND_SEEK = 1
    @Deprecated("unmaintained")
    const val INFECTION = 2
    const val LEAGUE = 3
}

abstract class GameHandler<CLIENT_INFO_TYPE : Any>(
    val multiverseId: Long,
    val server: WotwBackendServer,
) {
    protected val messageEventBus = EventBusWithMetadata<WorldMembershipId>()
    protected val multiverseEventBus = EventBus()

    fun getMultiverse(): Multiverse {
        return Multiverse.findById(multiverseId) ?: throw RuntimeException("Could not find multiverse $multiverseId for game handler")
    }

    suspend fun onMessage(message: Any, sender: WorldMembershipId) {
        messageEventBus.send(message, sender)
    }

    suspend fun onMultiverseEvent(message: Any) {
        multiverseEventBus.send(message)
    }

    open fun serializeState(): String? = null

    open suspend fun restoreState(serializedState: String?) {

    }

    open suspend fun getAdditionalDebugInformation(): String? = null

    abstract suspend fun generateStateAggregationRegistry(world: World): AggregationStrategyRegistry

    /**
     * @param worldMembership WorldMembership
     * @return Return the save GUID for a player, or null if the client should force creating a new save file
     */
    open suspend fun getPlayerSaveGuid(worldMembership: WorldMembership): MoodGuid? = null

    open suspend fun shouldPreventCheats(worldMembership: WorldMembership): Boolean = false

    /**
     * Whether clients should block starting a new game
     */
    open suspend fun shouldBlockStartingNewGame(): Boolean = false

    /**
     * Called after this game handler has been unfrozen and loaded from the database
     */
    open fun start() {}

    /**
     * Called before this game handler is being frozen/serialized and stored to the database
     */
    open fun stop() {}

    open suspend fun onGameConnectionSetup(connectionHandler: GameConnectionHandler, setupResult: GameConnectionHandlerSyncResult) {}

    /**
     * Return false if the game handler must not be destroyed currently.
     * e.g. when a game is active and the handler has an active scheduler.
     * If you return false here, the state of the handler can be serialized
     * and the handler will be stopped and destroyed.
     */
    open suspend fun isDisposable(): Boolean = true

    suspend fun persistState() {
        newSuspendedTransaction {
            val multiverse = getMultiverse()
            multiverse.gameHandlerStateJson = serializeState()
        }
    }

    /**
     * Return serializable data that is sent to game clients
     */
    open fun getClientInfo(): CLIENT_INFO_TYPE? = null

    private fun serializeClientInfo(clientInfo: CLIENT_INFO_TYPE): ByteArray {
        val serializer = serializer(clientInfo::class.createType())
        return protoBuf.encodeToByteArray(serializer, clientInfo)
    }

    fun getSerializedClientInfo(): ByteArray {
        return getClientInfo()?.let {
            serializeClientInfo(it)
        } ?: ByteArray(0)
    }

    suspend fun notifyMultiverseOrClientInfoChanged() {
        newSuspendedTransaction {
            val multiverse = getMultiverse()
            val message = server.infoMessagesService.generateMultiverseInfoMessage(multiverse)

            server.multiverseMemberCache.getOrNull(multiverseId)?.worldMembershipIds?.let { worldMembershipIds ->
                server.connections.toPlayers(
                    worldMembershipIds,
                    message,
                )
            }

            server.connections.toObservers(multiverseId, message = message)
        }
    }

    protected suspend fun notifyShouldBlockStartingGameChanged() {
        newSuspendedTransaction {
            val message = SetBlockStartingNewGameMessage(shouldBlockStartingNewGame())

            server.multiverseMemberCache.getOrNull(multiverseId)?.worldMembershipIds?.let { worldMembershipIds ->
                server.connections.toPlayers(
                    worldMembershipIds,
                    message,
                )
            }

            server.connections.toObservers(multiverseId, message = message)
        }
    }

    companion object {
        private val handlerTypeMap = biMapOf(
            GameHandlerType.NORMAL to NormalGameHandler::class,
            GameHandlerType.LEAGUE to LeagueGameHandler::class,
        )

        fun getByGameHandlerByType(type: Int): KClass<out GameHandler<out Any>> {
            return handlerTypeMap[type] ?: throw IllegalArgumentException("Could not get game handler for type '$type'")
        }

        fun getByGameHandlerType(handler: KClass<out GameHandler<out Any>>): Int {
            return handlerTypeMap.inverse[handler] ?: throw IllegalArgumentException()
        }
    }

    /**
     * Called when [user] requests to create a new [Universe] in this game
     */
    open suspend fun onPlayerCreateUniverseRequest(user: User) {}

    /**
     * Called when [user] requests to create a new [World] in [universe]. [universe] is expected to be part of this game.
     */
    open suspend fun onPlayerCreateWorldRequest(user: User, universe: Universe) {}

    /**
     * Called when [user] requests to join [world]. [world] is expected to be part of this game.
     */
    open suspend fun onPlayerJoinWorldRequest(user: User, world: World) {}

    /**
     * Return true if [user] is allowed to become a spectator of this game
     */
    open fun canSpectateMultiverse(user: User): Boolean = true

    open fun canDuplicateMultiverse(): Boolean = true

    open suspend fun getDifficultySettingsOverrides(worldMembershipId: WorldMembershipId): GameDifficultySettingsOverrides? = null
}
