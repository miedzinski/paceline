package paceline.device.domain

data class HeartRateSourceDescriptor(
    val id: String,
    val device: DeviceAdvertisement,
    val state: ConnectionPhase,
)

enum class DeviceCapabilityType {
    INDOOR_BIKE_TELEMETRY,
    ERG_POWER_CONTROL,
    HEART_RATE,
}

data class ConnectedDeviceSnapshot(
    val id: String,
    val state: ConnectionState,
    val capabilities: Set<DeviceCapabilityType>,
    val telemetry: IndoorBikeTelemetry? = null,
    val heartRate: HeartRateTelemetry? = null,
)
