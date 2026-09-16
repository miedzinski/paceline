package paceline.training.rest

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import paceline.training.domain.ErgProtectionState
import paceline.training.domain.HeartRateSourceNotFoundException
import paceline.training.domain.HeartRateSourceSelectionRequiredException
import paceline.training.domain.TrainingActivityUploadUnavailableException
import paceline.training.domain.TrainingSessionAlreadyActiveException
import paceline.training.domain.TrainingSessionCoordinator
import paceline.training.domain.TrainingSessionMismatchException
import paceline.training.domain.TrainingSessionNotActiveException
import paceline.training.domain.TrainingSessionPauseNotAllowedException
import paceline.training.domain.TrainingSessionResumeNotAllowedException
import paceline.training.domain.TrainingSessionState
import paceline.training.domain.TrainingSessionUnavailableException
import paceline.training.domain.TrainingWorkoutProgress
import paceline.training.domain.WorkoutStepAdvanceNotAllowedException
import paceline.training.domain.WorkoutTargetManagedException
import paceline.training.ports.ActivityUploadException
import paceline.workout.domain.WorkoutCatalog
import paceline.workout.domain.WorkoutNotExecutableException
import paceline.workout.domain.WorkoutNotFoundException
import paceline.workout.domain.WorkoutSelection
import paceline.workout.domain.WorkoutSourceReference
import paceline.workout.domain.WorkoutSourceType
import paceline.workout.domain.WorkoutStepCompletion
import paceline.workout.domain.WorkoutStepTarget
import paceline.workout.ports.WorkoutProviderUnavailableException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/training-sessions")
class TrainingSessionController(
    private val coordinator: TrainingSessionCoordinator,
    private val catalog: WorkoutCatalog,
) {
    @PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun startTrainingSession(
        @RequestBody(required = false) request: StartTrainingSessionRequest?,
    ): TrainingSessionResponse =
        try {
            val workout = request?.workout?.let { selection -> catalog.executable(selection.toDomain()) }
            coordinator
                .start(
                    workout = workout,
                    heartRateSourceId = request?.heartRateSourceId,
                ).toResponse()
        } catch (exception: TrainingSessionAlreadyActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: HeartRateSourceSelectionRequiredException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: HeartRateSourceNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: WorkoutNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: WorkoutNotExecutableException) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, exception.message, exception)
        } catch (exception: WorkoutProviderUnavailableException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.message, exception)
        } catch (exception: TrainingSessionUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        }

    @GetMapping("/current", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getCurrentTrainingSession(): TrainingSessionResponse = coordinator.current().toResponse()

    @PutMapping(
        "/{sessionId}/heart-rate-source",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun selectHeartRateSource(
        @PathVariable sessionId: UUID,
        @RequestBody request: SelectHeartRateSourceRequest,
    ): TrainingSessionResponse =
        try {
            coordinator.selectHeartRateSource(sessionId, request.sourceId).toResponse()
        } catch (exception: TrainingSessionNotActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionMismatchException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: HeartRateSourceNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        }

    @PutMapping(
        "/{sessionId}/erg-target",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun setErgTarget(
        @PathVariable sessionId: UUID,
        @RequestBody request: SetErgTargetRequest,
    ): TrainingSessionResponse =
        try {
            coordinator.setTargetPower(sessionId, request.powerWatts).toResponse()
        } catch (exception: TrainingSessionNotActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionMismatchException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: WorkoutTargetManagedException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
        }

    @PostMapping(
        "/{sessionId}/advance",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun advanceWorkoutStep(
        @PathVariable sessionId: UUID,
    ): TrainingSessionResponse =
        try {
            coordinator.advance(sessionId).toResponse()
        } catch (exception: TrainingSessionNotActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionMismatchException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: WorkoutStepAdvanceNotAllowedException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        }

    @PostMapping(
        "/{sessionId}/pause",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun pauseTrainingSession(
        @PathVariable sessionId: UUID,
    ): TrainingSessionResponse =
        try {
            coordinator.pause(sessionId).toResponse()
        } catch (exception: TrainingSessionNotActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionMismatchException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: TrainingSessionPauseNotAllowedException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        }

    @PostMapping(
        "/{sessionId}/resume",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun resumeTrainingSession(
        @PathVariable sessionId: UUID,
    ): TrainingSessionResponse =
        try {
            coordinator.resume(sessionId).toResponse()
        } catch (exception: TrainingSessionMismatchException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: TrainingSessionResumeNotAllowedException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        }

    @PostMapping(
        "/{sessionId}/stop",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun stopTrainingSession(
        @PathVariable sessionId: UUID,
    ): TrainingSessionResponse =
        try {
            coordinator.stop(sessionId).toResponse()
        } catch (exception: TrainingSessionNotActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionMismatchException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: TrainingSessionUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        }

    @PostMapping(
        "/{sessionId}/upload",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun uploadTrainingSession(
        @PathVariable sessionId: UUID,
    ): TrainingSessionResponse =
        try {
            coordinator.upload(sessionId).toResponse()
        } catch (exception: TrainingSessionMismatchException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: TrainingActivityUploadUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: ActivityUploadException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.message, exception)
        }
}

data class StartTrainingSessionRequest(
    val workout: WorkoutSelectionRequest? = null,
    val heartRateSourceId: String? = null,
)

data class SelectHeartRateSourceRequest(
    val sourceId: String,
)

data class WorkoutSelectionRequest(
    val provider: String,
    val sourceType: WorkoutSourceType,
    val sourceId: String,
)

data class SetErgTargetRequest(
    val powerWatts: Int,
)

data class TrainingSessionResponse(
    val state: String,
    val sessionId: UUID?,
    val startedAt: Instant?,
    val changedAt: Instant,
    val controlMode: String,
    val ergRequestedTargetPowerWatts: Int?,
    val ergTargetPowerWatts: Int?,
    val ergProtection: ErgProtectionResponse,
    val heartRateSourceId: String?,
    val heartRate: HeartRateResponse?,
    val workout: TrainingWorkoutResponse?,
    val activityUpload: TrainingActivityUploadResponse,
)

data class ErgProtectionResponse(
    val state: String,
    val changedAt: Instant?,
    val cadenceRpm: Double?,
    val error: String?,
    val retryAttempt: Int?,
    val nextRetryAt: Instant?,
)

data class HeartRateResponse(
    val heartRateBpm: Int,
    val receivedAt: Instant,
)

data class TrainingActivityUploadResponse(
    val state: String,
    val remoteActivityId: String?,
    val error: String?,
)

data class TrainingWorkoutResponse(
    val provider: String,
    val sourceId: String,
    val name: String,
    val currentStep: Int,
    val totalSteps: Int,
    val stepText: String?,
    val stepStartedAt: Instant,
    val completion: TrainingStepCompletionResponse,
    val target: TrainingStepTargetResponse,
    val completed: Boolean,
)

data class TrainingStepCompletionResponse(
    val kind: String,
    val value: Double?,
)

data class TrainingStepTargetResponse(
    val kind: String,
    val lowWatts: Int?,
    val highWatts: Int?,
    val startWatts: Int? = null,
    val endWatts: Int? = null,
)

private fun WorkoutSelectionRequest.toDomain(): WorkoutSelection =
    WorkoutSelection(
        sourceType = sourceType,
        reference = WorkoutSourceReference(provider = provider, id = sourceId),
    )

private fun TrainingSessionState.toResponse(): TrainingSessionResponse =
    TrainingSessionResponse(
        state = phase.name,
        sessionId = sessionId,
        startedAt = startedAt,
        changedAt = changedAt,
        controlMode = controlMode.name,
        ergRequestedTargetPowerWatts = ergRequestedTargetPowerWatts,
        ergTargetPowerWatts = ergTargetPowerWatts,
        ergProtection = ergProtection.toResponse(),
        heartRateSourceId = heartRateSourceId,
        heartRate =
            heartRate?.let { telemetry ->
                HeartRateResponse(
                    heartRateBpm = telemetry.heartRateBpm,
                    receivedAt = telemetry.receivedAt,
                )
            },
        workout = workout?.toResponse(),
        activityUpload =
            TrainingActivityUploadResponse(
                state = activityUpload.phase.name,
                remoteActivityId = activityUpload.remoteActivityId,
                error = activityUpload.error,
            ),
    )

private fun ErgProtectionState.toResponse(): ErgProtectionResponse =
    ErgProtectionResponse(
        state = status.name,
        changedAt = changedAt,
        cadenceRpm = cadenceRpm,
        error = error,
        retryAttempt = retryAttempt,
        nextRetryAt = nextRetryAt,
    )

private fun TrainingWorkoutProgress.toResponse(): TrainingWorkoutResponse =
    TrainingWorkoutResponse(
        provider = source.provider,
        sourceId = source.id,
        name = name,
        currentStep = currentStepNumber,
        totalSteps = totalSteps,
        stepText = step.text,
        stepStartedAt = stepStartedAt,
        completion = step.completion.toResponse(),
        target = step.target.toResponse(),
        completed = completed,
    )

private fun WorkoutStepCompletion.toResponse(): TrainingStepCompletionResponse =
    when (this) {
        is WorkoutStepCompletion.Time -> {
            TrainingStepCompletionResponse(kind = "TIME", value = seconds.toDouble())
        }

        is WorkoutStepCompletion.Distance -> {
            TrainingStepCompletionResponse(kind = "DISTANCE", value = meters)
        }

        WorkoutStepCompletion.Manual -> {
            TrainingStepCompletionResponse(kind = "MANUAL", value = null)
        }
    }

private fun WorkoutStepTarget.toResponse(): TrainingStepTargetResponse =
    when (this) {
        is WorkoutStepTarget.Power -> {
            TrainingStepTargetResponse(
                kind = "POWER",
                lowWatts = lowWatts,
                highWatts = highWatts,
            )
        }

        is WorkoutStepTarget.Ramp -> {
            TrainingStepTargetResponse(
                kind = "RAMP",
                lowWatts = lowWatts,
                highWatts = highWatts,
                startWatts = startWatts,
                endWatts = endWatts,
            )
        }

        WorkoutStepTarget.Open -> {
            TrainingStepTargetResponse(kind = "OPEN", lowWatts = null, highWatts = null)
        }
    }
