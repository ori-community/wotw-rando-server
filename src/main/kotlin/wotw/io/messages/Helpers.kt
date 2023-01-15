package wotw.io.messages

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.*

//suspend inline fun <reified T: Any> SendChannel<Frame>.sendMessage(obj: T){
//    val binaryData = Packet.serialize(obj) ?: throw IOException("Cannot serialize object: $obj | ${obj::class}")
//    send(Frame.Binary(true, binaryData))
//}

//kotlinx.serialization suggests a central configured ProtoBuf instance

object ByteArrayAsBase64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ByteArrayAsBase64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}


val jsonModule = SerializersModule {
    contextual(ByteArrayAsBase64Serializer)
}

val protoBuf = ProtoBuf {
    encodeDefaults = true
}

val json = Json {
    prettyPrint = true
    allowStructuredMapKeys = true
    encodeDefaults = true
    serializersModule = jsonModule
}

val relaxedJson = Json {
    prettyPrint = true
    allowStructuredMapKeys = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    serializersModule = jsonModule
}