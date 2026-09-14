package paceline.workout.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

data class WorkoutSourceReference(
    val provider: String,
    val id: String,
)

data class ScheduledWorkout(
    val reference: WorkoutSourceReference,
    val name: String?,
    val description: String?,
    val type: String?,
    val startAt: LocalDateTime?,
    val endAt: LocalDateTime?,
    val indoor: Boolean?,
    val durationSeconds: Int?,
    val distanceMeters: Double?,
    val trainingLoad: Double?,
    val target: String?,
    val workout: WorkoutPlanSummary?,
)

data class LibraryWorkout(
    val reference: WorkoutSourceReference,
    val name: String?,
    val description: String?,
    val type: String?,
    val indoor: Boolean?,
    val durationSeconds: Int?,
    val distanceMeters: Double?,
    val trainingLoad: Double?,
    val intensity: Double?,
    val target: String?,
    val targets: List<String>,
    val folderId: Long?,
    val workout: WorkoutPlanSummary?,
)

data class WorkoutPlanSummary(
    val description: String?,
    val durationSeconds: Int?,
    val distanceMeters: Double?,
    val ftpWatts: Int?,
    val thresholdHeartRateBpm: Int?,
    val target: String?,
    val steps: List<WorkoutStepSummary>,
)

data class WorkoutStepSummary(
    val text: String?,
    val durationSeconds: Int?,
    val distanceMeters: Double?,
    val repeats: Int?,
    val warmup: Boolean?,
    val cooldown: Boolean?,
    val intensity: String?,
    val ramp: Boolean?,
    val untilLapPress: Boolean? = null,
    val freeRide: Boolean? = null,
    val maxEffort: Boolean? = null,
    val hidePower: Boolean? = null,
    val power: WorkoutTargetSummary?,
    val resolvedPower: WorkoutTargetSummary?,
    val heartRate: WorkoutTargetSummary?,
    val resolvedHeartRate: WorkoutTargetSummary? = null,
    val pace: WorkoutTargetSummary?,
    val resolvedPace: WorkoutTargetSummary? = null,
    val cadence: WorkoutTargetSummary?,
    val resolvedDistanceMeters: Double? = null,
    val steps: List<WorkoutStepSummary>,
)

data class WorkoutTargetSummary(
    val value: Double? = null,
    val start: Double? = null,
    val end: Double? = null,
    val units: String? = null,
    val target: String? = null,
)

data class TodayWorkouts(
    val date: LocalDate,
    val scheduledWorkouts: List<ScheduledWorkout>,
    val fetchedAt: Instant,
)

data class WorkoutLibrarySnapshot(
    val workouts: List<LibraryWorkout>,
    val fetchedAt: Instant,
)

class WorkoutNotFoundException(
    workoutId: String,
) : NoSuchElementException("Workout $workoutId was not found")
