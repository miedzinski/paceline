package paceline.training.ports

import paceline.telemetry.domain.CyclingTelemetry
import paceline.telemetry.domain.HeartRateTelemetry
import paceline.training.domain.RideEquipmentSelection
import paceline.training.domain.RideSourceDescriptor
import java.util.concurrent.CompletionStage

interface CyclingTelemetrySource {
    val sourceId: String

    fun current(): CyclingTelemetry?

    fun subscribe(listener: (CyclingTelemetry) -> Unit): AutoCloseable
}

interface HeartRateSource {
    val sourceId: String

    fun current(): HeartRateTelemetry?

    fun subscribe(listener: (HeartRateTelemetry) -> Unit): AutoCloseable
}

interface TrainerControl {
    fun requestControl()

    fun setTargetPower(powerWatts: Int)

    fun setFreeRide()

    fun releaseResistance()

    fun stop()

    fun pause()

    fun startOrResume()
}

interface TrainerControlConnection {
    val sourceId: String

    fun control(): TrainerControl?

    fun reconnect(force: Boolean = false): CompletionStage<TrainerControlConnection?>
}

data class ConnectedRideSource(
    val descriptor: RideSourceDescriptor,
    val cyclingTelemetry: CyclingTelemetrySource?,
    val heartRate: HeartRateSource?,
    val trainerControlConnection: TrainerControlConnection?,
) {
    val id: String
        get() = descriptor.id
}

data class SelectedRideEquipment(
    val selection: RideEquipmentSelection,
    val trainerControlConnection: TrainerControlConnection,
    val controlTelemetry: CyclingTelemetrySource?,
    val powerTelemetry: CyclingTelemetrySource?,
    val cadenceTelemetry: CyclingTelemetrySource?,
    val heartRate: HeartRateSource?,
)

interface RideSourceCatalog {
    fun sources(): List<RideSourceDescriptor>

    fun source(sourceId: String): ConnectedRideSource?
}
