package paceline.workout.domain

enum class WorkoutSourceType {
    SCHEDULED,
    LIBRARY,
}

data class WorkoutSelection(
    val sourceType: WorkoutSourceType,
    val reference: WorkoutSourceReference,
)
