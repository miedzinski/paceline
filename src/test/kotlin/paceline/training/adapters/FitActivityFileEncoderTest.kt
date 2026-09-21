package paceline.training.adapters

import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.Decode
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.Intensity
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Mesg
import com.garmin.fit.MesgListener
import com.garmin.fit.RecordMesg
import com.garmin.fit.WktStepDuration
import com.garmin.fit.WktStepTarget
import com.garmin.fit.WorkoutMesg
import com.garmin.fit.WorkoutStepMesg
import paceline.training.domain.RecordedCyclingObservation
import paceline.training.domain.RecordedHeartRateObservation
import paceline.training.domain.RecordedTrainingActivity
import paceline.training.domain.RecordedTrainingActivitySegment
import paceline.training.domain.TrainingActivityEvent
import paceline.training.domain.TrainingActivityEventType
import paceline.training.domain.TrainingTelemetrySample
import paceline.training.ports.ActivityFile
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutStepCompletion
import paceline.workout.domain.WorkoutStepTarget
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitActivityFileEncoderTest {
    private val encoder = FitActivityFileEncoder()

    @Test
    fun `activity file preserves telemetry in a valid FIT activity`() {
        // given an in-memory ride with two raw telemetry samples:
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                startedAt = Instant.parse("2026-09-14T12:00:00Z"),
                stoppedAt = Instant.parse("2026-09-14T12:00:01Z"),
                name = "Test ride",
                workoutSource = null,
                workoutCompleted = false,
                samples =
                    listOf(
                        sample("2026-09-14T12:00:00Z", 1_000.0, 200).copy(heartRateBpm = 144),
                        sample("2026-09-14T12:00:00.100Z", 1_001.5, 210),
                    ),
            )

        // when the activity is encoded as FIT:
        val file = encoder.encode(activity)
        val messages = decode(file)
        val records = messages.filter { it.name == "record" }

        // then the file identity and telemetry are readable by Garmin's FIT decoder:
        assertEquals("paceline-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.fit", file.fileName)
        assertEquals("application/octet-stream", file.contentType)
        assertEquals(2, records.size)
        assertEquals(1, messages.count { it.name == "lap" })
        assertEquals(1, messages.count { it.name == "session" })
        assertEquals(1, messages.count { it.name == "activity" })
        assertEquals(
            DateTime(activity.stoppedAt).getTimestamp() +
                ZoneId
                    .systemDefault()
                    .rules
                    .getOffset(activity.stoppedAt)
                    .totalSeconds,
            messages
                .single { it.name == "activity" }
                .getFieldLongValue(ActivityMesg.LocalTimestampFieldNum),
        )
        assertEquals(0.0f, records[0].getFieldFloatValue(RecordMesg.DistanceFieldNum))
        assertEquals(1.5f, records[1].getFieldFloatValue(RecordMesg.DistanceFieldNum))
        assertEquals(200, records[0].getFieldIntegerValue(RecordMesg.PowerFieldNum))
        assertEquals(210, records[1].getFieldIntegerValue(RecordMesg.PowerFieldNum))
        assertEquals(90.toShort(), records[0].getFieldShortValue(RecordMesg.CadenceFieldNum))
        assertEquals(144.toShort(), records[0].getFieldShortValue(RecordMesg.HeartRateFieldNum))
        assertTrue(records[1].getFieldFloatValue(RecordMesg.Time128FieldNum) > 0.0f)
    }

    @Test
    fun `activity file excludes telemetry outside the session start and stop`() {
        // given an activity containing samples before start, during the ride, and after stop:
        val startedAt = Instant.parse("2026-09-14T12:00:00Z")
        val stoppedAt = startedAt.plusSeconds(1)
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                startedAt = startedAt,
                stoppedAt = stoppedAt,
                name = "Bounded ride",
                workoutSource = null,
                workoutCompleted = false,
                samples =
                    listOf(
                        sample(startedAt.minusMillis(1).toString(), 999.0, 190),
                        sample(startedAt.plusMillis(500).toString(), 1_000.0, 210),
                        sample(stoppedAt.plusMillis(1).toString(), 1_001.0, 230),
                    ),
            )

        // when the activity is encoded as FIT:
        val records = decode(encoder.encode(activity)).filter { it.name == "record" }

        // then only the sample inside the inclusive session boundary is exported:
        assertEquals(1, records.size)
        assertEquals(210, records.single().getFieldIntegerValue(RecordMesg.PowerFieldNum))
    }

    @Test
    fun `activity export combines exact timestamps without filling sparse raw fields`() {
        // given separate sparse cycling and heart-rate raw streams with a gap between cycling observations:
        val firstAt = Instant.parse("2026-09-14T12:00:00Z")
        val secondAt = Instant.parse("2026-09-14T12:00:02Z")
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                startedAt = firstAt,
                stoppedAt = Instant.parse("2026-09-14T12:00:03Z"),
                name = "Sparse ride",
                workoutSource = null,
                workoutCompleted = false,
                samples = emptyList(),
                cyclingObservations =
                    listOf(
                        RecordedCyclingObservation(
                            receivedAt = firstAt,
                            powerWatts = 200,
                            cadenceRpm = null,
                            speedKph = null,
                            distanceMeters = null,
                            sourceId = "trainer",
                            powerSourceId = "trainer",
                        ),
                        RecordedCyclingObservation(
                            receivedAt = secondAt,
                            powerWatts = null,
                            cadenceRpm = 90.0,
                            speedKph = null,
                            distanceMeters = null,
                            sourceId = "cadence",
                            cadenceSourceId = "cadence",
                        ),
                    ),
                heartRateObservations =
                    listOf(
                        RecordedHeartRateObservation(
                            receivedAt = firstAt,
                            heartRateBpm = 145,
                            sourceId = "strap",
                        ),
                    ),
            )

        // when the raw activity is encoded as FIT:
        val records = decode(encoder.encode(activity)).filter { it.name == "record" }

        // then the exporter combines only the exact timestamp and leaves absent fields unset:
        assertEquals(2, records.size)
        assertEquals(200, records[0].getFieldIntegerValue(RecordMesg.PowerFieldNum))
        assertEquals(145.toShort(), records[0].getFieldShortValue(RecordMesg.HeartRateFieldNum))
        assertNull(records[0].getFieldShortValue(RecordMesg.CadenceFieldNum))
        assertNull(records[0].getFieldFloatValue(RecordMesg.SpeedFieldNum))
        assertNull(records[0].getFieldFloatValue(RecordMesg.DistanceFieldNum))
        assertNull(records[1].getFieldIntegerValue(RecordMesg.PowerFieldNum))
        assertEquals(90.toShort(), records[1].getFieldShortValue(RecordMesg.CadenceFieldNum))
        assertNull(records[1].getFieldFloatValue(RecordMesg.SpeedFieldNum))
        assertNull(records[1].getFieldFloatValue(RecordMesg.DistanceFieldNum))
    }

    @Test
    fun `structured activity carries workout steps and linked interval laps`() {
        // given an activity whose telemetry is split across two executed workout steps and continuation:
        val firstSample = sample("2026-09-14T12:00:00Z", 1_000.0, 150)
        val secondSample = sample("2026-09-14T12:00:01Z", 1_001.0, 150)
        val thirdSample = sample("2026-09-14T12:00:02Z", 1_002.5, 220)
        val fourthSample = sample("2026-09-14T12:00:03Z", 1_004.0, 220)
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                startedAt = Instant.parse("2026-09-14T12:00:00Z"),
                stoppedAt = Instant.parse("2026-09-14T12:00:04Z"),
                name = "Structured ride",
                workoutSource = null,
                workoutCompleted = true,
                samples = listOf(firstSample, secondSample, thirdSample, fourthSample),
                segments =
                    listOf(
                        RecordedTrainingActivitySegment(
                            name = "Step 1/2: Warmup",
                            targetPowerWatts = 200,
                            startedAt = Instant.parse("2026-09-14T12:00:00Z"),
                            stoppedAt = Instant.parse("2026-09-14T12:00:02Z"),
                            samples = listOf(firstSample, secondSample),
                            workoutStep =
                                ExecutableWorkoutStep(
                                    text = "Warmup",
                                    completion = WorkoutStepCompletion.Time(2),
                                    target = WorkoutStepTarget.Power(150, 250),
                                    intensity = "warmup",
                                ),
                        ),
                        RecordedTrainingActivitySegment(
                            name = "Step 2/2: Work",
                            targetPowerWatts = 220,
                            startedAt = Instant.parse("2026-09-14T12:00:02Z"),
                            stoppedAt = Instant.parse("2026-09-14T12:00:04Z"),
                            samples = listOf(thirdSample, fourthSample),
                            workoutStep =
                                ExecutableWorkoutStep(
                                    text = "Work",
                                    completion = WorkoutStepCompletion.Time(2),
                                    target = WorkoutStepTarget.Ramp(200, 240),
                                    intensity = "interval",
                                ),
                        ),
                        RecordedTrainingActivitySegment(
                            name = "Manual continuation",
                            targetPowerWatts = null,
                            startedAt = Instant.parse("2026-09-14T12:00:04Z"),
                            stoppedAt = Instant.parse("2026-09-14T12:00:04Z"),
                            samples = emptyList(),
                        ),
                    ),
            )

        // when the structured activity is encoded as FIT:
        val messages = decode(encoder.encode(activity))
        val workout = messages.single { it.name == "workout" }
        val steps = messages.filter { it.name == "workout_step" }
        val laps = messages.filter { it.name == "lap" }

        // then each executed step is named, targeted, and linked to its activity lap:
        assertEquals("Structured ride", workout.getFieldStringValue(WorkoutMesg.WktNameFieldNum))
        assertEquals(2, workout.getFieldIntegerValue(WorkoutMesg.NumValidStepsFieldNum))
        assertEquals(listOf(0, 1), steps.map { it.getFieldIntegerValue(WorkoutStepMesg.MessageIndexFieldNum) })
        assertEquals(
            listOf("Step 1/2: Warmup", "Step 2/2: Work"),
            steps.map { it.getFieldStringValue(WorkoutStepMesg.WktStepNameFieldNum) },
        )
        assertEquals(
            listOf(Intensity.WARMUP, Intensity.INTERVAL),
            steps.map { Intensity.getByValue(it.getFieldShortValue(WorkoutStepMesg.IntensityFieldNum)) },
        )
        assertEquals(
            listOf(WktStepDuration.TIME, WktStepDuration.TIME),
            steps.map { WktStepDuration.getByValue(it.getFieldShortValue(WorkoutStepMesg.DurationTypeFieldNum)) },
        )
        assertEquals(
            listOf(2.0f, 2.0f),
            steps.map { it.getFieldFloatValue(WorkoutStepMesg.DurationValueFieldNum) },
        )
        assertEquals(
            listOf(WktStepTarget.POWER, WktStepTarget.POWER),
            steps.map { WktStepTarget.getByValue(it.getFieldShortValue(WorkoutStepMesg.TargetTypeFieldNum)) },
        )
        assertEquals(
            listOf(150L, 200L),
            steps.map { it.getFieldLongValue(WorkoutStepMesg.CustomTargetValueLowFieldNum) },
        )
        assertEquals(
            listOf(250L, 240L),
            steps.map { it.getFieldLongValue(WorkoutStepMesg.CustomTargetValueHighFieldNum) },
        )
        assertEquals(listOf(0, 1, null), laps.map { it.getFieldIntegerValue(LapMesg.WktStepIndexFieldNum) })
        assertEquals(
            listOf(LapTrigger.TIME, LapTrigger.TIME, LapTrigger.MANUAL),
            laps.map { LapTrigger.getByValue(it.getFieldShortValue(LapMesg.LapTriggerFieldNum)) },
        )
        assertEquals(
            listOf(2.0f, 2.0f, 0.0f),
            laps.map { it.getFieldFloatValue(LapMesg.TotalElapsedTimeFieldNum) },
        )
        assertNull(laps[2].getFieldIntegerValue(LapMesg.WktStepIndexFieldNum))
    }

    @Test
    fun `activity file preserves ERG protection events alongside raw telemetry`() {
        // given a ride that temporarily released ERG and then restored its target:
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                startedAt = Instant.parse("2026-09-14T12:00:00Z"),
                stoppedAt = Instant.parse("2026-09-14T12:00:03Z"),
                name = "Protected ride",
                workoutSource = null,
                workoutCompleted = false,
                samples =
                    listOf(
                        sample("2026-09-14T12:00:00Z", 1_000.0, 220),
                        sample("2026-09-14T12:00:02Z", 1_000.5, 0),
                    ),
                events =
                    listOf(
                        TrainingActivityEvent(
                            type = TrainingActivityEventType.ERG_PROTECTION_STARTED,
                            occurredAt = Instant.parse("2026-09-14T12:00:01Z"),
                            cadenceRpm = 42.0,
                        ),
                        TrainingActivityEvent(
                            type = TrainingActivityEventType.ERG_PROTECTION_ENDED,
                            occurredAt = Instant.parse("2026-09-14T12:00:02Z"),
                            cadenceRpm = 62.0,
                        ),
                    ),
            )

        // when the activity is encoded as FIT:
        val messages = decode(encoder.encode(activity))
        val events = messages.filter { it.name == "event" }

        // then the low-cadence lifecycle is represented in the exported timeline:
        assertEquals(
            listOf(EventType.START, EventType.START, EventType.STOP, EventType.STOP_ALL),
            events.map { EventType.getByValue(it.getFieldShortValue(EventMesg.EventTypeFieldNum)) },
        )
        assertEquals(
            listOf(Event.TIMER, Event.CAD_LOW_ALERT, Event.CAD_LOW_ALERT, Event.TIMER),
            events.map { Event.getByValue(it.getFieldShortValue(EventMesg.EventFieldNum)) },
        )
        assertEquals(42, events[1].getFieldIntegerValue(EventMesg.Data16FieldNum))
        assertEquals(62, events[2].getFieldIntegerValue(EventMesg.Data16FieldNum))
    }

    @Test
    fun `activity file preserves workout target adjustments as timeline markers`() {
        // given a ride whose workout target was increased during execution:
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                startedAt = Instant.parse("2026-09-14T12:00:00Z"),
                stoppedAt = Instant.parse("2026-09-14T12:00:02Z"),
                name = "Adjusted ride",
                workoutSource = null,
                workoutCompleted = false,
                samples = listOf(sample("2026-09-14T12:00:00Z", 1_000.0, 200)),
                events =
                    listOf(
                        TrainingActivityEvent(
                            type = TrainingActivityEventType.WORKOUT_TARGET_ADJUSTED,
                            occurredAt = Instant.parse("2026-09-14T12:00:01Z"),
                            workoutPowerTargetPercent = 101L,
                            targetPowerWatts = 202,
                        ),
                    ),
            )

        // when the activity is encoded as FIT:
        val events = decode(encoder.encode(activity)).filter { it.name == "event" }

        // then the adjustment is visible as a user marker carrying its percentage and target watts:
        assertEquals(
            listOf(EventType.START, EventType.MARKER, EventType.STOP_ALL),
            events.map { EventType.getByValue(it.getFieldShortValue(EventMesg.EventTypeFieldNum)) },
        )
        assertEquals(Event.USER_MARKER, Event.getByValue(events[1].getFieldShortValue(EventMesg.EventFieldNum)))
        assertEquals(202, events[1].getFieldIntegerValue(EventMesg.Data16FieldNum))
        assertEquals(101L, events[1].getFieldLongValue(EventMesg.DataFieldNum))
    }

    @Test
    fun `activity file exports trainer connection events as neutral markers`() {
        // given a ride with a telemetry gap and a successful target synchronization:
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                startedAt = Instant.parse("2026-09-14T12:00:00Z"),
                stoppedAt = Instant.parse("2026-09-14T12:00:05Z"),
                name = "Reconnected ride",
                workoutSource = null,
                workoutCompleted = false,
                samples = listOf(sample("2026-09-14T12:00:00Z", 1_000.0, 200)),
                events =
                    listOf(
                        TrainingActivityEvent(
                            type = TrainingActivityEventType.TRAINER_CONNECTION_INTERRUPTED,
                            occurredAt = Instant.parse("2026-09-14T12:00:01Z"),
                        ),
                        TrainingActivityEvent(
                            type = TrainingActivityEventType.TRAINER_RECONNECT_ATTEMPTED,
                            occurredAt = Instant.parse("2026-09-14T12:00:02Z"),
                            retryAttempt = 2,
                        ),
                        TrainingActivityEvent(
                            type = TrainingActivityEventType.TRAINER_RECONNECTED,
                            occurredAt = Instant.parse("2026-09-14T12:00:03Z"),
                        ),
                        TrainingActivityEvent(
                            type = TrainingActivityEventType.TRAINER_TARGET_SYNCHRONIZED,
                            occurredAt = Instant.parse("2026-09-14T12:00:04Z"),
                            targetPowerWatts = 200,
                        ),
                    ),
            )

        // when the activity is encoded as FIT:
        val events = decode(encoder.encode(activity)).filter { it.name == "event" }

        // then connection lifecycle markers do not appear as false low-cadence alerts:
        assertEquals(
            listOf(EventType.START, EventType.MARKER, EventType.MARKER, EventType.MARKER, EventType.MARKER, EventType.STOP_ALL),
            events.map { EventType.getByValue(it.getFieldShortValue(EventMesg.EventTypeFieldNum)) },
        )
        assertEquals(
            listOf(Event.TIMER, Event.USER_MARKER, Event.USER_MARKER, Event.USER_MARKER, Event.USER_MARKER, Event.TIMER),
            events.map { Event.getByValue(it.getFieldShortValue(EventMesg.EventFieldNum)) },
        )
        assertNull(events[1].getFieldIntegerValue(EventMesg.Data16FieldNum))
        assertEquals(2, events[2].getFieldIntegerValue(EventMesg.Data16FieldNum))
        assertNull(events[3].getFieldIntegerValue(EventMesg.Data16FieldNum))
        assertEquals(200, events[4].getFieldIntegerValue(EventMesg.Data16FieldNum))
    }

    @Test
    fun `activity file exports pause and resume markers without synthetic samples`() {
        // given a ride with a pause gap and no telemetry during that interval:
        val start = Instant.parse("2026-09-14T12:00:00Z")
        val activity =
            RecordedTrainingActivity(
                sessionId = UUID.fromString("99999999-9999-9999-9999-999999999999"),
                startedAt = start,
                stoppedAt = start.plusSeconds(4),
                name = "Paused ride",
                workoutSource = null,
                workoutCompleted = false,
                samples =
                    listOf(
                        sample(start.toString(), 1_000.0, 200),
                        sample(start.plusSeconds(4).toString(), 1_001.0, 205),
                    ),
                events =
                    listOf(
                        TrainingActivityEvent(
                            type = TrainingActivityEventType.TRAINING_PAUSED,
                            occurredAt = start.plusSeconds(1),
                        ),
                        TrainingActivityEvent(
                            type = TrainingActivityEventType.TRAINING_RESUMED,
                            occurredAt = start.plusSeconds(3),
                        ),
                    ),
            )

        // when the activity is encoded as FIT:
        val messages = decode(encoder.encode(activity))
        val events = messages.filter { it.name == "event" }
        val records = messages.filter { it.name == "record" }

        // then the pause lifecycle is exported as markers and the gap remains a gap:
        assertEquals(2, records.size)
        assertEquals(
            listOf(EventType.START, EventType.MARKER, EventType.MARKER, EventType.STOP_ALL),
            events.map { EventType.getByValue(it.getFieldShortValue(EventMesg.EventTypeFieldNum)) },
        )
        assertEquals(
            listOf(Event.TIMER, Event.USER_MARKER, Event.USER_MARKER, Event.TIMER),
            events.map { Event.getByValue(it.getFieldShortValue(EventMesg.EventFieldNum)) },
        )
    }

    private fun decode(file: ActivityFile): List<Mesg> {
        val messages = mutableListOf<Mesg>()
        val decoded =
            Decode().read(
                ByteArrayInputStream(file.content),
                MesgListener { message -> messages += message },
            )
        assertTrue(decoded)
        assertNotNull(messages.firstOrNull { it.name == "file_id" })
        return messages
    }

    private fun sample(
        receivedAt: String,
        distanceMeters: Double,
        powerWatts: Int,
    ): TrainingTelemetrySample =
        TrainingTelemetrySample(
            receivedAt = Instant.parse(receivedAt),
            powerWatts = powerWatts,
            cadenceRpm = 90.0,
            speedKph = 25.0,
            distanceMeters = distanceMeters,
        )
}
