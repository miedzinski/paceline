package paceline.training.adapters

import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.Decode
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
import paceline.training.domain.RecordedTrainingActivity
import paceline.training.domain.RecordedTrainingActivitySegment
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
                                    target = WorkoutStepTarget.Power(200, 240),
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
