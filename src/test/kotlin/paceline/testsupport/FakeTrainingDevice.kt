package paceline.testsupport

import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.IndoorBikePowerControl
import paceline.device.ports.IndoorBikeTelemetryListener
import paceline.training.ports.TrainingDevice

class FakeTrainingDevice(
    var powerControl: IndoorBikePowerControl? = null,
    var telemetry: IndoorBikeTelemetry? = null,
) : TrainingDevice {
    private val telemetryListeners = mutableListOf<IndoorBikeTelemetryListener>()

    override fun currentPowerControl(): IndoorBikePowerControl? = powerControl

    override fun currentTelemetry(): IndoorBikeTelemetry? = telemetry

    override fun addTelemetryListener(listener: IndoorBikeTelemetryListener): AutoCloseable {
        telemetryListeners += listener
        return AutoCloseable { telemetryListeners -= listener }
    }

    fun emitTelemetry(nextTelemetry: IndoorBikeTelemetry) {
        telemetry = nextTelemetry
        telemetryListeners.toList().forEach { it.onTelemetry(nextTelemetry) }
    }
}
