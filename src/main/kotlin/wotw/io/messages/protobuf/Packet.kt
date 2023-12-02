package wotw.io.messages.protobuf

import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoNumber
import wotw.io.messages.protoBuf
import wotw.util.BiMap
import wotw.util.biMapOf
import kotlin.reflect.KType
import kotlin.reflect.typeOf

typealias PacketId = Int

@Serializable
data class Packet(
    @ProtoNumber(1) val id: PacketId,
    @ProtoNumber(2) val message: ByteArray = ByteArray(0),
) {

    fun deserializeMessage(): Any? {
        val type = ids[id] ?: return null
        return protoBuf.decodeFromByteArray(serializer(type), message)
    }

    //Auto-Generated equals/hash-code due to array type
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as Packet

        if (id != other.id) return false
        if (!message.contentEquals(other.message)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.toInt()
        result = 31 * result + message.contentHashCode()
        return result
    }

    companion object {
        val ids: BiMap<PacketId, KType> = biMapOf(
            1 to typeOf<SyncBoardMessage>(),
            2 to typeOf<RequestUpdatesMessage>(),
            3 to typeOf<UberStateUpdateMessage>(),
            4 to typeOf<SyncBingoUniversesMessage>(),
            5 to typeOf<InitGameSyncMessage>(),
         // 6    Legacy PrintTextMessage, deprecated
            7 to typeOf<UberStateBatchUpdateMessage>(),
            8 to typeOf<MultiverseInfoMessage>(),
            9 to typeOf<AuthenticateMessage>(),
            10 to typeOf<PlayerPositionMessage>(),
            11 to typeOf<UpdatePlayerPositionMessage>(),
            12 to typeOf<AuthenticatedMessage>(),
            13 to typeOf<PrintTextMessage>(),
            14 to typeOf<PrintPickupMessage>(),
            15 to typeOf<RequestSeedMessage>(),
            16 to typeOf<SetSeedMessage>(),
            17 to typeOf<PlayerUseCatchingAbilityMessage>(),
            18 to typeOf<PlayerUsedCatchingAbilityMessage>(),
            19 to typeOf<PlayerCaughtMessage>(),
            20 to typeOf<SetVisibilityMessage>(),
            21 to typeOf<UpdatePlayerWorldPositionMessage>(),
            22 to typeOf<UpdatePlayerMapPositionMessage>(),
            23 to typeOf<ResourceRequestMessage>(),
            24 to typeOf<PlayerTeleportMessage>(),
            25 to typeOf<ReportInGameTimeMessage>(),
            26 to typeOf<SetBlockStartingNewGameMessage>(),
            27 to typeOf<ReportPlayerRaceReadyMessage>(),

            100 to typeOf<TrackerUpdate>(),
            101 to typeOf<ResetTracker>(),
            102 to typeOf<TrackerFlagsUpdate>(),
            103 to typeOf<RequestFullUpdate>(),
            104 to typeOf<SetTrackerEndpointId>(),
            105 to typeOf<TrackerTimerStateUpdate>(),
        )

        fun deserialize(bytes: ByteArray): Any? {
            return protoBuf.decodeFromByteArray(serializer(), bytes).deserializeMessage()
        }

        inline fun <reified T : Any> from(obj: T): Packet {
            val id = ids.inverse[typeOf<T>()]
                ?: throw SerializationException("No packet-id known for ${typeOf<T>()}, known values: ${ids.inverse.keys}")
            val serializer = serializer(typeOf<T>())
            return Packet(id, protoBuf.encodeToByteArray(serializer, obj))
        }

        inline fun <reified T : Any> serialize(obj: T): ByteArray? {
            return from(obj).let {
                protoBuf.encodeToByteArray(it)
            }
        }
    }
}
