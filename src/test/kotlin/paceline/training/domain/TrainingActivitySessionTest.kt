package paceline.training.domain

import paceline.device.domain.ConnectionPhase
import paceline.device.domain.DeviceAdvertisement
import paceline.device.domain.DeviceEndpoint
import paceline.telemetry.domain.CyclingTelemetry
import paceline.telemetry.domain.HeartRateTelemetry
import paceline.testsupport.FakeRideSourceCatalog
import paceline.testsupport.FakeTrainerControl
import paceline.testsupport.rideSource
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrainingActivitySessionTest {
    private val start = Instant.parse("2026-09-16T12:00:00Z")

    @Test
    fun `records telemetry throughout pause without notifying active observers`() {
        // given an activity session with an active trainer telemetry subscription:
        val rideSourceCatalog = FakeRideSourceCatalog(powerControl = FakeTrainerControl())
        val activitySession = TrainingActivitySession()
        val sessionId = UUID.randomUUID()
        var activeObserverNotifications = 0
        activitySession.start(
            sessionId = sessionId,
            startedAt = start,
            workout = null,
            initialTargetPowerWatts = null,
            onTelemetry = {},
            onHeartRate = { _, _, _ -> },
            equipment = rideSourceCatalog.selectedRideEquipment(defaultSelection()),
            onSourceTelemetry = { _, _ -> activeObserverNotifications += 1 },
        )

        // when telemetry arrives before pause, during pause, and after resume:
        rideSourceCatalog.emitTelemetry(telemetry(start.plusSeconds(1)))
        activitySession.pauseRecording()
        rideSourceCatalog.emitTelemetry(telemetry(start.plusSeconds(2)))
        activitySession.resumeRecording(sessionId)
        rideSourceCatalog.emitTelemetry(telemetry(start.plusSeconds(3)))

        // then all observed samples are recorded, while runtime observers receive only active samples:
        val activity = activitySession.finish(sessionId, start.plusSeconds(4))
        assertEquals(
            listOf(start.plusSeconds(1), start.plusSeconds(2), start.plusSeconds(3)),
            activity.samples.map { it.receivedAt },
        )
        assertEquals(2, activeObserverNotifications)
    }

    @Test
    fun `finalized activity excludes telemetry outside the session stop boundary`() {
        // given an active recording with cycling and heart-rate sources:
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = FakeTrainerControl(),
                availableHeartRateSources = listOf(heartRateSource("strap")),
            )
        val activitySession = TrainingActivitySession()
        val sessionId = UUID.randomUUID()
        activitySession.start(
            sessionId = sessionId,
            startedAt = start,
            workout = null,
            initialTargetPowerWatts = null,
            onTelemetry = {},
            onHeartRate = { id, sourceId, telemetry ->
                activitySession.recordHeartRate(id, sourceId, telemetry)
            },
            equipment = rideSourceCatalog.selectedRideEquipment(defaultSelection("strap")),
        )
        val stoppedAt = start.plusSeconds(2)

        // when notifications are delivered before stop and after finalization:
        rideSourceCatalog.emitTelemetry(telemetry(start.plusSeconds(1)))
        rideSourceCatalog.emitHeartRate(
            "strap",
            HeartRateTelemetry(heartRateBpm = 140, receivedAt = start.plusSeconds(1)),
        )
        val activity = activitySession.finish(sessionId, stoppedAt)
        rideSourceCatalog.emitTelemetry(telemetry(stoppedAt.plusSeconds(2)))
        rideSourceCatalog.emitHeartRate(
            "strap",
            HeartRateTelemetry(heartRateBpm = 160, receivedAt = stoppedAt.plusSeconds(2)),
        )

        // then the finalized activity contains only observations from session start through stop:
        assertEquals(listOf(start.plusSeconds(1)), activity.cyclingObservations.map { it.receivedAt })
        assertEquals(listOf(140), activity.heartRateObservations.map { it.heartRateBpm })
        assertEquals(listOf(start.plusSeconds(1)), activity.exportSamples.map { it.receivedAt })
    }

    @Test
    fun `activity summary uses all power samples between session start and stop`() {
        // given a finalized activity with samples before, during, and after the session:
        val stoppedAt = start.plusSeconds(3)
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.randomUUID(),
                startedAt = start,
                stoppedAt = stoppedAt,
                name = "Manual ride",
                workoutSource = null,
                workoutCompleted = false,
                samples =
                    listOf(
                        TrainingTelemetrySample(
                            receivedAt = start.minusSeconds(1),
                            powerWatts = 900,
                            cadenceRpm = null,
                            speedKph = null,
                            distanceMeters = null,
                        ),
                        TrainingTelemetrySample(
                            receivedAt = start.plusSeconds(1),
                            powerWatts = 200,
                            cadenceRpm = null,
                            speedKph = null,
                            distanceMeters = null,
                        ),
                        TrainingTelemetrySample(
                            receivedAt = start.plusSeconds(2),
                            powerWatts = 220,
                            cadenceRpm = null,
                            speedKph = null,
                            distanceMeters = null,
                        ),
                        TrainingTelemetrySample(
                            receivedAt = stoppedAt.plusSeconds(1),
                            powerWatts = 1_000,
                            cadenceRpm = null,
                            speedKph = null,
                            distanceMeters = null,
                        ),
                    ),
            )

        // when the summary is calculated from the finalized activity:
        val summary = activity.summary()

        // then it covers the full session and excludes samples outside its boundary:
        assertEquals(3, summary.durationSeconds)
        assertEquals(210, summary.averagePowerWatts)
    }

    @Test
    fun `records heart-rate notifications received while paused`() {
        // given an activity session whose selected heart-rate callback records accepted samples:
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = FakeTrainerControl(),
                availableHeartRateSources = listOf(heartRateSource("strap")),
            )
        val activitySession = TrainingActivitySession()
        val sessionId = UUID.randomUUID()
        activitySession.start(
            sessionId = sessionId,
            startedAt = start,
            workout = null,
            initialTargetPowerWatts = null,
            onTelemetry = {},
            onHeartRate = { id, sourceId, telemetry ->
                activitySession.recordHeartRate(id, sourceId, telemetry)
            },
            equipment = rideSourceCatalog.selectedRideEquipment(defaultSelection("strap")),
        )

        // when heart-rate notifications arrive before, during, and after an explicit pause:
        rideSourceCatalog.emitHeartRate("strap", heartRate(140))
        activitySession.pauseRecording()
        rideSourceCatalog.emitHeartRate("strap", heartRate(145))
        activitySession.resumeRecording(sessionId)
        rideSourceCatalog.emitHeartRate("strap", heartRate(150))

        // then selected heart-rate notifications received before, during, and after pause are retained:
        val activity = activitySession.finish(sessionId, start.plusSeconds(151))
        assertEquals(listOf(140, 145, 150), activity.samples.mapNotNull { it.heartRateBpm })
    }

    @Test
    fun `records split cycling roles from their selected sources`() {
        // given a ride whose control and power source differ from its cadence source:
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = FakeTrainerControl(),
                availableRideSources =
                    listOf(
                        rideSource(
                            "trainer",
                            setOf(
                                RideSourceCapability.RESISTANCE_CONTROL,
                                RideSourceCapability.POWER,
                            ),
                        ),
                        rideSource("cadence", setOf(RideSourceCapability.CADENCE)),
                    ),
            )
        val activitySession = TrainingActivitySession()
        val sessionId = UUID.randomUUID()
        val equipment =
            RideEquipmentSelection(
                controlSourceId = "trainer",
                powerSourceId = "trainer",
                cadenceSourceId = "cadence",
            )
        activitySession.start(
            sessionId = sessionId,
            startedAt = start,
            workout = null,
            initialTargetPowerWatts = null,
            onTelemetry = {},
            onHeartRate = { _, _, _ -> },
            equipment = rideSourceCatalog.selectedRideEquipment(equipment),
        )

        // when the selected trainer and cadence source report at different times:
        rideSourceCatalog.emitTelemetry(
            "trainer",
            CyclingTelemetry(
                powerWatts = 200,
                speedKph = 25.0,
                distanceMeters = 1_000.0,
                receivedAt = start.plusSeconds(1),
            ),
        )
        rideSourceCatalog.emitTelemetry(
            "cadence",
            CyclingTelemetry(
                cadenceRpm = 92.0,
                receivedAt = start.plusSeconds(2),
            ),
        )

        // then raw observations keep the field ownership and do not fabricate a combined timestamp:
        val activity = activitySession.finish(sessionId, start.plusSeconds(3))
        assertEquals(listOf(start.plusSeconds(1), start.plusSeconds(2)), activity.samples.map { it.receivedAt })
        assertEquals(200, activity.samples[0].powerWatts)
        assertEquals("trainer", activity.samples[0].powerSourceId)
        assertEquals(1_000.0, activity.samples[0].distanceMeters)
        assertEquals("trainer", activity.samples[0].distanceSourceId)
        assertEquals(92.0, activity.samples[1].cadenceRpm)
        assertEquals("cadence", activity.samples[1].cadenceSourceId)
        assertEquals(null, activity.samples[1].distanceMeters)
        assertEquals(listOf("trainer", "cadence"), activity.cyclingObservations.map { it.sourceId })
        assertEquals(emptyList(), activity.heartRateObservations)
    }

    @Test
    fun `retains sparse cycling fields without carrying values across observations`() {
        // given a selected trainer whose notifications contain different sparse fields:
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = FakeTrainerControl(),
                availableRideSources =
                    listOf(
                        rideSource(
                            "trainer",
                            setOf(
                                RideSourceCapability.RESISTANCE_CONTROL,
                                RideSourceCapability.POWER,
                                RideSourceCapability.CADENCE,
                            ),
                        ),
                    ),
            )
        val activitySession = TrainingActivitySession()
        val sessionId = UUID.randomUUID()
        val equipment =
            RideEquipmentSelection(
                controlSourceId = "trainer",
                powerSourceId = "trainer",
                cadenceSourceId = "trainer",
            )
        activitySession.start(
            sessionId = sessionId,
            startedAt = start,
            workout = null,
            initialTargetPowerWatts = null,
            onTelemetry = {},
            onHeartRate = { _, _, _ -> },
            equipment = rideSourceCatalog.selectedRideEquipment(equipment),
        )

        // when one notification has power and another has only cadence:
        rideSourceCatalog.emitTelemetry(
            "trainer",
            CyclingTelemetry(
                powerWatts = 210,
                speedKph = 30.0,
                distanceMeters = 1_000.0,
                receivedAt = start.plusSeconds(1),
            ),
        )
        rideSourceCatalog.emitTelemetry(
            "trainer",
            CyclingTelemetry(
                cadenceRpm = 88.0,
                receivedAt = start.plusSeconds(2),
            ),
        )

        // then absent fields stay absent instead of becoming zero or stale values:
        val activity = activitySession.finish(sessionId, start.plusSeconds(3))
        assertEquals(2, activity.cyclingObservations.size)
        assertEquals(210, activity.cyclingObservations[0].powerWatts)
        assertNull(activity.cyclingObservations[0].cadenceRpm)
        assertEquals(88.0, activity.cyclingObservations[1].cadenceRpm)
        assertNull(activity.cyclingObservations[1].powerWatts)
        assertNull(activity.cyclingObservations[1].speedKph)
        assertNull(activity.cyclingObservations[1].distanceMeters)
    }

    @Test
    fun `keeps same-time source observations raw and combines them only for export`() {
        // given independent selected power and cadence sources:
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = FakeTrainerControl(),
                availableRideSources =
                    listOf(
                        rideSource(
                            "trainer",
                            setOf(RideSourceCapability.RESISTANCE_CONTROL, RideSourceCapability.POWER),
                        ),
                        rideSource("cadence", setOf(RideSourceCapability.CADENCE)),
                    ),
            )
        val activitySession = TrainingActivitySession()
        val sessionId = UUID.randomUUID()
        val sameTime = start.plusSeconds(1)
        activitySession.start(
            sessionId = sessionId,
            startedAt = start,
            workout = null,
            initialTargetPowerWatts = null,
            onTelemetry = {},
            onHeartRate = { _, _, _ -> },
            equipment =
                rideSourceCatalog.selectedRideEquipment(
                    RideEquipmentSelection(
                        controlSourceId = "trainer",
                        powerSourceId = "trainer",
                        cadenceSourceId = "cadence",
                    ),
                ),
        )

        // when both source notifications arrive with the same receive timestamp:
        rideSourceCatalog.emitTelemetry(
            "trainer",
            CyclingTelemetry(powerWatts = 205, receivedAt = sameTime),
        )
        rideSourceCatalog.emitTelemetry(
            "cadence",
            CyclingTelemetry(cadenceRpm = 91.0, receivedAt = sameTime),
        )

        // then raw source notifications remain distinct while the export view is sparse and combined:
        val activity = activitySession.finish(sessionId, start.plusSeconds(2))
        assertEquals(listOf("trainer", "cadence"), activity.cyclingObservations.map { it.sourceId })
        assertEquals(listOf(205, null), activity.cyclingObservations.map { it.powerWatts })
        assertEquals(listOf(null, 91.0), activity.cyclingObservations.map { it.cadenceRpm })
        assertEquals(1, activity.exportSamples.size)
        assertEquals(205, activity.exportSamples.single().powerWatts)
        assertEquals(91.0, activity.exportSamples.single().cadenceRpm)
        assertEquals("trainer", activity.exportSamples.single().powerSourceId)
        assertEquals("cadence", activity.exportSamples.single().cadenceSourceId)
    }

    @Test
    fun `keeps heart rate as a separate selected-source stream`() {
        // given a ride with selected cycling and heart-rate sources:
        val rideSourceCatalog =
            FakeRideSourceCatalog(
                powerControl = FakeTrainerControl(),
                availableHeartRateSources = listOf(heartRateSource("strap")),
            )
        val activitySession = TrainingActivitySession()
        val sessionId = UUID.randomUUID()
        val receivedAt = start.plusSeconds(1)
        activitySession.start(
            sessionId = sessionId,
            startedAt = start,
            workout = null,
            initialTargetPowerWatts = null,
            onTelemetry = {},
            onHeartRate = { id, sourceId, telemetry ->
                activitySession.recordHeartRate(id, sourceId, telemetry)
            },
            equipment = rideSourceCatalog.selectedRideEquipment(defaultSelection("strap")),
        )

        // when cycling and heart-rate notifications arrive independently at the same time:
        rideSourceCatalog.emitTelemetry(
            CyclingTelemetry(powerWatts = 220, receivedAt = receivedAt),
        )
        rideSourceCatalog.emitHeartRate(
            "strap",
            HeartRateTelemetry(heartRateBpm = 148, receivedAt = receivedAt),
        )

        // then the raw streams stay separate and the selected source identity is retained:
        val activity = activitySession.finish(sessionId, start.plusSeconds(2))
        assertEquals(1, activity.cyclingObservations.size)
        assertEquals(1, activity.heartRateObservations.size)
        assertEquals("strap", activity.heartRateObservations.single().sourceId)
        assertEquals(receivedAt, activity.heartRateObservations.single().receivedAt)
        assertEquals(1, activity.exportSamples.size)
        assertEquals(148, activity.exportSamples.single().heartRateBpm)
        assertEquals("strap", activity.exportSamples.single().heartRateSourceId)
    }

    private fun telemetry(receivedAt: Instant): CyclingTelemetry =
        CyclingTelemetry(
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

    private fun defaultSelection(heartRateSourceId: String? = null): RideEquipmentSelection =
        RideEquipmentSelection(
            controlSourceId = "trainer",
            powerSourceId = "trainer",
            cadenceSourceId = "trainer",
            heartRateSourceId = heartRateSourceId,
        )

    private fun heartRateSource(id: String): RideSourceDescriptor =
        RideSourceDescriptor(
            id = id,
            state = ConnectionPhase.CONNECTED,
            capabilities = setOf(RideSourceCapability.HEART_RATE),
            device =
                DeviceAdvertisement(
                    name = id,
                    endpoint = DeviceEndpoint.Bluetooth("AA:BB:CC:DD:EE:01", "11:22:33:44:55:66"),
                ),
        )
}
