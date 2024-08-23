package wotw.server.game.inventory

import wotw.io.messages.protobuf.ResourceRequestMessage
import wotw.io.messages.protobuf.SpendResourceTarget
import wotw.io.messages.protobuf.UberStateUpdateMessage
import wotw.server.database.model.GameState
import wotw.server.util.assertTransaction
import wotw.server.util.logger

class WorldInventory(private val gameState: GameState) {

    /**
     * Returns true if the request was handled successfully, otherwise false
     */
    fun handleRequest(request: ResourceRequestMessage): UberStateUpdateMessage? {
        assertTransaction()

        request.target?.let { target ->
            if (!canUpdateTarget(target)) {
                logger().info("Target condition not met, not handling resource request")
                return null
            }

            gameState.uberStateData[target.uberId] = target.value
        }

        var value = request.amount.toDouble()
        if (request.relative) {
            value += gameState.uberStateData[request.resourceUberId] ?: 0.0
        }

        gameState.uberStateData[request.resourceUberId] = value

        return UberStateUpdateMessage(request.resourceUberId, value)
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
