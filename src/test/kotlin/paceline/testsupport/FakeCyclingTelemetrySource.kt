package paceline.testsupport

import paceline.device.domain.CyclingMeasurement
import paceline.device.domain.CyclingTelemetry
import paceline.device.ports.CyclingTelemetryListener
import paceline.device.ports.CyclingTelemetrySource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class FakeCyclingTelemetrySource(
    measurements: Set<CyclingMeasurement> = CyclingMeasurement.entries.toSet(),
) : CyclingTelemetrySource {
    private val measurementsReference = AtomicReference(measurements)
    private val listeners = CopyOnWriteArrayList<CyclingTelemetryListener>()
    private var latest: CyclingTelemetry? = null

    override val measurements: Set<CyclingMeasurement>
        get() = measurementsReference.get()

    override fun addTelemetryListener(listener: CyclingTelemetryListener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun latestTelemetry(): CyclingTelemetry? = latest

    fun emit(telemetry: CyclingTelemetry) {
        latest = telemetry
        listeners.forEach { listener -> listener.onTelemetry(telemetry) }
    }

    fun expose(measurement: CyclingMeasurement) {
        measurementsReference.updateAndGet { current -> current + measurement }
    }
}
