package paceline.workout.adapters

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
import kotlin.math.roundToInt

private val FTP_PERCENT_UNITS = setOf("%ftp", "%offtp", "percentftp")

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

        val workouts =
            client
                .calendarEvents(date)
                .filter { it.category.equals("WORKOUT", ignoreCase = true) }
                .filterNot { it.id.toString() in completedEventIds }
        val fallbackFtpWatts = fallbackFtpWatts(workouts.mapNotNull { it.workoutDocument })
        return workouts.map { toScheduledWorkout(it, fallbackFtpWatts) }
    }

    override fun list(): List<LibraryWorkout> {
        val workouts = client.libraryWorkouts()
        val fallbackFtpWatts = fallbackFtpWatts(workouts.mapNotNull { it.workoutDocument })
        return workouts.map { toLibraryWorkout(it, fallbackFtpWatts) }
    }

    override fun find(workoutId: String): LibraryWorkout? {
        val workout = client.libraryWorkout(workoutId) ?: return null
        val fallbackFtpWatts = fallbackFtpWatts(listOfNotNull(workout.workoutDocument))
        return toLibraryWorkout(workout, fallbackFtpWatts)
    }

    private fun toScheduledWorkout(
        event: IntervalsCalendarEventDto,
        fallbackFtpWatts: Int?,
    ): ScheduledWorkout =
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
            workout =
                event.workoutDocument?.let { document ->
                    toWorkoutPlanSummary(
                        document,
                        firstUsableFtpWatts(document.ftp, event.ftpWatts, fallbackFtpWatts),
                    )
                },
        )

    private fun toLibraryWorkout(
        workout: IntervalsLibraryWorkoutDto,
        fallbackFtpWatts: Int?,
    ): LibraryWorkout =
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
            workout =
                workout.workoutDocument?.let { document ->
                    toWorkoutPlanSummary(document, firstUsableFtpWatts(document.ftp, fallbackFtpWatts))
                },
        )

    private fun toWorkoutPlanSummary(
        document: IntervalsWorkoutDocumentDto,
        ftpWatts: Int?,
    ): WorkoutPlanSummary {
        val effectiveFtpWatts = ftpWatts?.takeIf { it > 0 }
        return WorkoutPlanSummary(
            description = document.description,
            durationSeconds = document.duration,
            distanceMeters = document.distance,
            ftpWatts = effectiveFtpWatts,
            thresholdHeartRateBpm = document.lthr,
            target = document.target,
            steps = document.steps.orEmpty().map { step -> toWorkoutStepSummary(step, effectiveFtpWatts) },
        )
    }

    private fun toWorkoutStepSummary(
        step: IntervalsWorkoutStepDto,
        ftpWatts: Int?,
    ): WorkoutStepSummary =
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
            resolvedPower = step.power?.toResolvedPower(ftpWatts),
            heartRate = step.hr?.let(::toWorkoutTargetSummary),
            resolvedHeartRate = step.resolvedHeartRate?.let(::toWorkoutTargetSummary),
            pace = step.pace?.let(::toWorkoutTargetSummary),
            resolvedPace = step.resolvedPace?.let(::toWorkoutTargetSummary),
            cadence = step.cadence?.let(::toWorkoutTargetSummary),
            resolvedDistanceMeters = step.resolvedDistanceMeters,
            steps = step.steps.orEmpty().map { child -> toWorkoutStepSummary(child, ftpWatts) },
        )

    private fun fallbackFtpWatts(documents: List<IntervalsWorkoutDocumentDto>): Int? {
        if (documents.none { it.requiresFtpResolution() && it.ftp?.takeIf { ftp -> ftp > 0 } == null }) {
            return null
        }

        val settings = client.sportSettings(CYCLING_SPORT)
        return settings.indoorFtp?.takeIf { it > 0 } ?: settings.ftp?.takeIf { it > 0 }
    }

    private fun firstUsableFtpWatts(vararg candidates: Int?): Int? =
        candidates.firstNotNullOfOrNull { candidate -> candidate?.takeIf { it > 0 } }

    private fun IntervalsWorkoutDocumentDto.requiresFtpResolution(): Boolean = steps.orEmpty().any { it.requiresFtpResolution() }

    private fun IntervalsWorkoutStepDto.requiresFtpResolution(): Boolean =
        power?.units.normalizedPowerUnits() in FTP_PERCENT_UNITS ||
            steps.orEmpty().any { it.requiresFtpResolution() }

    private fun IntervalsWorkoutValueDto.toResolvedPower(ftpWatts: Int?): WorkoutTargetSummary? {
        if (units.normalizedPowerUnits() !in FTP_PERCENT_UNITS) {
            return null
        }

        val validFtpWatts = ftpWatts?.takeIf { it > 0 } ?: return null
        if (value == null && start == null && end == null) {
            return null
        }

        fun resolve(percent: Double?): Double? =
            percent
                ?.takeIf(Double::isFinite)
                ?.let { ((it / 100.0) * validFtpWatts).roundToInt().toDouble() }

        return WorkoutTargetSummary(
            value = resolve(value),
            start = resolve(start),
            end = resolve(end),
            units = "W",
            target = target,
        )
    }

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
        const val CYCLING_SPORT = "Ride"
        const val PROVIDER = "intervals.icu"
    }
}

private fun String?.normalizedPowerUnits(): String? =
    this
        ?.trim()
        ?.lowercase()
        ?.replace(" ", "")
