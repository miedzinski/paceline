package paceline.workout.rest

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import paceline.workout.domain.LibraryWorkout
import paceline.workout.domain.ScheduledWorkout
import paceline.workout.domain.TodayWorkouts
import paceline.workout.domain.WorkoutCatalog
import paceline.workout.domain.WorkoutLibrarySnapshot
import paceline.workout.domain.WorkoutNotFoundException
import paceline.workout.domain.WorkoutPlanSummary
import paceline.workout.domain.WorkoutStepSummary
import paceline.workout.domain.WorkoutTargetSummary
import paceline.workout.domain.WorkoutZoneDistribution
import paceline.workout.ports.WorkoutProviderUnavailableException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@RestController
@RequestMapping("/workouts")
class WorkoutController(
    private val catalog: WorkoutCatalog,
) {
    @GetMapping("/today", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun today(): TodayWorkoutsResponse =
        try {
            catalog.today().toResponse()
        } catch (exception: WorkoutProviderUnavailableException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.message, exception)
        }

    @GetMapping("/library", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun library(): WorkoutLibraryResponse =
        try {
            catalog.library().toResponse()
        } catch (exception: WorkoutProviderUnavailableException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.message, exception)
        }

    @GetMapping("/library/{workoutId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun libraryWorkout(
        @PathVariable workoutId: String,
    ): LibraryWorkoutResponse =
        try {
            catalog.libraryWorkout(workoutId).toResponse()
        } catch (exception: WorkoutNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: WorkoutProviderUnavailableException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.message, exception)
        }
}

data class TodayWorkoutsResponse(
    val date: LocalDate,
    val scheduledWorkouts: List<ScheduledWorkoutResponse>,
    val fetchedAt: Instant,
)

data class WorkoutLibraryResponse(
    val workouts: List<LibraryWorkoutResponse>,
    val fetchedAt: Instant,
)

data class ScheduledWorkoutResponse(
    val provider: String,
    val sourceEventId: String,
    val name: String?,
    val description: String?,
    val type: String?,
    val startAt: LocalDateTime?,
    val endAt: LocalDateTime?,
    val indoor: Boolean?,
    val durationSeconds: Int?,
    val distanceMeters: Double?,
    val trainingLoad: Double?,
    val plannedZoneDistribution: List<WorkoutZoneDistributionResponse>?,
    val target: String?,
    val workout: WorkoutDefinitionResponse?,
)

data class LibraryWorkoutResponse(
    val provider: String,
    val sourceWorkoutId: String,
    val name: String?,
    val description: String?,
    val type: String?,
    val indoor: Boolean?,
    val durationSeconds: Int?,
    val distanceMeters: Double?,
    val trainingLoad: Double?,
    val plannedZoneDistribution: List<WorkoutZoneDistributionResponse>?,
    val intensity: Double?,
    val target: String?,
    val targets: List<String>,
    val folderId: Long?,
    val workout: WorkoutDefinitionResponse?,
)

data class WorkoutDefinitionResponse(
    val description: String?,
    val durationSeconds: Int?,
    val distanceMeters: Double?,
    val ftpWatts: Int?,
    val thresholdHeartRateBpm: Int?,
    val target: String?,
    val steps: List<WorkoutStepResponse>,
)

data class WorkoutZoneDistributionResponse(
    val zone: String,
    val durationSeconds: Int,
)

data class WorkoutStepResponse(
    val text: String?,
    val durationSeconds: Int?,
    val distanceMeters: Double?,
    val repeats: Int?,
    val warmup: Boolean?,
    val cooldown: Boolean?,
    val intensity: String?,
    val ramp: Boolean?,
    val power: WorkoutTargetResponse?,
    val resolvedPower: WorkoutTargetResponse?,
    val heartRate: WorkoutTargetResponse?,
    val pace: WorkoutTargetResponse?,
    val cadence: WorkoutTargetResponse?,
    val steps: List<WorkoutStepResponse>,
)

data class WorkoutTargetResponse(
    val value: Double?,
    val start: Double?,
    val end: Double?,
    val units: String?,
    val target: String?,
)

private fun TodayWorkouts.toResponse(): TodayWorkoutsResponse =
    TodayWorkoutsResponse(
        date = date,
        scheduledWorkouts = scheduledWorkouts.map(ScheduledWorkout::toResponse),
        fetchedAt = fetchedAt,
    )

private fun WorkoutLibrarySnapshot.toResponse(): WorkoutLibraryResponse =
    WorkoutLibraryResponse(
        workouts = workouts.map(LibraryWorkout::toResponse),
        fetchedAt = fetchedAt,
    )

private fun ScheduledWorkout.toResponse(): ScheduledWorkoutResponse =
    ScheduledWorkoutResponse(
        provider = reference.provider,
        sourceEventId = reference.id,
        name = name,
        description = description,
        type = type,
        startAt = startAt,
        endAt = endAt,
        indoor = indoor,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        trainingLoad = trainingLoad,
        plannedZoneDistribution = workout?.plannedZoneDistribution?.map(WorkoutZoneDistribution::toResponse),
        target = target,
        workout = workout?.toResponse(),
    )

private fun LibraryWorkout.toResponse(): LibraryWorkoutResponse =
    LibraryWorkoutResponse(
        provider = reference.provider,
        sourceWorkoutId = reference.id,
        name = name,
        description = description,
        type = type,
        indoor = indoor,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        trainingLoad = trainingLoad,
        plannedZoneDistribution = workout?.plannedZoneDistribution?.map(WorkoutZoneDistribution::toResponse),
        intensity = intensity,
        target = target,
        targets = targets,
        folderId = folderId,
        workout = workout?.toResponse(),
    )

private fun WorkoutPlanSummary.toResponse(): WorkoutDefinitionResponse =
    WorkoutDefinitionResponse(
        description = description,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        ftpWatts = ftpWatts,
        thresholdHeartRateBpm = thresholdHeartRateBpm,
        target = target,
        steps = steps.map(WorkoutStepSummary::toResponse),
    )

private fun WorkoutZoneDistribution.toResponse(): WorkoutZoneDistributionResponse =
    WorkoutZoneDistributionResponse(
        zone = zone,
        durationSeconds = durationSeconds,
    )

private fun WorkoutStepSummary.toResponse(): WorkoutStepResponse =
    WorkoutStepResponse(
        text = text,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        repeats = repeats,
        warmup = warmup,
        cooldown = cooldown,
        intensity = intensity,
        ramp = ramp,
        power = power?.toResponse(),
        resolvedPower = resolvedPower?.toResponse(),
        heartRate = heartRate?.toResponse(),
        pace = pace?.toResponse(),
        cadence = cadence?.toResponse(),
        steps = steps.map(WorkoutStepSummary::toResponse),
    )

private fun WorkoutTargetSummary.toResponse(): WorkoutTargetResponse =
    WorkoutTargetResponse(
        value = value,
        start = start,
        end = end,
        units = units,
        target = target,
    )
