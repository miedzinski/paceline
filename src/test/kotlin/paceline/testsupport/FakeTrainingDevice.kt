package paceline.testsupport

import paceline.device.domain.HeartRateSourceDescriptor
import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.HeartRateTelemetryListener
import paceline.device.ports.IndoorBikePowerControl
import paceline.device.ports.IndoorBikeTelemetryListener
import paceline.training.ports.TrainingDevice
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class FakeTrainingDevice(
    var powerControl: IndoorBikePowerControl? = null,
    var telemetry: IndoorBikeTelemetry? = null,
    var availableHeartRateSources: List<HeartRateSourceDescriptor> = emptyList(),
    var telemetryCapabilityAvailable: Boolean = false,
) : TrainingDevice {
    var reconnectPowerControlResult: IndoorBikePowerControl? = null
    var lastReconnectForce: Boolean? = null
    var reconnectCalls = 0
        private set
    private val telemetryListeners = mutableListOf<IndoorBikeTelemetryListener>()
    private val heartRateListeners = mutableMapOf<String, MutableList<HeartRateTelemetryListener>>()
    private val heartRates = mutableMapOf<String, HeartRateTelemetry>()

    override fun currentPowerControl(): IndoorBikePowerControl? = powerControl

    override fun reconnectPowerControl(force: Boolean): CompletionStage<IndoorBikePowerControl?> {
        reconnectCalls += 1
        lastReconnectForce = force
        return CompletableFuture.completedFuture(reconnectPowerControlResult?.also { powerControl = it })
    }

    override fun hasTelemetryCapability(): Boolean = telemetryCapabilityAvailable

    override fun currentTelemetry(): IndoorBikeTelemetry? = telemetry

    override fun addTelemetryListener(listener: IndoorBikeTelemetryListener): AutoCloseable {
        telemetryListeners += listener
        return AutoCloseable { telemetryListeners -= listener }
    }

    override fun heartRateSources(): List<HeartRateSourceDescriptor> = availableHeartRateSources

    override fun currentHeartRate(sourceId: String): HeartRateTelemetry? = heartRates[sourceId]

    override fun addHeartRateListener(
        sourceId: String,
        listener: HeartRateTelemetryListener,
    ): AutoCloseable {
        heartRateListeners.getOrPut(sourceId, ::mutableListOf) += listener
        return AutoCloseable { heartRateListeners[sourceId]?.remove(listener) }
    }

    fun emitTelemetry(nextTelemetry: IndoorBikeTelemetry) {
        telemetry = nextTelemetry
        telemetryListeners.toList().forEach { it.onTelemetry(nextTelemetry) }
    }

    fun emitHeartRate(
        sourceId: String,
        nextHeartRate: HeartRateTelemetry,
    ) {
        heartRates[sourceId] = nextHeartRate
        heartRateListeners[sourceId].orEmpty().toList().forEach { it.onHeartRate(nextHeartRate) }
    }
}
