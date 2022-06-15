package wotw.server.game.inventory

import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import wotw.io.messages.protobuf.SpendResourceRequestMessage
import wotw.io.messages.protobuf.SpendResourceTarget
import wotw.server.database.model.GameState
import wotw.server.util.logger

class WorldInventory(private val gameState: GameState) {

    /**
     * Returns true if the request was handled successfully, otherwise false
     */
    suspend fun handleRequest(request: SpendResourceRequestMessage): Boolean {
        return newSuspendedTransaction {
            request.target?.let { target ->
                if (!canUpdateTarget(target)) {
                    logger().info("Target condition not met, not handling resource request")
                    return@newSuspendedTransaction false
                }

                gameState.uberStateData[target.uberId] = target.value
            }

            var value = request.amount.toDouble();
            if (request.relative) {
                value += gameState.uberStateData[request.resourceUberId] ?: 0.0
            }

            gameState.uberStateData[request.resourceUberId] = value

            return@newSuspendedTransaction true
        }
    }

    private fun canUpdateTarget(target: SpendResourceTarget): Boolean {
        val currentValue = gameState.uberStateData[target.uberId] ?: return false

        return when (target.updateIf) {
            SpendResourceTarget.UPDATE_IF_LARGER -> currentValue < target.value
            SpendResourceTarget.UPDATE_IF_SMALLER -> currentValue > target.value
            SpendResourceTarget.UPDATE_IF_DIFFERENT -> currentValue != target.value
            else -> false
        }
    }
}