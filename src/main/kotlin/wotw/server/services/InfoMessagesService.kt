package wotw.server.services

import wotw.io.messages.protobuf.*
import wotw.server.database.model.*
import wotw.server.game.handlers.league.LeagueGameHandler
import wotw.server.main.WotwBackendServer

class InfoMessagesService(private val server: WotwBackendServer) {
    val COLORS = arrayOf(
        "#1565c0",
        "#388e3c",
        "#ad1457",
        "#d32f2f",
        "#f57c00",
        "#00796b",
        "#303f9f",
        "#6d4c41",
    )

    fun generateRaceTeamMemberInfo(raceTeamMember: RaceTeamMember) = RaceTeamMemberInfo(
        raceTeamMember.id.value,
        generateUserInfo(raceTeamMember.user),
        raceTeamMember.finishedTime,
    )

    fun generateRaceTeamInfo(raceTeam: RaceTeam) = RaceTeamInfo(
        raceTeam.id.value,
        raceTeam.members.map(::generateRaceTeamMemberInfo),
        raceTeam.points,
        raceTeam.finishedTime,
    )

    fun generateRaceInfo(race: Race) = RaceInfo(
        race.id.value,
        race.teams.map(::generateRaceTeamInfo),
        race.finishedTime,
    )

    suspend fun generateMultiverseInfoMessage(multiverse: Multiverse) = MultiverseInfoMessage(
        multiverse.id.value,
        multiverse.universes.sortedBy { it.id }
            .mapIndexed { index, universe -> generateUniverseInfo(universe, COLORS[index % COLORS.size]) },
        multiverse.board != null,
        multiverse.spectators.map(::generateUserInfo),
        multiverse.seed?.takeIf { it.allowDownload }?.id?.value,
        multiverse.gameHandlerType,
        server.gameHandlerRegistry.getHandler(multiverse).getSerializedClientInfo(),
        multiverse.locked,
        multiverse.isLockable,
        multiverse.race?.let(::generateRaceInfo),
        multiverse.seed?.spoilerDownloads?.map(::generateUserInfo) ?: listOf(),
        multiverse.memberships.filter { server.connections.playerMultiverseConnections.containsKey(it.id.value) }.map { it.user.id.value },
        multiverse.memberships.filter { server.connections.playerMultiverseConnections[it.id.value]?.raceReady == true }.map { it.user.id.value },
    )

    fun generateUniverseInfo(universe: Universe, color: String? = null) = UniverseInfo(
        universe.id.value,
        universe.name,
        color ?: COLORS[universe.multiverse.universes.sortedBy { it.id }.indexOf(universe) % COLORS.size],
        universe.worlds.sortedBy { it.id }.mapIndexed { index, world ->
            generateWorldInfo(world, COLORS[index % COLORS.size])
        }
    )

    fun generateWorldMembershipInfo(worldMemberhip: WorldMembership) =
        WorldMembershipInfo(
            worldMemberhip.id.value,
            generateUserInfo(worldMemberhip.user),
        )

    fun generateWorldInfo(world: World, color: String? = null) =
        WorldInfo(
            world.id.value,
            world.name,
            color ?: COLORS[world.universe.worlds.sortedBy { it.id }.indexOf(world) % COLORS.size],
            world.memberships.map(::generateWorldMembershipInfo),
            world.seed?.id?.value,
        )

    fun generateUserInfo(user: User) = UserInfo(
        user.id.value,
        user.name,
        user.avatarId,
        user.isDeveloper,
        user.points,
    )

    fun generateLeagueSeasonMembershipInfo(membership: LeagueSeasonMembership) = LeagueSeasonMembershipInfo(
        generateUserInfo(membership.user),
        membership.points,
        membership.rank,
        membership.joinedAt.toEpochMilli(),
    )

    suspend fun generateLeagueGameInfo(game: LeagueGame, selfUser: User? = null) = LeagueGameInfo(
        game.id.value,
        game.multiverse.id.value,
        game.season.id.value,
        game.submissions.count(),
        game.gameNumber,
        game.isCurrent,
        selfUser?.let { user ->
            (server.gameHandlerRegistry.getHandler(game.multiverse) as? LeagueGameHandler)?.let { leagueHandler ->
                LeagueGameUserMetadata(
                    leagueHandler.canSubmit(user),
                    leagueHandler.didSubmitForThisGame(user),
                )
            }
        },
    )

    suspend fun generateLeagueSeasonInfo(season: LeagueSeason, selfUser: User? = null) = LeagueSeasonInfo(
        season.id.value,
        season.name,
        season.memberships.map(::generateLeagueSeasonMembershipInfo),
        season.gameCount,
        season.games.map { generateLeagueGameInfo(it, selfUser) },
        season.canJoin,
        season.currentGame?.id?.value,
        season.shortDescription,
        season.longDescriptionMarkdown,
        season.rulesMarkdown,
        season.nextContinuationAt.toEpochMilli(),
        season.backgroundImageUrl,
    )

    fun generateLeagueGameSubmissionInfo(submission: LeagueGameSubmission) = LeagueGameSubmissionInfo(
        submission.id.value,
        generateLeagueSeasonMembershipInfo(submission.membership),
        submission.submittedAt.toEpochMilli(),
        LeagueGameSubmissionRankingData(
            submission.time,
            submission.points,
            submission.rank,
            submission.discarded,
        )
    )

    fun generateReducedLeagueGameSubmissionInfo(submission: LeagueGameSubmission) = LeagueGameSubmissionInfo(
        submission.id.value,
        generateLeagueSeasonMembershipInfo(submission.membership),
        submission.submittedAt.toEpochMilli(),
        null,
    )
}
