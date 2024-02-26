package wotw.server.game.handlers.league

import kotlinx.serialization.Serializable
import wotw.server.api.AggregationStrategyRegistry
import wotw.server.database.model.LeagueGame
import wotw.server.database.model.LeagueGames
import wotw.server.database.model.Multiverse
import wotw.server.database.model.World
import wotw.server.game.handlers.GameHandler
import wotw.server.game.handlers.NormalGameHandlerState
import wotw.server.main.WotwBackendServer


class LeagueGameHandler(multiverseId: Long, server: WotwBackendServer) :
    GameHandler<NormalGameHandlerState>(multiverseId, server) {

    fun getLeagueGame(): LeagueGame {
        return LeagueGame.find { LeagueGames.multiverseId eq multiverseId }.firstOrNull() ?: throw RuntimeException("Could not find league game for multiverse $multiverseId")
    }

    override suspend fun generateStateAggregationRegistry(world: World): AggregationStrategyRegistry {
        // We don't sync anything in league games
        return AggregationStrategyRegistry()
    }
}
