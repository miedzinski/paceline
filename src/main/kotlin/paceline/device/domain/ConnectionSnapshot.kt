package paceline.device.domain

import paceline.telemetry.domain.CyclingTelemetry
import paceline.telemetry.domain.HeartRateTelemetry
import paceline.telemetry.domain.TelemetryProjection
import java.time.Instant

data class ConnectionSnapshot(
    val id: String,
    val device: DeviceAdvertisement,
    val phase: ConnectionPhase,
    val changedAt: Instant,
    val failure: ConnectionFailure?,
    val capabilities: Set<DeviceCapabilityType>,
    val telemetry: TelemetryProjection<CyclingTelemetry>,
    val heartRate: TelemetryProjection<HeartRateTelemetry>,
)
