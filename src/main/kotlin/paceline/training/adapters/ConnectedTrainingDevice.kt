package paceline.training.adapters

import org.springframework.stereotype.Component
import paceline.device.domain.ConnectionCoordinator
import paceline.device.domain.HeartRateSourceDescriptor
import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.HeartRateTelemetryListener
import paceline.device.ports.IndoorBikePowerControl
import paceline.device.ports.IndoorBikeTelemetryListener
import paceline.training.ports.TrainingDevice
import java.util.concurrent.CompletionStage

@Component
class ConnectedTrainingDevice(
    private val connectionCoordinator: ConnectionCoordinator,
) : TrainingDevice {
    override fun currentPowerControl(): IndoorBikePowerControl? = connectionCoordinator.currentPowerControl()

    override fun reconnectPowerControl(force: Boolean): CompletionStage<IndoorBikePowerControl?> =
        connectionCoordinator.reconnectPrimaryTrainingConnection(force)

    override fun hasTelemetryCapability(): Boolean = connectionCoordinator.hasPrimaryTrainingTelemetry()

    override fun currentTelemetry(): IndoorBikeTelemetry? = connectionCoordinator.currentTelemetry()

    override fun addTelemetryListener(listener: IndoorBikeTelemetryListener): AutoCloseable =
        connectionCoordinator.addTelemetryListener(listener)

    override fun heartRateSources(): List<HeartRateSourceDescriptor> = connectionCoordinator.heartRateSources()

    override fun currentHeartRate(sourceId: String): HeartRateTelemetry? = connectionCoordinator.currentHeartRate(sourceId)

    override fun addHeartRateListener(
        sourceId: String,
        listener: HeartRateTelemetryListener,
    ): AutoCloseable = connectionCoordinator.addHeartRateListener(sourceId, listener)
}
