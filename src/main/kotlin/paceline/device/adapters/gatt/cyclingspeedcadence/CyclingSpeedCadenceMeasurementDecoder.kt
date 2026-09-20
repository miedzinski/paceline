package paceline.device.adapters.gatt.cyclingspeedcadence

data class CyclingSpeedCadenceMeasurement(
    val cumulativeWheelRevolutions: Long? = null,
    val lastWheelEventTime: Int? = null,
    val cumulativeCrankRevolutions: Int? = null,
    val lastCrankEventTime: Int? = null,
)

class CyclingSpeedCadenceDataException(
    message: String,
) : IllegalArgumentException(message)

class CyclingSpeedCadenceMeasurementDecoder {
    fun decode(value: ByteArray): CyclingSpeedCadenceMeasurement {
        val reader = LittleEndianReader(value)
        val flags = reader.readUnsignedByte("flags")
        val wheelDataPresent = flags and FLAG_WHEEL_REVOLUTION_DATA != 0
        val crankDataPresent = flags and FLAG_CRANK_REVOLUTION_DATA != 0
        val cumulativeWheelRevolutions =
            if (wheelDataPresent) {
                reader.readUnsignedInt("cumulative wheel revolutions")
            } else {
                null
            }
        val lastWheelEventTime =
            if (wheelDataPresent) {
                reader.readUnsignedShort("last wheel event time")
            } else {
                null
            }
        val cumulativeCrankRevolutions =
            if (crankDataPresent) {
                reader.readUnsignedShort("cumulative crank revolutions")
            } else {
                null
            }
        val lastCrankEventTime =
            if (crankDataPresent) {
                reader.readUnsignedShort("last crank event time")
            } else {
                null
            }
        return CyclingSpeedCadenceMeasurement(
            cumulativeWheelRevolutions = cumulativeWheelRevolutions,
            lastWheelEventTime = lastWheelEventTime,
            cumulativeCrankRevolutions = cumulativeCrankRevolutions,
            lastCrankEventTime = lastCrankEventTime,
        )
    }

    private class LittleEndianReader(
        private val value: ByteArray,
    ) {
        private var offset = 0

        fun readUnsignedByte(field: String): Int {
            requireAvailable(1, field)
            return value[offset++].toInt() and 0xff
        }

        fun readUnsignedShort(field: String): Int {
            requireAvailable(2, field)
            val result =
                (value[offset].toInt() and 0xff) or
                    ((value[offset + 1].toInt() and 0xff) shl 8)
            offset += 2
            return result
        }

        fun readUnsignedInt(field: String): Long {
            requireAvailable(4, field)
            val result =
                (value[offset].toLong() and 0xff) or
                    ((value[offset + 1].toLong() and 0xff) shl 8) or
                    ((value[offset + 2].toLong() and 0xff) shl 16) or
                    ((value[offset + 3].toLong() and 0xff) shl 24)
            offset += 4
            return result
        }

        private fun requireAvailable(
            count: Int,
            field: String,
        ) {
            if (offset + count > value.size) {
                throw CyclingSpeedCadenceDataException(
                    "Cycling Speed and Cadence Measurement is truncated while reading $field: " +
                        "needed $count bytes at offset $offset, received ${value.size} bytes",
                )
            }
        }
    }

    private companion object {
        const val FLAG_WHEEL_REVOLUTION_DATA = 1 shl 0
        const val FLAG_CRANK_REVOLUTION_DATA = 1 shl 1
    }
}
