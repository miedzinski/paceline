package paceline.training.adapters

import org.springframework.stereotype.Component
import paceline.device.domain.ConnectionCoordinator
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.IndoorBikePowerControl
import paceline.training.ports.TrainingDevice

@Component
class ConnectedTrainingDevice(
    private val connectionCoordinator: ConnectionCoordinator,
) : TrainingDevice {
    override fun currentPowerControl(): IndoorBikePowerControl? = connectionCoordinator.currentPowerControl()

    override fun currentTelemetry(): IndoorBikeTelemetry? = connectionCoordinator.currentTelemetry()
}
