package paceline.device.domain

import java.time.Instant

data class CyclingTelemetry(
    val powerWatts: Int? = null,
    val cadenceRpm: Double? = null,
    val speedKph: Double? = null,
    val distanceMeters: Double? = null,
    val receivedAt: Instant,
)
