package paceline.workout.domain

import paceline.testsupport.FakePlannedWorkoutCalendar
import paceline.testsupport.FakeWorkoutLibrary
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkoutCatalogTest {
    @Test
    fun `today uses the machine clock timezone when requesting scheduled workouts`() {
        // given a machine clock whose local date has crossed midnight in its timezone:
        val clock =
            Clock.fixed(
                Instant.parse("2026-09-14T23:30:00Z"),
                ZoneId.of("Europe/Warsaw"),
            )
        val calendar = FakePlannedWorkoutCalendar()
        val catalog = WorkoutCatalog(calendar, FakeWorkoutLibrary(), clock)

        // when today's workouts are requested:
        val result = catalog.today()

        // then the calendar source receives the machine-local date:
        assertEquals("2026-09-15", result.date.toString())
        assertEquals(listOf(result.date), calendar.requestedDates)
        assertEquals(clock.instant(), result.fetchedAt)
    }

    @Test
    fun `library lookup stays separate from the today calendar flow`() {
        // given a library containing one saved workout:
        val workout =
            LibraryWorkout(
                reference = WorkoutSourceReference("intervals.icu", "42"),
                name = "Tempo",
                description = null,
                type = "Ride",
                indoor = true,
                durationSeconds = 1800,
                distanceMeters = null,
                trainingLoad = null,
                intensity = null,
                target = "POWER",
                targets = listOf("POWER"),
                folderId = null,
                workout = null,
            )
        val library = FakeWorkoutLibrary(listOf(workout))
        val catalog = WorkoutCatalog(FakePlannedWorkoutCalendar(), library)

        // when the saved workout is looked up:
        val result = catalog.libraryWorkout("42")

        // then the library source is queried without involving today's calendar:
        assertEquals(workout, result)
        assertEquals(listOf("42"), library.requestedWorkoutIds)
    }

    @Test
    fun `execution lookup validates a scheduled source and returns a strict plan`() {
        // given today's calendar contains a selected structured cycling workout:
        val scheduled =
            ScheduledWorkout(
                reference = WorkoutSourceReference("intervals.icu", "event-7"),
                name = "Threshold",
                description = null,
                type = "Ride",
                startAt = null,
                endAt = null,
                indoor = true,
                durationSeconds = 600,
                distanceMeters = null,
                trainingLoad = null,
                target = "POWER",
                workout =
                    WorkoutPlanSummary(
                        description = null,
                        durationSeconds = 600,
                        distanceMeters = null,
                        ftpWatts = 250,
                        thresholdHeartRateBpm = null,
                        target = "POWER",
                        steps =
                            listOf(
                                WorkoutStepSummary(
                                    text = "Work",
                                    durationSeconds = 600,
                                    distanceMeters = null,
                                    repeats = null,
                                    warmup = null,
                                    cooldown = null,
                                    intensity = null,
                                    ramp = null,
                                    power = null,
                                    resolvedPower = WorkoutTargetSummary(value = 250.0, units = "W"),
                                    heartRate = null,
                                    pace = null,
                                    cadence = null,
                                    steps = emptyList(),
                                ),
                            ),
                    ),
            )
        val catalog =
            WorkoutCatalog(
                plannedWorkoutCalendar = FakePlannedWorkoutCalendar(mapOf(LocalDate.of(2026, 9, 14) to listOf(scheduled))),
                workoutLibrary = FakeWorkoutLibrary(),
                clock = Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneId.of("UTC")),
            )

        // when the scheduled source is selected for execution:
        val executable =
            catalog.executable(
                WorkoutSelection(
                    sourceType = WorkoutSourceType.SCHEDULED,
                    reference = scheduled.reference,
                ),
            )

        // then the selection keeps its provider identity and becomes a strict executable plan:
        assertEquals(scheduled.reference, executable.source)
        assertEquals(ExecutableSport.CYCLING, executable.sport)
        assertEquals(250, (executable.steps.single().target as WorkoutStepTarget.Power).lowWatts)
    }
}
