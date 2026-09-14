package paceline.testsupport

import paceline.device.ports.IndoorBikePowerControl
import paceline.training.ports.TrainingDevice

class FakeTrainingDevice(
    var powerControl: IndoorBikePowerControl? = null,
) : TrainingDevice {
    override fun currentPowerControl(): IndoorBikePowerControl? = powerControl
}
