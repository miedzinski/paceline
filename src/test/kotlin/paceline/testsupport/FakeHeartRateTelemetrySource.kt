package paceline.testsupport

import paceline.device.ports.HeartRateTelemetryListener
import paceline.device.ports.HeartRateTelemetrySource
import paceline.telemetry.domain.HeartRateTelemetry
import java.util.concurrent.CopyOnWriteArrayList

class FakeHeartRateTelemetrySource : HeartRateTelemetrySource {
    private val listeners = CopyOnWriteArrayList<HeartRateTelemetryListener>()
    private var latest: HeartRateTelemetry? = null

    override fun addHeartRateListener(listener: HeartRateTelemetryListener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun latestHeartRate(): HeartRateTelemetry? = latest

    fun emit(telemetry: HeartRateTelemetry) {
        latest = telemetry
        listeners.forEach { listener -> listener.onHeartRate(telemetry) }
    }
}
