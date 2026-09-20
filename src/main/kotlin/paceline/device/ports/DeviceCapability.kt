package paceline.device.ports

import paceline.device.domain.CyclingMeasurement
import paceline.device.domain.CyclingTelemetry
import paceline.device.domain.HeartRateTelemetry

interface DeviceCapability

fun interface CyclingTelemetryListener {
    fun onTelemetry(telemetry: CyclingTelemetry)
}

interface CyclingTelemetrySource : DeviceCapability {
    val measurements: Set<CyclingMeasurement>

    fun addTelemetryListener(listener: CyclingTelemetryListener): AutoCloseable = AutoCloseable { }

    fun latestTelemetry(): CyclingTelemetry? = null
}

fun interface HeartRateTelemetryListener {
    fun onHeartRate(telemetry: HeartRateTelemetry)
}

interface HeartRateTelemetrySource : DeviceCapability {
    fun addHeartRateListener(listener: HeartRateTelemetryListener): AutoCloseable = AutoCloseable { }

    fun latestHeartRate(): HeartRateTelemetry? = null
}

interface TrainerControl :
    DeviceCapability,
    AutoCloseable {
    fun requestControl()

    fun setTargetPower(powerWatts: Int)

    fun setFreeRide()

    override fun close() = Unit
}
