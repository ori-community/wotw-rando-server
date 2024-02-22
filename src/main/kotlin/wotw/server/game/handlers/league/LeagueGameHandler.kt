package wotw.server.game.handlers.league

import wotw.server.api.AggregationStrategyRegistry
import wotw.server.database.model.World
import wotw.server.game.handlers.GameHandler
import wotw.server.game.handlers.NormalGameHandlerState
import wotw.server.main.WotwBackendServer

class LeagueGameHandler(multiverseId: Long, server: WotwBackendServer) :
    GameHandler<NormalGameHandlerState>(multiverseId, server) {



    override suspend fun generateStateAggregationRegistry(world: World): AggregationStrategyRegistry {
        return AggregationStrategyRegistry()
    }
}
