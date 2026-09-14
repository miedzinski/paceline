package paceline.workout.ports

import paceline.workout.domain.LibraryWorkout
import paceline.workout.domain.ScheduledWorkout
import java.time.LocalDate

interface PlannedWorkoutCalendar {
    fun uncompletedFor(date: LocalDate): List<ScheduledWorkout>
}

interface WorkoutLibrary {
    fun list(): List<LibraryWorkout>

    fun find(workoutId: String): LibraryWorkout?
}

class WorkoutProviderUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
