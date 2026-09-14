package paceline.device.domain

import java.time.Instant

data class HeartRateTelemetry(
    val heartRateBpm: Int,
    val receivedAt: Instant,
) {
    init {
        require(heartRateBpm in 0..65_535) {
            "Heart rate must fit the standard Bluetooth heart-rate measurement field"
        }
    }
}
