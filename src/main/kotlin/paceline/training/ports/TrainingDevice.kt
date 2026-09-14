package paceline.training.ports

import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.IndoorBikePowerControl

interface TrainingDevice {
    fun currentPowerControl(): IndoorBikePowerControl?

    fun currentTelemetry(): IndoorBikeTelemetry? = null
}
