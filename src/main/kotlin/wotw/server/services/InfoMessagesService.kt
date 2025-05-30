package wotw.server.services

import wotw.io.messages.protobuf.*
import wotw.server.database.model.*
import wotw.server.game.WotwSaveFileReader
import wotw.server.game.handlers.league.LeagueGameHandler
import wotw.server.main.WotwBackendServer

class InfoMessagesService(private val server: WotwBackendServer) {
    val colors = arrayOf(
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
        raceTeam.finishedTime,
    )

    fun generateRaceInfo(race: Race) = RaceInfo(
        race.id.value,
        race.teams.map(::generateRaceTeamInfo),
        race.finishedTime,
    )

    fun generateMultiverseMetadataInfoMessage(multiverse: Multiverse) = MultiverseMetadataInfoMessage(
        multiverse.id.value,
        multiverse.cachedBoard != null,
        multiverse.seed != null,
        multiverse.memberships.map { generateUserInfo(it.user) },
        multiverse.createdAt.toEpochMilli(),
    )

    suspend fun generateMultiverseInfoMessage(multiverse: Multiverse) = MultiverseInfoMessage(
        multiverse.id.value,
        multiverse.universes.sortedBy { it.id.value }
            .mapIndexed { index, universe -> generateUniverseInfo(universe, colors[index % colors.size]) },
        multiverse.cachedBoard != null,
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
        color ?: colors[universe.multiverse.universes.sortedBy { it.id.value }.indexOf(universe) % colors.size],
        universe.worlds.sortedBy { it.id.value }.mapIndexed { index, world ->
            generateWorldInfo(world, colors[index % colors.size])
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
            color ?: colors[world.universe.worlds.sortedBy { it.id.value }.indexOf(world) % colors.size],
            world.memberships.map(::generateWorldMembershipInfo),
            world.seed?.id?.value,
        )

    fun generateUserInfo(user: User) = UserInfo(
        user.id.value,
        user.name,
        user.avatarId,
        user.isDeveloper,
    )

    fun generateSaveFileGameStatsInfo(saveFileData: WotwSaveFileReader.SaveFileData) = SaveFileGameStatsInfo(
        inGameTime = saveFileData.inGameTime,
        collectedPickups = saveFileData.collectedPickups,
        teleports = saveFileData.teleports,
    )

    fun generateLeagueSeasonMembershipInfo(membership: LeagueSeasonMembership) = LeagueSeasonMembershipInfo(
        generateUserInfo(membership.user),
        membership.points,
        membership.rank,
        membership.lastRankDelta,
        membership.joinedAt.toEpochMilli(),
        membership.rankingCompensationPoints,
    )

    suspend fun generateLeagueGameInfo(game: LeagueGame, selfUser: User? = null): LeagueGameInfo {
        var userMetadata: LeagueGameUserMetadata? = null
        var submissionCount: Long? = null

        selfUser?.let { user ->
            (server.gameHandlerRegistry.getHandler(game.multiverse) as? LeagueGameHandler)?.let { leagueHandler ->
                userMetadata = LeagueGameUserMetadata(
                    leagueHandler.canSubmit(user),
                    leagueHandler.getSubmissionForThisGame(user)?.let { generateLeagueGameSubmissionInfo(it) },
                )

                if (leagueHandler.didSubmitForThisGame(user)) {
                    submissionCount = game.submissions.count()
                }
            }
        }

        if (submissionCount == null && !game.shouldHideSubmissionsForUnfinishedPlayers()) {
            submissionCount = game.submissions.count()
        }

        return LeagueGameInfo(
            game.id.value,
            game.multiverse.id.value,
            game.season.id.value,
            submissionCount ?: 0L,
            game.gameNumber,
            game.isCurrent,
            userMetadata,
        )
    }

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
        season.discardWorstGamesCount,
    )

    fun generateLeagueGameSubmissionInfo(submission: LeagueGameSubmission) = LeagueGameSubmissionInfo(
        submission.id.value,
        generateLeagueSeasonMembershipInfo(submission.membership),
        submission.submittedAt.toEpochMilli(),
        LeagueGameSubmissionRankingData(
            submission.time,
            submission.points,
            submission.rank,
            submission.videoUrl,
            submission.rankingMultiplier,
            submission.originalTime,
        ),
        submission.saveFile != null,
    )

    fun generateReducedLeagueGameSubmissionInfo(submission: LeagueGameSubmission) = LeagueGameSubmissionInfo(
        submission.id.value,
        generateLeagueSeasonMembershipInfo(submission.membership),
        submission.submittedAt.toEpochMilli(),
        null,
        false,
    )
}
