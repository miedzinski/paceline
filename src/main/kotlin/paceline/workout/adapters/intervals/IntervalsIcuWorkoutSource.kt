package paceline.workout.adapters.intervals

import org.springframework.stereotype.Component
import paceline.intervals.adapters.IntervalsCalendarEventDto
import paceline.intervals.adapters.IntervalsIcuClient
import paceline.intervals.adapters.IntervalsLibraryWorkoutDto
import paceline.intervals.adapters.IntervalsWorkoutDocumentDto
import paceline.intervals.adapters.IntervalsWorkoutStepDto
import paceline.intervals.adapters.IntervalsWorkoutValueDto
import paceline.workout.domain.LibraryWorkout
import paceline.workout.domain.ScheduledWorkout
import paceline.workout.domain.WorkoutPlanSummary
import paceline.workout.domain.WorkoutSourceReference
import paceline.workout.domain.WorkoutStepSummary
import paceline.workout.domain.WorkoutTargetSummary
import paceline.workout.ports.PlannedWorkoutCalendar
import paceline.workout.ports.WorkoutLibrary
import java.time.LocalDate
import java.time.LocalDateTime

@Component
class IntervalsIcuWorkoutSource(
    private val client: IntervalsIcuClient,
) : PlannedWorkoutCalendar,
    WorkoutLibrary {
    override fun uncompletedFor(date: LocalDate): List<ScheduledWorkout> {
        val completedEventIds =
            client
                .completedActivities(date)
                .mapNotNull { it.pairedEventId }
                .map(Long::toString)
                .toSet()

        return client
            .calendarEvents(date)
            .filter { it.category.equals("WORKOUT", ignoreCase = true) }
            .filterNot { it.id.toString() in completedEventIds }
            .map(::toScheduledWorkout)
    }

    override fun list(): List<LibraryWorkout> = client.libraryWorkouts().map(::toLibraryWorkout)

    override fun find(workoutId: String): LibraryWorkout? = client.libraryWorkout(workoutId)?.let(::toLibraryWorkout)

    private fun toScheduledWorkout(event: IntervalsCalendarEventDto): ScheduledWorkout =
        ScheduledWorkout(
            reference = WorkoutSourceReference(PROVIDER, event.id.toString()),
            name = event.name,
            description = event.description,
            type = event.type,
            startAt = parseLocalDateTime(event.startDateLocal),
            endAt = parseLocalDateTime(event.endDateLocal),
            indoor = event.indoor,
            durationSeconds = event.movingTimeSeconds,
            distanceMeters = event.distance,
            trainingLoad = event.trainingLoad,
            target = event.target,
            workout = event.workoutDocument?.let(::toWorkoutPlanSummary),
        )

    private fun toLibraryWorkout(workout: IntervalsLibraryWorkoutDto): LibraryWorkout =
        LibraryWorkout(
            reference = WorkoutSourceReference(PROVIDER, workout.id.toString()),
            name = workout.name,
            description = workout.description,
            type = workout.type,
            indoor = workout.indoor,
            durationSeconds = workout.movingTimeSeconds,
            distanceMeters = workout.distance,
            trainingLoad = workout.trainingLoad,
            intensity = workout.intensity,
            target = workout.target,
            targets = workout.targets.orEmpty(),
            folderId = workout.folderId,
            workout = workout.workoutDocument?.let(::toWorkoutPlanSummary),
        )

    private fun toWorkoutPlanSummary(document: IntervalsWorkoutDocumentDto): WorkoutPlanSummary =
        WorkoutPlanSummary(
            description = document.description,
            durationSeconds = document.duration,
            distanceMeters = document.distance,
            ftpWatts = document.ftp,
            thresholdHeartRateBpm = document.lthr,
            target = document.target,
            steps = document.steps.orEmpty().map(::toWorkoutStepSummary),
        )

    private fun toWorkoutStepSummary(step: IntervalsWorkoutStepDto): WorkoutStepSummary =
        WorkoutStepSummary(
            text = step.text,
            durationSeconds = step.duration,
            distanceMeters = step.distance,
            repeats = step.reps,
            warmup = step.warmup,
            cooldown = step.cooldown,
            intensity = step.intensity,
            ramp = step.ramp,
            untilLapPress = step.untilLapPress,
            freeRide = step.freeRide,
            maxEffort = step.maxEffort,
            hidePower = step.hidePower,
            power = step.power?.let(::toWorkoutTargetSummary),
            resolvedPower = step.resolvedPower?.let(::toWorkoutTargetSummary),
            heartRate = step.hr?.let(::toWorkoutTargetSummary),
            resolvedHeartRate = step.resolvedHeartRate?.let(::toWorkoutTargetSummary),
            pace = step.pace?.let(::toWorkoutTargetSummary),
            resolvedPace = step.resolvedPace?.let(::toWorkoutTargetSummary),
            cadence = step.cadence?.let(::toWorkoutTargetSummary),
            resolvedDistanceMeters = step.resolvedDistanceMeters,
            steps = step.steps.orEmpty().map(::toWorkoutStepSummary),
        )

    private fun toWorkoutTargetSummary(value: IntervalsWorkoutValueDto): WorkoutTargetSummary =
        WorkoutTargetSummary(
            value = value.value,
            start = value.start,
            end = value.end,
            units = value.units,
            target = value.target,
        )

    private fun parseLocalDateTime(value: String?): LocalDateTime? =
        value?.let {
            runCatching { LocalDateTime.parse(it) }
                .getOrElse { LocalDate.parse(value).atStartOfDay() }
        }

    private companion object {
        const val PROVIDER = "intervals.icu"
    }
}
