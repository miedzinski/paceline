package paceline.training.domain

import paceline.device.domain.IndoorBikeTelemetry
import paceline.workout.domain.ExecutableSport
import paceline.workout.domain.ExecutableWorkout
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutSourceReference
import paceline.workout.domain.WorkoutStepCompletion
import paceline.workout.domain.WorkoutStepTarget
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkoutExecutionTest {
    private val start = Instant.parse("2026-09-16T12:00:00Z")

    @Test
    fun `calculates fixed and ramp targets with the session intensity multiplier`() {
        // given a workout execution attached to a fixed step followed by a descending ramp:
        val execution = WorkoutExecution()
        val workout =
            workout(
                step(
                    completion = WorkoutStepCompletion.Time(10),
                    target = WorkoutStepTarget.Power(200, 300),
                ),
                step(
                    completion = WorkoutStepCompletion.Time(10),
                    target = WorkoutStepTarget.Ramp(300, 100),
                ),
            )
        val firstProgress = TrainingWorkoutProgress.firstStep(workout, start)
        execution.attach(workout, firstProgress, telemetry(distanceMeters = 1_000.0))

        // when the fixed target is resolved and the next ramp is sampled at its midpoint:
        val fixedTarget = execution.targetPower(firstProgress, start, targetPercent = 100)
        val nextProgress = requireNotNull(execution.nextProgress(firstProgress, start.plusSeconds(10)))
        val rampMidpoint = execution.targetPower(nextProgress, start.plusSeconds(15), targetPercent = 110)

        // then fixed ranges use their midpoint and ramps scale the interpolated value:
        assertEquals(250, fixedTarget)
        assertEquals(220, rampMidpoint)
    }

    @Test
    fun `preserves distance progress across an explicit pause`() {
        // given a distance step with a cumulative trainer-distance baseline:
        val execution = WorkoutExecution()
        val workout =
            workout(
                step(
                    completion = WorkoutStepCompletion.Distance(100.0),
                    target = WorkoutStepTarget.Power(200, 200),
                ),
            )
        val progress = TrainingWorkoutProgress.firstStep(workout, start)
        execution.attach(workout, progress, telemetry(distanceMeters = 1_000.0))

        // when thirty meters are completed, the trainer moves while paused, and seventy meters follow resume:
        assertFalse(execution.isComplete(progress, start.plusSeconds(1), telemetry(distanceMeters = 1_030.0)))
        execution.captureDistanceProgressAtPause(progress, telemetry(distanceMeters = 1_030.0))
        execution.resumeDistanceTracking(progress, telemetry(distanceMeters = 1_080.0))

        // then distance accumulated during the pause is excluded from completion:
        assertFalse(execution.isComplete(progress, start.plusSeconds(21), telemetry(distanceMeters = 1_149.0)))
        assertTrue(execution.isComplete(progress, start.plusSeconds(22), telemetry(distanceMeters = 1_150.0)))
    }

    private fun workout(vararg steps: ExecutableWorkoutStep): ExecutableWorkout =
        ExecutableWorkout(
            source = WorkoutSourceReference("test", "workout"),
            name = "Test workout",
            sport = ExecutableSport.CYCLING,
            steps = steps.toList(),
        )

    private fun step(
        completion: WorkoutStepCompletion,
        target: WorkoutStepTarget,
    ): ExecutableWorkoutStep =
        ExecutableWorkoutStep(
            text = null,
            completion = completion,
            target = target,
        )

    private fun telemetry(distanceMeters: Double): IndoorBikeTelemetry =
        IndoorBikeTelemetry(
            powerWatts = 200,
            cadenceRpm = 90.0,
            speedKph = 25.0,
            distanceMeters = distanceMeters,
            receivedAt = start,
        )
}
