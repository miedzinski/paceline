package paceline.device.ports

import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry

interface DeviceCapability

fun interface IndoorBikeTelemetryListener {
    fun onTelemetry(telemetry: IndoorBikeTelemetry)
}

interface IndoorBikeTelemetrySource : DeviceCapability {
    fun addTelemetryListener(listener: IndoorBikeTelemetryListener): AutoCloseable = AutoCloseable { }

    fun latestTelemetry(): IndoorBikeTelemetry? = null
}

fun interface HeartRateTelemetryListener {
    fun onHeartRate(telemetry: HeartRateTelemetry)
}

interface HeartRateTelemetrySource : DeviceCapability {
    fun addHeartRateListener(listener: HeartRateTelemetryListener): AutoCloseable = AutoCloseable { }

    fun latestHeartRate(): HeartRateTelemetry? = null
}

interface IndoorBikePowerControl :
    DeviceCapability,
    AutoCloseable {
    fun requestControl()

    fun setTargetPower(powerWatts: Int)

    override fun close() = Unit
}
