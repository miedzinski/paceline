package paceline.device.domain

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
