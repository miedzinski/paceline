package paceline.training.domain

import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.testsupport.FakeTrainingDevice
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TrainingActivitySessionTest {
    private val start = Instant.parse("2026-09-16T12:00:00Z")

    @Test
    fun `owns recording subscriptions across pause and resume`() {
        // given an activity session with an active trainer telemetry subscription:
        val trainingDevice = FakeTrainingDevice()
        val activitySession = TrainingActivitySession(trainingDevice)
        val sessionId = UUID.randomUUID()
        activitySession.start(
            sessionId = sessionId,
            startedAt = start,
            workout = null,
            heartRateSourceId = null,
            initialTargetPowerWatts = null,
            onTelemetry = {},
            onHeartRate = { _, _, _ -> },
        )

        // when telemetry arrives before pause, during pause, and after resume:
        trainingDevice.emitTelemetry(telemetry(start.plusSeconds(1)))
        activitySession.pauseRecording()
        trainingDevice.emitTelemetry(telemetry(start.plusSeconds(2)))
        activitySession.resumeRecording(sessionId)
        trainingDevice.emitTelemetry(telemetry(start.plusSeconds(3)))

        // then only samples from recording intervals belong to the finalized activity:
        val activity = activitySession.finish(sessionId, start.plusSeconds(4))
        assertEquals(
            listOf(start.plusSeconds(1), start.plusSeconds(3)),
            activity.samples.map { it.receivedAt },
        )
    }

    @Test
    fun `does not accept heart-rate notifications while paused`() {
        // given an activity session whose selected heart-rate callback records accepted samples:
        val trainingDevice = FakeTrainingDevice()
        val activitySession = TrainingActivitySession(trainingDevice)
        val sessionId = UUID.randomUUID()
        activitySession.start(
            sessionId = sessionId,
            startedAt = start,
            workout = null,
            heartRateSourceId = "strap",
            initialTargetPowerWatts = null,
            onTelemetry = {},
            onHeartRate = { id, sourceId, telemetry ->
                activitySession.recordHeartRate(id, sourceId, telemetry)
            },
        )

        // when heart-rate notifications arrive before, during, and after an explicit pause:
        trainingDevice.emitHeartRate("strap", heartRate(140))
        activitySession.pauseRecording()
        trainingDevice.emitHeartRate("strap", heartRate(145))
        activitySession.resumeRecording(sessionId)
        trainingDevice.emitHeartRate("strap", heartRate(150))

        // then only notifications received while recording is active belong to the activity:
        val activity = activitySession.finish(sessionId, start.plusSeconds(1))
        assertEquals(listOf(140, 150), activity.samples.mapNotNull { it.heartRateBpm })
    }

    private fun telemetry(receivedAt: Instant): IndoorBikeTelemetry =
        IndoorBikeTelemetry(
            powerWatts = 200,
            cadenceRpm = 90.0,
            speedKph = 25.0,
            distanceMeters = 1_000.0,
            receivedAt = receivedAt,
        )

    private fun heartRate(bpm: Int): HeartRateTelemetry =
        HeartRateTelemetry(
            heartRateBpm = bpm,
            receivedAt = start.plusSeconds(bpm.toLong()),
        )
}
