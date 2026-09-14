package paceline.device.adapters.gatt.heartrate

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HeartRateMeasurement(
    val heartRateBpm: Int,
)

class HeartRateDataException(
    message: String,
) : IllegalArgumentException(message)

class HeartRateMeasurementDecoder {
    fun decode(value: ByteArray): HeartRateMeasurement {
        val reader = LittleEndianReader(value)
        val flags = reader.readUnsignedByte("flags")
        val heartRateBpm =
            if (flags and FLAG_UINT16_FORMAT != 0) {
                reader.readUnsignedShort("heart rate")
            } else {
                reader.readUnsignedByte("heart rate")
            }

        if (flags and FLAG_ENERGY_EXPENDED != 0) {
            reader.skip(2, "energy expended")
        }
        if (flags and FLAG_RR_INTERVAL != 0) {
            while (reader.remaining > 0) {
                reader.skip(2, "RR interval")
            }
        }

        return HeartRateMeasurement(heartRateBpm)
    }

    private class LittleEndianReader(
        private val value: ByteArray,
    ) {
        private var offset = 0

        val remaining: Int
            get() = value.size - offset

        fun readUnsignedByte(field: String): Int {
            requireAvailable(1, field)
            return value[offset++].toInt() and 0xff
        }

        fun readUnsignedShort(field: String): Int {
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
                throw HeartRateDataException(
                    "Bluetooth Heart Rate Measurement is truncated while reading $field: " +
                        "needed $count bytes at offset $offset, received ${value.size} bytes",
                )
            }
        }
    }

    private companion object {
        const val FLAG_UINT16_FORMAT = 1 shl 0
        const val FLAG_ENERGY_EXPENDED = 1 shl 3
        const val FLAG_RR_INTERVAL = 1 shl 4
    }
}
