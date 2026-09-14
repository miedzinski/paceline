package paceline.device.adapters.gatt.heartrate

import paceline.device.domain.HeartRateTelemetry
import java.time.Instant
import java.util.UUID

object HeartRateUuid {
    val HEART_RATE_SERVICE: UUID = uuid16(0x180d)
    val HEART_RATE_MEASUREMENT: UUID = uuid16(0x2a37)

    private fun uuid16(value: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))
}

fun HeartRateMeasurement.toHeartRateTelemetry(receivedAt: Instant): HeartRateTelemetry =
    HeartRateTelemetry(
        heartRateBpm = heartRateBpm,
        receivedAt = receivedAt,
    )
