@file:OptIn(ExperimentalSerializationApi::class)

package wotw.io.messages.protobuf

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoNumber
import wotw.io.messages.protoBuf
import java.nio.ByteBuffer
import kotlin.experimental.xor

@Serializable
data class UdpPacket(
    @ProtoNumber(1) val udpId: Int?,
    @ProtoNumber(2) val encryptedPacket: ByteArray,
) {
    companion object {
        fun deserialize(bytes: ByteArray): UdpPacket {
            return protoBuf.decodeFromByteArray(serializer(), bytes)
        }

        fun fromPacketData(udpId: Int?, packetData: ByteArray, udpKey: ByteArray): UdpPacket {
            val byteBuffer = ByteBuffer.allocate(packetData.size)

            packetData.forEachIndexed { index, byte ->
                byteBuffer.put(index, byte.xor(udpKey[index % udpKey.size]))
            }

            return UdpPacket(udpId, byteBuffer.array())
        }

        inline fun <reified T : Any> serialize(obj: T): ByteArray {
            return protoBuf.encodeToByteArray(obj)
        }
    }

    fun getPacket(udpKey: ByteArray): Any? {
        val byteBuffer = ByteBuffer.allocate(encryptedPacket.size)

        encryptedPacket.forEachIndexed { index, byte ->
            byteBuffer.put(index, byte.xor(udpKey[index % udpKey.size]))
        }

        return Packet.deserialize(byteBuffer.array())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UdpPacket

        if (udpId != other.udpId) return false
        if (!encryptedPacket.contentEquals(other.encryptedPacket)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = udpId
        result = 31 * (result ?: -1) + encryptedPacket.contentHashCode()
        return result
    }
}
