package paceline.training.ports

import paceline.device.ports.IndoorBikePowerControl

interface TrainingDevice {
    fun currentPowerControl(): IndoorBikePowerControl?
}
