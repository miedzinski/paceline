package paceline.training.ports

import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.IndoorBikePowerControl
import paceline.device.ports.IndoorBikeTelemetryListener

interface TrainingDevice {
    fun currentPowerControl(): IndoorBikePowerControl?

    fun currentTelemetry(): IndoorBikeTelemetry? = null

    fun addTelemetryListener(listener: IndoorBikeTelemetryListener): AutoCloseable = AutoCloseable { }
}
