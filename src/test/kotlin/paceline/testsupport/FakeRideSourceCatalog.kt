package paceline.testsupport

import paceline.device.domain.ConnectionPhase
import paceline.device.domain.CyclingTelemetry
import paceline.device.domain.HeartRateTelemetry
import paceline.training.domain.RideEquipmentSelection
import paceline.training.domain.RideRole
import paceline.training.domain.RideSourceCapability
import paceline.training.domain.RideSourceDescriptor
import paceline.training.ports.ConnectedRideSource
import paceline.training.ports.CyclingTelemetrySource
import paceline.training.ports.HeartRateSource
import paceline.training.ports.RideSourceCatalog
import paceline.training.ports.SelectedRideEquipment
import paceline.training.ports.TrainerControlConnection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import paceline.device.ports.TrainerControl as DeviceTrainerControl
import paceline.training.ports.TrainerControl as RideTrainerControl

class FakeRideSourceCatalog(
    var powerControl: DeviceTrainerControl? = null,
    var telemetry: CyclingTelemetry? = null,
    var availableHeartRateSources: List<RideSourceDescriptor> = emptyList(),
    var telemetryCapabilityAvailable: Boolean = false,
    var availableRideSources: List<RideSourceDescriptor> = emptyList(),
) : RideSourceCatalog {
    var reconnectPowerControlResult: DeviceTrainerControl? = null
    var lastReconnectForce: Boolean? = null
    var reconnectCalls = 0
        private set
    val sourcePowerControls = mutableMapOf<String, DeviceTrainerControl?>()
    val sourceReconnectResults = mutableMapOf<String, DeviceTrainerControl?>()
    val sourceReconnectCalls = mutableListOf<String>()

    private val sourceTelemetries = mutableMapOf<String, CyclingTelemetry>()
    private val heartRates = mutableMapOf<String, HeartRateTelemetry>()
    private val cyclingSources = mutableMapOf<String, FakeCyclingTelemetrySource>()
    private val heartRateSources = mutableMapOf<String, FakeHeartRateSource>()
    private val trainerConnections = mutableMapOf<String, FakeTrainerControlConnection>()
    private val rideControls = mutableMapOf<String, Pair<DeviceTrainerControl, RideTrainerControl>>()

    override fun sources(): List<RideSourceDescriptor> {
        val rideSources =
            if (availableRideSources.isEmpty() && powerControl != null) {
                listOf(
                    RideSourceDescriptor(
                        id = DEFAULT_SOURCE_ID,
                        state = ConnectionPhase.CONNECTED,
                        capabilities =
                            setOf(
                                RideSourceCapability.RESISTANCE_CONTROL,
                                RideSourceCapability.POWER,
                                RideSourceCapability.CADENCE,
                            ),
                    ),
                )
            } else {
                availableRideSources
            }
        return (rideSources + availableHeartRateSources)
            .groupBy(RideSourceDescriptor::id)
            .map { (id, descriptors) ->
                RideSourceDescriptor(
                    id = id,
                    state =
                        descriptors.firstOrNull { it.state == ConnectionPhase.CONNECTED }?.state
                            ?: descriptors.first().state,
                    capabilities = descriptors.flatMap { it.capabilities }.toSet(),
                    device = descriptors.firstNotNullOfOrNull(RideSourceDescriptor::device),
                )
            }
    }

    override fun source(sourceId: String): ConnectedRideSource? {
        val descriptor = sources().firstOrNull { it.id == sourceId } ?: return null
        val cyclingTelemetry =
            descriptor
                .takeIf { it.supports(RideRole.POWER) || it.supports(RideRole.CADENCE) }
                ?.let { cyclingSources.getOrPut(sourceId) { FakeCyclingTelemetrySource(sourceId) } }
        val heartRate =
            descriptor
                .takeIf { it.supports(RideRole.HEART_RATE) }
                ?.let { heartRateSources.getOrPut(sourceId) { FakeHeartRateSource(sourceId) } }
        val trainer =
            descriptor
                .takeIf { it.supports(RideRole.RESISTANCE_CONTROL) }
                ?.let { trainerConnections.getOrPut(sourceId) { FakeTrainerControlConnection(sourceId) } }
        return ConnectedRideSource(
            descriptor = descriptor,
            cyclingTelemetry = cyclingTelemetry,
            heartRate = heartRate,
            trainerControlConnection = trainer,
        )
    }

    fun selectedRideEquipment(selection: RideEquipmentSelection): SelectedRideEquipment {
        val controlSource = requireNotNull(source(requireNotNull(selection.controlSourceId)))
        val trainer =
            requireNotNull(controlSource.trainerControlConnection) {
                "Test selection has no trainer control source"
            }
        val powerSource = selection.powerSourceId?.let { sourceId -> requireNotNull(source(sourceId)) }
        val cadenceSource = selection.cadenceSourceId?.let { sourceId -> requireNotNull(source(sourceId)) }
        return SelectedRideEquipment(
            selection = selection,
            trainerControlConnection = trainer,
            controlTelemetry =
                controlSource.cyclingTelemetry
                    ?.takeIf { availableRideSources.isNotEmpty() || telemetryCapabilityAvailable },
            powerTelemetry = powerSource?.cyclingTelemetry,
            cadenceTelemetry = cadenceSource?.cyclingTelemetry,
            heartRate = selection.heartRateSourceId?.let { id -> requireNotNull(source(id)?.heartRate) },
        )
    }

    fun emitTelemetry(nextTelemetry: CyclingTelemetry) {
        telemetry = nextTelemetry
        val sourceId =
            sources()
                .firstOrNull { source -> source.supports(RideRole.POWER) || source.supports(RideRole.CADENCE) }
                ?.id
                ?: DEFAULT_SOURCE_ID
        emitTelemetry(sourceId, nextTelemetry)
    }

    fun emitTelemetry(
        sourceId: String,
        nextTelemetry: CyclingTelemetry,
    ) {
        sourceTelemetries[sourceId] = nextTelemetry
        if (sourceId == DEFAULT_SOURCE_ID) {
            telemetry = nextTelemetry
        }
        cyclingSources[sourceId]?.emit(nextTelemetry)
    }

    fun emitHeartRate(
        sourceId: String,
        nextHeartRate: HeartRateTelemetry,
    ) {
        heartRates[sourceId] = nextHeartRate
        heartRateSources[sourceId]?.emit(nextHeartRate)
    }

    private fun rawControl(sourceId: String): DeviceTrainerControl? =
        if (sourcePowerControls.containsKey(sourceId)) {
            sourcePowerControls[sourceId]
        } else if (sourceId == DEFAULT_SOURCE_ID) {
            powerControl
        } else {
            null
        }

    private fun rideControl(sourceId: String): RideTrainerControl? {
        val raw = rawControl(sourceId) ?: return null
        val existing = rideControls[sourceId]
        if (existing == null || existing.first !== raw) {
            val wrapped =
                object : RideTrainerControl {
                    override fun requestControl() = raw.requestControl()

                    override fun setTargetPower(powerWatts: Int) = raw.setTargetPower(powerWatts)

                    override fun setFreeRide() = raw.setFreeRide()
                }
            rideControls[sourceId] = raw to wrapped
            return wrapped
        }
        return existing.second
    }

    private fun isConnectedSource(sourceId: String): Boolean = sources().firstOrNull { source -> source.id == sourceId }?.connected ?: false

    private inner class FakeCyclingTelemetrySource(
        override val sourceId: String,
    ) : CyclingTelemetrySource {
        private val listeners = mutableListOf<(CyclingTelemetry) -> Unit>()

        override fun current(): CyclingTelemetry? =
            (sourceTelemetries[sourceId] ?: telemetry.takeIf { sourceId == DEFAULT_SOURCE_ID })
                ?.takeIf { isConnectedSource(sourceId) }

        override fun subscribe(listener: (CyclingTelemetry) -> Unit): AutoCloseable {
            if (!isConnectedSource(sourceId)) {
                return AutoCloseable { }
            }
            listeners += listener
            return AutoCloseable { listeners -= listener }
        }

        fun emit(nextTelemetry: CyclingTelemetry) {
            if (isConnectedSource(sourceId)) {
                listeners.toList().forEach { listener -> listener(nextTelemetry) }
            }
        }
    }

    private inner class FakeHeartRateSource(
        override val sourceId: String,
    ) : HeartRateSource {
        private val listeners = mutableListOf<(HeartRateTelemetry) -> Unit>()

        override fun current(): HeartRateTelemetry? = heartRates[sourceId]?.takeIf { isConnectedSource(sourceId) }

        override fun subscribe(listener: (HeartRateTelemetry) -> Unit): AutoCloseable {
            if (!isConnectedSource(sourceId)) {
                return AutoCloseable { }
            }
            listeners += listener
            return AutoCloseable { listeners -= listener }
        }

        fun emit(nextHeartRate: HeartRateTelemetry) {
            if (isConnectedSource(sourceId)) {
                listeners.toList().forEach { listener -> listener(nextHeartRate) }
            }
        }
    }

    private inner class FakeTrainerControlConnection(
        override val sourceId: String,
    ) : TrainerControlConnection {
        override fun control(): RideTrainerControl? = rideControl(sourceId).takeIf { isConnectedSource(sourceId) }

        override fun reconnect(force: Boolean): CompletionStage<TrainerControlConnection?> {
            val recovered =
                if (sourceReconnectResults.containsKey(sourceId)) {
                    sourceReconnectCalls += sourceId
                    sourceReconnectResults[sourceId]
                } else if (sourceId == DEFAULT_SOURCE_ID) {
                    reconnectCalls += 1
                    lastReconnectForce = force
                    (reconnectPowerControlResult ?: powerControl)?.also { powerControl = it }
                } else {
                    sourceReconnectCalls += sourceId
                    sourceReconnectResults[sourceId]
                }
            if (recovered != null) {
                sourcePowerControls[sourceId] = recovered
                availableRideSources =
                    availableRideSources.map { source ->
                        if (source.id == sourceId) source.copy(state = ConnectionPhase.CONNECTED) else source
                    }
            }
            return CompletableFuture.completedFuture(this.takeIf { recovered != null })
        }
    }

    private companion object {
        const val DEFAULT_SOURCE_ID = "trainer"
    }
}
