package paceline.device.adapters.gatt.cyclingpower

data class CyclingPowerMeasurement(
    val instantaneousPowerWatts: Int,
)

class CyclingPowerDataException(
    message: String,
) : IllegalArgumentException(message)

class CyclingPowerMeasurementDecoder {
    fun decode(value: ByteArray): CyclingPowerMeasurement {
        if (value.size < MINIMUM_LENGTH) {
            throw CyclingPowerDataException(
                "Cycling Power Measurement is truncated: needed $MINIMUM_LENGTH bytes, received ${value.size}",
            )
        }

        val power =
            (value[2].toInt() and 0xff) or
                ((value[3].toInt() and 0xff) shl 8)
        return CyclingPowerMeasurement(instantaneousPowerWatts = power.toShort().toInt())
    }

    private companion object {
        const val MINIMUM_LENGTH = 4
    }
}
