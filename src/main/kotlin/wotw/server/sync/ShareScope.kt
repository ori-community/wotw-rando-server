package wotw.server.sync

enum class ShareScope{
    /**
     * Only share with same player
     * */
    PLAYER,
    /**
     * Only share within the same world of a multiworld game
     * */
    WORLD,
    /**
     * Share state cross-world
     * */
    UNIVERSE,
    /**
     * Share state among all players in the game
     * */
    MULTIVERSE,
}