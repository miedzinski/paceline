package paceline.workout.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WorkoutExecutionPlanTest {
    @Test
    fun `normalizes repeated power and open steps into a strict cycling plan`() {
        // given a cycling plan with a repeated group and resolved power targets:
        val repeatedGroup =
            WorkoutStepSummary(
                text = "2x",
                durationSeconds = null,
                distanceMeters = null,
                repeats = 2,
                warmup = null,
                cooldown = null,
                intensity = "interval",
                ramp = null,
                power = null,
                resolvedPower = null,
                heartRate = null,
                pace = null,
                cadence = null,
                steps =
                    listOf(
                        leafStep(
                            text = "Work",
                            durationSeconds = 300,
                            resolvedPower = WorkoutTargetSummary(value = 250.0, units = "W"),
                            intensity = "interval",
                        ),
                        leafStep(
                            text = "Free recovery",
                            durationSeconds = 60,
                            freeRide = true,
                            intensity = "recovery",
                        ),
                    ),
            )
        val scheduled = scheduledWorkout(WorkoutPlanSummary(null, 720, null, 250, null, "POWER", listOf(repeatedGroup)))

        // when the plan is normalized for execution:
        val result = ExecutableWorkoutFactory.from(scheduled)

        // then repetitions are expanded and every executable step has strict completion and target types:
        assertEquals(ExecutableSport.CYCLING, result.sport)
        assertEquals(WorkoutSourceType.SCHEDULED, result.sourceType)
        assertEquals(listOf("Work", "Free recovery", "Work", "Free recovery"), result.steps.map { it.text })
        assertEquals(
            listOf("interval", "recovery", "interval", "recovery"),
            result.steps.map { it.intensity },
        )
        assertEquals(
            250,
            assertIs<WorkoutStepTarget.Power>(result.steps[0].target).lowWatts,
        )
        assertEquals(
            300,
            assertIs<WorkoutStepCompletion.Time>(result.steps[0].completion).seconds,
        )
        assertIs<WorkoutStepTarget.Open>(result.steps[1].target)
    }

    @Test
    fun `rejects relative power when the provider did not resolve it`() {
        // given a workout whose only power target is still relative to FTP:
        val scheduled =
            scheduledWorkout(
                WorkoutPlanSummary(
                    description = null,
                    durationSeconds = 300,
                    distanceMeters = null,
                    ftpWatts = null,
                    thresholdHeartRateBpm = null,
                    target = "POWER",
                    steps =
                        listOf(
                            leafStep(
                                text = "Relative power",
                                durationSeconds = 300,
                                power = WorkoutTargetSummary(value = 90.0, units = "%ftp"),
                            ),
                        ),
                ),
            )

        // when the relative-only plan is normalized:
        val failure =
            assertFailsWith<WorkoutNotExecutableException> {
                ExecutableWorkoutFactory.from(scheduled)
            }

        // then execution is refused instead of guessing the user's FTP:
        assertEquals("step 1 has no resolved power target or explicit open target", failure.message)
    }

    @Test
    fun `rejects a step with ambiguous time and distance completion`() {
        // given a step carrying both time and distance completion values:
        val scheduled =
            scheduledWorkout(
                WorkoutPlanSummary(
                    description = null,
                    durationSeconds = null,
                    distanceMeters = null,
                    ftpWatts = null,
                    thresholdHeartRateBpm = null,
                    target = "POWER",
                    steps =
                        listOf(
                            leafStep(
                                text = "Ambiguous",
                                durationSeconds = 300,
                                distanceMeters = 1000.0,
                                resolvedPower = WorkoutTargetSummary(value = 200.0, units = "W"),
                            ),
                        ),
                ),
            )

        // when the ambiguous plan is normalized:
        val failure =
            assertFailsWith<WorkoutNotExecutableException> {
                ExecutableWorkoutFactory.from(scheduled)
            }

        // then the execution boundary reports the missing product decision:
        assertEquals("step 1 has both time and distance completion", failure.message)
    }

    private fun scheduledWorkout(plan: WorkoutPlanSummary): ScheduledWorkout =
        ScheduledWorkout(
            reference = WorkoutSourceReference("intervals.icu", "123"),
            name = "Structured ride",
            description = null,
            type = "Ride",
            startAt = null,
            endAt = null,
            indoor = true,
            durationSeconds = plan.durationSeconds,
            distanceMeters = plan.distanceMeters,
            trainingLoad = null,
            target = plan.target,
            workout = plan,
        )

    private fun leafStep(
        text: String,
        durationSeconds: Int? = null,
        distanceMeters: Double? = null,
        freeRide: Boolean? = null,
        intensity: String? = null,
        power: WorkoutTargetSummary? = null,
        resolvedPower: WorkoutTargetSummary? = null,
    ): WorkoutStepSummary =
        WorkoutStepSummary(
            text = text,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
            repeats = null,
            warmup = null,
            cooldown = null,
            intensity = intensity,
            ramp = null,
            untilLapPress = null,
            freeRide = freeRide,
            maxEffort = null,
            hidePower = null,
            power = power,
            resolvedPower = resolvedPower,
            heartRate = null,
            resolvedHeartRate = null,
            pace = null,
            resolvedPace = null,
            cadence = null,
            resolvedDistanceMeters = null,
            steps = emptyList(),
        )
}
