package paceline.testsupport

import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.IndoorBikePowerControl
import paceline.training.ports.TrainingDevice

class FakeTrainingDevice(
    var powerControl: IndoorBikePowerControl? = null,
    var telemetry: IndoorBikeTelemetry? = null,
) : TrainingDevice {
    override fun currentPowerControl(): IndoorBikePowerControl? = powerControl

    override fun currentTelemetry(): IndoorBikeTelemetry? = telemetry
}
