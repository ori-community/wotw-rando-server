package wotw.io.messages

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.protobuf.ProtoBuf

//suspend inline fun <reified T: Any> SendChannel<Frame>.sendMessage(obj: T){
//    val binaryData = Packet.serialize(obj) ?: throw IOException("Cannot serialize object: $obj | ${obj::class}")
//    send(Frame.Binary(true, binaryData))
//}

//kotlinx.serialization suggests a central configured ProtoBuf instance
val protoBuf = ProtoBuf {
    encodeDefaults = true
    serializersModule = EmptySerializersModule
}
val json = Json {
    prettyPrint = true
    allowStructuredMapKeys = true
    encodeDefaults = true
}
val relaxedJson = Json {
    prettyPrint = true
    allowStructuredMapKeys = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}