package paceline.testsupport

import paceline.workout.domain.LibraryWorkout
import paceline.workout.domain.ScheduledWorkout
import paceline.workout.ports.PlannedWorkoutCalendar
import paceline.workout.ports.WorkoutLibrary
import java.time.LocalDate

class FakePlannedWorkoutCalendar(
    private val workoutsByDate: Map<LocalDate, List<ScheduledWorkout>> = emptyMap(),
) : PlannedWorkoutCalendar {
    val requestedDates = mutableListOf<LocalDate>()

    override fun uncompletedFor(date: LocalDate): List<ScheduledWorkout> {
        requestedDates += date
        return workoutsByDate[date].orEmpty()
    }
}

class FakeWorkoutLibrary(
    private val workouts: List<LibraryWorkout> = emptyList(),
) : WorkoutLibrary {
    val requestedWorkoutIds = mutableListOf<String>()

    override fun list(): List<LibraryWorkout> = workouts

    override fun find(workoutId: String): LibraryWorkout? {
        requestedWorkoutIds += workoutId
        return workouts.firstOrNull { it.reference.id == workoutId }
    }
}
