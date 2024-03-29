package wotw.server.services

import wotw.server.main.WotwBackendServer
import wotw.server.util.logger

class DiscordService(private val server: WotwBackendServer) {
    fun getEmojiMarkdown(name: String): String {
        val emojiId = System.getenv("DISCORD_EMOJI_${name.uppercase()}")

        if (emojiId.isNullOrBlank()) {
            logger().error("Discord Emoji ID for $name not set. Please set the DISCORD_EMOJI_${name.uppercase()} environment variable.")
            return ""
        }

        return "<:$name:$emojiId>"
    }
}
