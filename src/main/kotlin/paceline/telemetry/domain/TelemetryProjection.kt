package paceline.telemetry.domain

import java.time.Duration
import java.time.Instant

enum class TelemetryAvailability {
    CURRENT,
    UNAVAILABLE,
    INTERRUPTED,
}

data class TelemetryProjection<T>(
    val availability: TelemetryAvailability,
    val sample: T?,
    val lastReceivedAt: Instant?,
) {
    init {
        when (availability) {
            TelemetryAvailability.CURRENT -> {
                require(sample != null) {
                    "Current telemetry must contain a sample"
                }
            }

            TelemetryAvailability.UNAVAILABLE -> {
                require(sample == null && lastReceivedAt == null) {
                    "Unavailable telemetry must not contain a sample or a previous timestamp"
                }
            }

            TelemetryAvailability.INTERRUPTED -> {
                require(sample == null && lastReceivedAt != null) {
                    "Interrupted telemetry must contain the last received timestamp without a live sample"
                }
            }
        }
    }

    companion object {
        fun <T> unavailable(): TelemetryProjection<T> =
            TelemetryProjection(
                availability = TelemetryAvailability.UNAVAILABLE,
                sample = null,
                lastReceivedAt = null,
            )

        fun <T> current(
            sample: T,
            receivedAt: Instant,
        ): TelemetryProjection<T> =
            TelemetryProjection(
                availability = TelemetryAvailability.CURRENT,
                sample = sample,
                lastReceivedAt = receivedAt,
            )

        fun <T> interrupted(lastReceivedAt: Instant): TelemetryProjection<T> =
            TelemetryProjection(
                availability = TelemetryAvailability.INTERRUPTED,
                sample = null,
                lastReceivedAt = lastReceivedAt,
            )
    }
}

fun isTelemetryFresh(
    receivedAt: Instant,
    now: Instant,
    freshness: Duration,
): Boolean =
    !receivedAt.isAfter(now) &&
        Duration.between(receivedAt, now) <= freshness

fun <T> projectTelemetry(
    sample: T?,
    connected: Boolean,
    now: Instant,
    freshness: Duration,
    lastReceivedAt: Instant? = null,
    receivedAt: (T) -> Instant,
): TelemetryProjection<T> {
    val observedAt = sample?.let(receivedAt) ?: lastReceivedAt
    if (observedAt == null) {
        return TelemetryProjection.unavailable()
    }
    if (!connected || sample == null || !isTelemetryFresh(observedAt, now, freshness)) {
        return TelemetryProjection.interrupted(observedAt)
    }
    return TelemetryProjection.current(sample, observedAt)
}
