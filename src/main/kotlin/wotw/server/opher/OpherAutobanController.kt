package wotw.server.opher

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Message
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import wotw.server.main.WotwBackendServer
import wotw.server.util.Every
import wotw.server.util.Scheduler
import wotw.server.util.logger
import wotw.server.util.md5
import wotw.util.ExpiringCache
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

typealias MessageHash = String
typealias MemberId = Pair<Snowflake, Snowflake> // Guild ID and User ID

const val AUTOBAN_BURST = 2
val AUTOBAN_BURST_TIMESPAN = 3.minutes

class OpherAutobanController(val server: WotwBackendServer) {
    private class RecentMemberCommunication {
        class MessageBurstInfo {
            //                          ChannelID  Last occurrence
            val channels = mutableMapOf<Snowflake, Instant>()
            val messages = mutableSetOf<Message>()

            fun garbageCollect() {
                channels -= channels
                    .filterValues { lastOccurrence -> lastOccurrence < Clock.System.now().minus(AUTOBAN_BURST_TIMESPAN) }
                    .keys
            }

            suspend fun tryPurgeMessages() {
                for (message in messages) {
                    try {
                        message.delete("Opher Autoban")
                    } catch (e: Exception) {
                        logger().error("OpherAutoban: Could not delete message during autoban", e)
                    }
                }

                messages.clear()
            }
        }

        val messageBurstsInChannels = ExpiringCache<MessageHash, MessageBurstInfo>(10.minutes)

        fun garbageCollect() {
            for (value in messageBurstsInChannels.values) {
                value.garbageCollect()
            }

            messageBurstsInChannels.garbageCollect()
        }

        private fun hashMessage(message: Message): MessageHash = message.content.trim().md5()

        suspend fun reportMessage(message: Message): MessageBurstInfo {
            val burstInfo = messageBurstsInChannels.getOrPut(hashMessage(message)) { MessageBurstInfo() }
            burstInfo.channels[message.channelId] = Clock.System.now()
            burstInfo.messages += message
            return burstInfo
        }
    }

    private val channelIdGuildIdCache = ExpiringCache<Snowflake, Snowflake>(2.days)
    private val rateLimitCache = ExpiringCache<MemberId, RecentMemberCommunication>(10.minutes)

    private val cacheGarbageCollectScheduler = Scheduler {
        channelIdGuildIdCache.garbageCollect()
        rateLimitCache.garbageCollect()
    }

    private fun getLogChannel(): Snowflake {
        val channelId = System.getenv("DISCORD_OPHER_LOG_CHANNEL")

        if (channelId.isNullOrBlank()) {
            throw RuntimeException("DISCORD_OPHER_LOG_CHANNEL is not set")
        }

        return Snowflake(channelId)
    }

    suspend fun start(discordToken: String) {
        val kord = Kord(discordToken)

        kord.on<MessageCreateEvent> {
            // Ignore other bots, even ourselves. We only serve humans here!
            if (message.author?.isBot != false) return@on

            // We don't consider short messages
            if (message.attachments.isEmpty() && message.embeds.isEmpty() && message.content.length <= 20) {
                return@on
            }

            val author = message.author ?: return@on

            val guildId = channelIdGuildIdCache.getOrTryPut(message.channelId) {
                message.getGuildOrNull()?.id
            }

            if (guildId == null) {
                return@on
            }

            val memberId = MemberId(guildId, author.id)

            val recentCommunication = rateLimitCache.getOrPut(memberId) { RecentMemberCommunication() }
            recentCommunication.garbageCollect()

            val burstInfo = recentCommunication.reportMessage(message)

            logger().warn(burstInfo.channels.size.toString())
            if (burstInfo.channels.size >= AUTOBAN_BURST) {
                try {
                    message.getAuthorAsMember().edit {
                        this.communicationDisabledUntil = Clock.System.now().plus(2.days)
                    }

                    kord.rest.channel.createMessage(getLogChannel()) {
                        this.content = "Auto-Timeout triggered: <@${message.author?.id}>"
                    }
                } catch (e: Exception) {
                    kord.rest.channel.createMessage(getLogChannel()) {
                        this.content = "Spam detected but timeout wasn't possible: <@${message.author?.id}>"
                    }
                }

                burstInfo.tryPurgeMessages()
            }
        }

        cacheGarbageCollectScheduler.scheduleExecution(Every(5, TimeUnit.MINUTES))

        kord.login {
            @OptIn(PrivilegedIntent::class)
            intents += Intent.MessageContent
            @OptIn(PrivilegedIntent::class)
            intents += Intent.GuildMembers
        }
    }
}
