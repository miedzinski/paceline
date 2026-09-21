package paceline.training.adapters

import org.springframework.stereotype.Component
import paceline.device.domain.ConnectionCoordinator
import paceline.device.domain.ConnectionSnapshot
import paceline.device.domain.DeviceCapabilityType
import paceline.device.ports.CyclingTelemetryListener
import paceline.device.ports.HeartRateTelemetryListener
import paceline.telemetry.domain.CyclingTelemetry
import paceline.telemetry.domain.HeartRateTelemetry
import paceline.training.domain.RideSourceDescriptor
import paceline.training.domain.toRideSourceDescriptor
import paceline.training.ports.ConnectedRideSource
import paceline.training.ports.CyclingTelemetrySource
import paceline.training.ports.HeartRateSource
import paceline.training.ports.RideSourceCatalog
import paceline.training.ports.TrainerControl
import paceline.training.ports.TrainerControlConnection
import java.util.concurrent.CompletionStage
import paceline.device.ports.TrainerControl as DeviceTrainerControl

@Component
class ConnectedRideSourceCatalog(
    private val connectionCoordinator: ConnectionCoordinator,
) : RideSourceCatalog {
    override fun sources(): List<RideSourceDescriptor> =
        connectionCoordinator
            .connectionSnapshots()
            .map(ConnectionSnapshot::toRideSourceDescriptor)

    override fun source(sourceId: String): ConnectedRideSource? {
        val snapshot = connectionCoordinator.connectionSnapshots().firstOrNull { it.id == sourceId } ?: return null
        val descriptor = snapshot.toRideSourceDescriptor()
        val cyclingTelemetry =
            snapshot
                .takeIf {
                    it.capabilities.any { capability ->
                        capability == DeviceCapabilityType.CYCLING_TELEMETRY ||
                            capability == DeviceCapabilityType.POWER_TELEMETRY ||
                            capability == DeviceCapabilityType.CADENCE_TELEMETRY
                    }
                }?.let { ConnectedCyclingTelemetrySource(connectionCoordinator, sourceId) }
        val trainer =
            snapshot
                .takeIf { DeviceCapabilityType.ERG_POWER_CONTROL in it.capabilities }
                ?.let { ConnectedTrainerControlConnection(connectionCoordinator, sourceId) }
        val heartRate =
            snapshot
                .takeIf { DeviceCapabilityType.HEART_RATE in it.capabilities }
                ?.let { ConnectedHeartRateSource(connectionCoordinator, sourceId) }
        return ConnectedRideSource(
            descriptor = descriptor,
            cyclingTelemetry = cyclingTelemetry,
            heartRate = heartRate,
            trainerControlConnection = trainer,
        )
    }
}

private class ConnectedCyclingTelemetrySource(
    private val connectionCoordinator: ConnectionCoordinator,
    override val sourceId: String,
) : CyclingTelemetrySource {
    override fun current(): CyclingTelemetry? = connectionCoordinator.currentCyclingTelemetry(sourceId)

    override fun subscribe(listener: (CyclingTelemetry) -> Unit): AutoCloseable =
        connectionCoordinator.addCyclingTelemetryListener(sourceId, CyclingTelemetryListener(listener))
}

private class ConnectedHeartRateSource(
    private val connectionCoordinator: ConnectionCoordinator,
    override val sourceId: String,
) : HeartRateSource {
    override fun current(): HeartRateTelemetry? = connectionCoordinator.currentHeartRate(sourceId)

    override fun subscribe(listener: (HeartRateTelemetry) -> Unit): AutoCloseable =
        connectionCoordinator.addHeartRateListener(sourceId, HeartRateTelemetryListener(listener))
}

private class ConnectedTrainerControlConnection(
    private val connectionCoordinator: ConnectionCoordinator,
    override val sourceId: String,
) : TrainerControlConnection {
    override fun control(): TrainerControl? = connectionCoordinator.currentTrainerControl(sourceId)?.let(::ConnectedTrainerControl)

    override fun reconnect(force: Boolean): CompletionStage<TrainerControlConnection?> =
        connectionCoordinator
            .reconnectConnection(sourceId, force)
            .thenApply { session -> session?.let { this } }
}

private class ConnectedTrainerControl(
    private val delegate: DeviceTrainerControl,
) : TrainerControl {
    override fun requestControl() = delegate.requestControl()

    override fun setTargetPower(powerWatts: Int) = delegate.setTargetPower(powerWatts)

    override fun setFreeRide() = delegate.setFreeRide()
}
