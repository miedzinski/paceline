package paceline.training.ports

import paceline.device.domain.HeartRateSourceDescriptor
import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.HeartRateTelemetryListener
import paceline.device.ports.IndoorBikePowerControl
import paceline.device.ports.IndoorBikeTelemetryListener

interface TrainingDevice {
    fun currentPowerControl(): IndoorBikePowerControl?

    fun currentTelemetry(): IndoorBikeTelemetry? = null

    fun addTelemetryListener(listener: IndoorBikeTelemetryListener): AutoCloseable = AutoCloseable { }

    fun heartRateSources(): List<HeartRateSourceDescriptor> = emptyList()

    fun currentHeartRate(sourceId: String): HeartRateTelemetry? = null

    fun addHeartRateListener(
        sourceId: String,
        listener: HeartRateTelemetryListener,
    ): AutoCloseable = AutoCloseable { }
}
