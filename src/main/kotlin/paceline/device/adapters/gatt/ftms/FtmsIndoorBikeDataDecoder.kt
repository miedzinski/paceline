package paceline.device.adapters.gatt.ftms

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FtmsIndoorBikeData(
    val instantaneousSpeedKph: Double? = null,
    val averageSpeedKph: Double? = null,
    val instantaneousCadenceRpm: Double? = null,
    val averageCadenceRpm: Double? = null,
    val totalDistanceMeters: Double? = null,
    val instantaneousPowerWatts: Int? = null,
    val averagePowerWatts: Int? = null,
)

class FtmsDataException(
    message: String,
) : IllegalArgumentException(message)

class FtmsIndoorBikeDataDecoder {
    fun decode(value: ByteArray): FtmsIndoorBikeData {
        val reader = LittleEndianReader(value)
        val flags = reader.readUnsignedShort()

        val instantaneousSpeedKph =
            if (flags and FLAG_MORE_DATA == 0) {
                reader.readUnsignedShort("instantaneous speed") / 100.0
            } else {
                null
            }
        val averageSpeedKph =
            if (flags has FLAG_AVERAGE_SPEED) {
                reader.readUnsignedShort("average speed") / 100.0
            } else {
                null
            }
        val instantaneousCadenceRpm =
            if (flags has FLAG_INSTANTANEOUS_CADENCE) {
                reader.readUnsignedShort("instantaneous cadence") / 2.0
            } else {
                null
            }
        val averageCadenceRpm =
            if (flags has FLAG_AVERAGE_CADENCE) {
                reader.readUnsignedShort("average cadence") / 2.0
            } else {
                null
            }

        val totalDistanceMeters =
            if (flags has FLAG_TOTAL_DISTANCE) {
                reader.readUnsignedInt24("total distance").toDouble()
            } else {
                null
            }
        if (flags has FLAG_RESISTANCE_LEVEL) {
            reader.skip(2, "resistance level")
        }
        val instantaneousPowerWatts =
            if (flags has FLAG_INSTANTANEOUS_POWER) {
                reader.readSignedShort("instantaneous power")
            } else {
                null
            }
        val averagePowerWatts =
            if (flags has FLAG_AVERAGE_POWER) {
                reader.readSignedShort("average power")
            } else {
                null
            }

        return FtmsIndoorBikeData(
            instantaneousSpeedKph = instantaneousSpeedKph,
            averageSpeedKph = averageSpeedKph,
            instantaneousCadenceRpm = instantaneousCadenceRpm,
            averageCadenceRpm = averageCadenceRpm,
            totalDistanceMeters = totalDistanceMeters,
            instantaneousPowerWatts = instantaneousPowerWatts,
            averagePowerWatts = averagePowerWatts,
        )
    }

    private class LittleEndianReader(
        private val value: ByteArray,
    ) {
        private var offset = 0

        fun readUnsignedShort(field: String = "value"): Int {
            requireAvailable(2, field)
            val result =
                ByteBuffer
                    .wrap(value, offset, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short
                    .toInt() and 0xffff
            offset += 2
            return result
        }

        fun readSignedShort(field: String): Int {
            requireAvailable(2, field)
            val result =
                ByteBuffer
                    .wrap(value, offset, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short
                    .toInt()
            offset += 2
            return result
        }

        fun readUnsignedInt24(field: String): Int {
            requireAvailable(3, field)
            val result =
                (value[offset].toInt() and 0xff) or
                    ((value[offset + 1].toInt() and 0xff) shl 8) or
                    ((value[offset + 2].toInt() and 0xff) shl 16)
            offset += 3
            return result
        }

        fun skip(
            count: Int,
            field: String,
        ) {
            requireAvailable(count, field)
            offset += count
        }

        private fun requireAvailable(
            count: Int,
            field: String,
        ) {
            if (offset + count > value.size) {
                throw FtmsDataException(
                    "FTMS Indoor Bike Data is truncated while reading $field: " +
                        "needed $count bytes at offset $offset, received ${value.size} bytes",
                )
            }
        }
    }

    private infix fun Int.has(flag: Int): Boolean = this and flag != 0

    private companion object {
        const val FLAG_MORE_DATA = 1 shl 0
        const val FLAG_AVERAGE_SPEED = 1 shl 1
        const val FLAG_INSTANTANEOUS_CADENCE = 1 shl 2
        const val FLAG_AVERAGE_CADENCE = 1 shl 3
        const val FLAG_TOTAL_DISTANCE = 1 shl 4
        const val FLAG_RESISTANCE_LEVEL = 1 shl 5
        const val FLAG_INSTANTANEOUS_POWER = 1 shl 6
        const val FLAG_AVERAGE_POWER = 1 shl 7
    }
}
