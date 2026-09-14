package paceline.workout.domain

import org.springframework.stereotype.Component
import paceline.workout.ports.PlannedWorkoutCalendar
import paceline.workout.ports.WorkoutLibrary
import java.time.Clock
import java.time.LocalDate

@Component
class WorkoutCatalog(
    private val plannedWorkoutCalendar: PlannedWorkoutCalendar,
    private val workoutLibrary: WorkoutLibrary,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun today(): TodayWorkouts {
        val now = clock.instant()
        val date = LocalDate.now(clock)
        return TodayWorkouts(
            date = date,
            scheduledWorkouts = plannedWorkoutCalendar.uncompletedFor(date),
            fetchedAt = now,
        )
    }

    fun library(): WorkoutLibrarySnapshot =
        WorkoutLibrarySnapshot(
            workouts = workoutLibrary.list(),
            fetchedAt = clock.instant(),
        )

    fun libraryWorkout(workoutId: String): LibraryWorkout = workoutLibrary.find(workoutId) ?: throw WorkoutNotFoundException(workoutId)

    fun executable(selection: WorkoutSelection): ExecutableWorkout =
        when (selection.sourceType) {
            WorkoutSourceType.SCHEDULED -> {
                today()
                    .scheduledWorkouts
                    .firstOrNull { it.reference == selection.reference }
                    ?.let(ExecutableWorkoutFactory::from)
                    ?: throw WorkoutNotFoundException(selection.reference.id)
            }

            WorkoutSourceType.LIBRARY -> {
                libraryWorkout(selection.reference.id)
                    .takeIf { it.reference == selection.reference }
                    ?.let(ExecutableWorkoutFactory::from)
                    ?: throw WorkoutNotFoundException(selection.reference.id)
            }
        }
}
