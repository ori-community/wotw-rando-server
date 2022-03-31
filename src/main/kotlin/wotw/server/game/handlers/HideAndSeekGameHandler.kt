package wotw.server.game.handlers

import kotlinx.serialization.Serializable
import wotw.io.messages.json
import wotw.io.messages.protobuf.PlayerPositionMessage
import wotw.io.messages.protobuf.Vector2
import wotw.server.util.Every
import wotw.server.util.Scheduler
import wotw.server.util.logger
import java.util.Collections
import java.util.concurrent.TimeUnit


@Serializable
data class HideAndSeekGameHandlerState(
    var started: Boolean = false,
    var catchPhase: Boolean = false,
    var secondsUntilCatchPhase: Int = 300,
    var gameSecondsElapsed: Int = 0,
)
class HideAndSeekGameHandler(multiverseId: Long) : GameHandler(multiverseId) {
    private val playerPositionMap: MutableMap<PlayerId, Vector2> = Collections.synchronizedMap(mutableMapOf())
    private var state = HideAndSeekGameHandlerState()

    private val scheduler = Scheduler {
        state.apply {
            if (started) {
                if (!catchPhase) {
                    secondsUntilCatchPhase--

                    if (secondsUntilCatchPhase == 0) {
                        catchPhase = true
                    }
                } else {
                    gameSecondsElapsed++
                }
            }
        }
    }

    override fun start() {
        messageEventBus.register(this, PlayerPositionMessage::class) { message, playerId ->
            playerPositionMap[playerId] = Vector2(message.x, message.y)
        }

        scheduler.scheduleExecution(Every(1, TimeUnit.SECONDS))
    }

    override fun stop() {
        scheduler.stop()
    }

    override fun serializeState(): String {
        return json.encodeToString(HideAndSeekGameHandlerState.serializer(), state)
    }

    override fun restoreState(serializedState: String?) {
        serializedState?.let {
            state = json.decodeFromString(HideAndSeekGameHandlerState.serializer(), it)
        }
    }
}