package wotw.server.game

import kotlinx.serialization.json.*
import wotw.io.messages.protobuf.MoodGuid
import wotw.io.messages.relaxedJson
import wotw.server.util.logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WotwSaveFileReader(
    private val data: ByteBuffer
) {
    data class SaveFileData(
        val version: Int,
        val inGameTime: Float,
        val asyncLoadingTime: Float,
        val saveFileGuid: MoodGuid,
    )

    companion object {
        const val SAVE_META_FILE_MAGIC = 1
        const val SAVE_META_FILE_VERSION = 1

        enum class SaveMetaSlot(val value: Int) {
            CheckpointGameStats(0),
            SaveFileGameStats(1),
            SeedMetaData(2),
        }
    }

    init {
        data.order(ByteOrder.LITTLE_ENDIAN)
    }

    fun parse(): SaveFileData? {
        data.rewind()

        val saveFileMagic = data.getInt()

        if (saveFileMagic != SAVE_META_FILE_MAGIC) {
            return null
        }

        val saveFileVersion = data.getInt()

        if (saveFileVersion != SAVE_META_FILE_VERSION) {
            return null
        }

        val saveGuid = MoodGuid(
            data.getInt(),
            data.getInt(),
            data.getInt(),
            data.getInt(),
        )

        val slotCount = data.getInt()
        for (i in 0..<slotCount) {
            val slotId = data.getInt()
            val slotLength = data.getInt().toUInt()

            if (slotLength > Int.MAX_VALUE.toUInt()) {
                // Cannot read this...
                logger().error("WotwSaveFileReader: Save slot size exceeded maximum supported length")
                return null
            }

            val slotData = ByteArray(slotLength.toInt())
            data.get(slotData)

            if (slotId != SaveMetaSlot.SaveFileGameStats.value) {
                continue
            }

            // Skip JSON length                         ↓
            val jsonString = String(slotData.sliceArray(4..<slotLength.toInt()), Charsets.UTF_8)
            val json = relaxedJson.parseToJsonElement(jsonString)

            val inGameTimeElement = json.jsonObject["in_game_time"]
            val asyncLoadingTimesElement = json.jsonObject["async_loading_times"]

            if (inGameTimeElement == null || inGameTimeElement !is JsonPrimitive) {
                logger().error("WotwSaveFileReader: Tried to read in-game-time but element was null or not a primitive")
                return null
            }

            val inGameTime = inGameTimeElement.jsonPrimitive.floatOrNull ?: return null

            if (asyncLoadingTimesElement == null || asyncLoadingTimesElement !is JsonArray) {
                logger().error("WotwSaveFileReader: Tried to read async loading times but element was null or not an array")
                return null
            }

            var totalAsyncLoadingTime = 0f

            asyncLoadingTimesElement.jsonArray.forEach { element ->
                if (element !is JsonArray) {
                    logger().error("WotwSaveFileReader: Tried to read async loading time element but element was not an array")
                    return null
                }

                val asyncLoadingTime = element[1].jsonPrimitive.floatOrNull ?: run {
                    logger().error("WotwSaveFileReader: Tried to read async loading time element but 2nd item in array was not a float")
                    return null
                }
                
                totalAsyncLoadingTime += asyncLoadingTime
            }

            return SaveFileData(
                saveFileVersion,
                inGameTime,
                totalAsyncLoadingTime,
                saveGuid,
            )
        }

        return null
    }
}
