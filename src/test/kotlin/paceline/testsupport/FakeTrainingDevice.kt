package paceline.testsupport

import paceline.device.domain.HeartRateSourceDescriptor
import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.HeartRateTelemetryListener
import paceline.device.ports.IndoorBikePowerControl
import paceline.device.ports.IndoorBikeTelemetryListener
import paceline.training.ports.TrainingDevice

class FakeTrainingDevice(
    var powerControl: IndoorBikePowerControl? = null,
    var telemetry: IndoorBikeTelemetry? = null,
    var availableHeartRateSources: List<HeartRateSourceDescriptor> = emptyList(),
) : TrainingDevice {
    private val telemetryListeners = mutableListOf<IndoorBikeTelemetryListener>()
    private val heartRateListeners = mutableMapOf<String, MutableList<HeartRateTelemetryListener>>()
    private val heartRates = mutableMapOf<String, HeartRateTelemetry>()

    override fun currentPowerControl(): IndoorBikePowerControl? = powerControl

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
