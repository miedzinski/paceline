package paceline.device.domain

import java.time.Instant

data class IndoorBikeTelemetry(
    val powerWatts: Int? = null,
    val cadenceRpm: Double? = null,
    val speedKph: Double? = null,
    val receivedAt: Instant,
)
