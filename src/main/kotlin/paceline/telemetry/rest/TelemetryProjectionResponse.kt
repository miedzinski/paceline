package paceline.telemetry.rest

import paceline.telemetry.domain.TelemetryAvailability
import paceline.telemetry.domain.TelemetryProjection
import java.time.Instant

data class TelemetryProjectionResponse<T>(
    val availability: TelemetryAvailability,
    val sample: T?,
    val lastReceivedAt: Instant?,
)

fun <T, R> TelemetryProjection<T>.toResponse(mapSample: (T) -> R): TelemetryProjectionResponse<R> =
    TelemetryProjectionResponse(
        availability = availability,
        sample = sample?.let(mapSample),
        lastReceivedAt = lastReceivedAt,
    )
