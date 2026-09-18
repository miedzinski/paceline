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
    fun `resolves relative power and preserves ramp direction from the plan FTP`() {
        // given a workout whose power targets are expressed as FTP percentages:
        val scheduled =
            scheduledWorkout(
                WorkoutPlanSummary(
                    description = null,
                    durationSeconds = 600,
                    distanceMeters = null,
                    ftpWatts = 200,
                    thresholdHeartRateBpm = null,
                    target = "POWER",
                    steps =
                        listOf(
                            leafStep(
                                text = "Steady",
                                durationSeconds = 300,
                                power = WorkoutTargetSummary(value = 80.0, units = "%ftp"),
                            ),
                            leafStep(
                                text = "Ramp down",
                                durationSeconds = 300,
                                ramp = true,
                                power = WorkoutTargetSummary(start = 75.0, end = 60.0, units = "%ftp"),
                            ),
                        ),
                ),
            )

        // when the plan is normalized for execution:
        val result = ExecutableWorkoutFactory.from(scheduled)

        // then fixed percentages become watts and the descending ramp keeps its ordered endpoints:
        assertEquals(WorkoutStepTarget.Power(160, 160), result.steps[0].target)
        assertEquals(WorkoutStepTarget.Ramp(150, 120), result.steps[1].target)
        assertEquals(
            WorkoutTargetSummary(value = 80.0, units = "%ftp"),
            result.steps[0].sourceTarget,
        )
        assertEquals(
            WorkoutTargetSummary(start = 75.0, end = 60.0, units = "%ftp"),
            result.steps[1].sourceTarget,
        )
    }

    @Test
    fun `executes a backend-resolved power zone as a fixed watt range`() {
        // given a zone target with the backend's resolved absolute watt range:
        val scheduled =
            scheduledWorkout(
                WorkoutPlanSummary(
                    description = null,
                    durationSeconds = 600,
                    distanceMeters = null,
                    ftpWatts = 250,
                    thresholdHeartRateBpm = null,
                    target = "POWER",
                    steps =
                        listOf(
                            leafStep(
                                text = "Endurance",
                                durationSeconds = 600,
                                power = WorkoutTargetSummary(value = 2.0, units = "power_zone"),
                                resolvedPower =
                                    WorkoutTargetSummary(
                                        start = 138.0,
                                        end = 187.0,
                                        units = "W",
                                    ),
                            ),
                        ),
                ),
            )

        // when the plan is normalized for trainer execution:
        val result = ExecutableWorkoutFactory.from(scheduled)

        // then the trainer receives the concrete range and the session can use its midpoint:
        assertEquals(WorkoutStepTarget.Power(138, 187), result.steps.single().target)
        assertEquals(
            WorkoutTargetSummary(value = 2.0, units = "power_zone"),
            result.steps.single().sourceTarget,
        )
    }

    @Test
    fun `executes direct percentage and watt ranges as fixed power ranges`() {
        // given a cycling plan with provider ranges expressed as FTP percentage and watts:
        val scheduled =
            scheduledWorkout(
                WorkoutPlanSummary(
                    description = null,
                    durationSeconds = 1_200,
                    distanceMeters = null,
                    ftpWatts = 200,
                    thresholdHeartRateBpm = null,
                    target = "POWER",
                    steps =
                        listOf(
                            leafStep(
                                text = "Percentage range",
                                durationSeconds = 600,
                                power = WorkoutTargetSummary(start = 60.0, end = 75.0, units = "%ftp"),
                            ),
                            leafStep(
                                text = "Watt range",
                                durationSeconds = 600,
                                power = WorkoutTargetSummary(start = 100.0, end = 120.0, units = "w"),
                            ),
                        ),
                ),
            )

        // when the plan is normalized for trainer execution:
        val result = ExecutableWorkoutFactory.from(scheduled)

        // then both direct ranges become fixed executable watt ranges:
        assertEquals(WorkoutStepTarget.Power(120, 150), result.steps[0].target)
        assertEquals(WorkoutStepTarget.Power(100, 120), result.steps[1].target)
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
        ramp: Boolean? = null,
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
            ramp = ramp,
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
