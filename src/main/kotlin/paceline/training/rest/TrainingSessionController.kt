package paceline.training.rest

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import paceline.device.domain.CyclingTelemetry
import paceline.device.domain.HeartRateTelemetry
import paceline.rest.TelemetryProjectionResponse
import paceline.rest.toResponse
import paceline.training.domain.ErgProtectionState
import paceline.training.domain.RideEquipmentSelection
import paceline.training.domain.RideEquipmentSelectionRequiredException
import paceline.training.domain.RideEquipmentState
import paceline.training.domain.RideEquipmentUnavailableException
import paceline.training.domain.RideReadinessReason
import paceline.training.domain.RideRole
import paceline.training.domain.RideRoleState
import paceline.training.domain.RideSourceDescriptor
import paceline.training.domain.RideSourceIncompatibleException
import paceline.training.domain.RideSourceNotFoundException
import paceline.training.domain.RideSourceUnavailableException
import paceline.training.domain.TrainingActivityUploadUnavailableException
import paceline.training.domain.TrainingSessionAlreadyActiveException
import paceline.training.domain.TrainingSessionCoordinator
import paceline.training.domain.TrainingSessionMismatchException
import paceline.training.domain.TrainingSessionNotActiveException
import paceline.training.domain.TrainingSessionNotStoppedException
import paceline.training.domain.TrainingSessionPauseNotAllowedException
import paceline.training.domain.TrainingSessionResumeNotAllowedException
import paceline.training.domain.TrainingSessionState
import paceline.training.domain.TrainingSessionUnavailableException
import paceline.training.domain.TrainingWorkoutProgress
import paceline.training.domain.WorkoutStepAdvanceNotAllowedException
import paceline.training.domain.WorkoutTargetAdjustmentNotAllowedException
import paceline.training.domain.WorkoutTargetManagedException
import paceline.training.ports.ActivityUploadException
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutCatalog
import paceline.workout.domain.WorkoutNotExecutableException
import paceline.workout.domain.WorkoutNotFoundException
import paceline.workout.domain.WorkoutSelection
import paceline.workout.domain.WorkoutSourceReference
import paceline.workout.domain.WorkoutSourceType
import paceline.workout.domain.WorkoutStepCompletion
import paceline.workout.domain.WorkoutStepTarget
import paceline.workout.domain.WorkoutTargetSummary
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
                    equipment = request?.equipment?.toDomain(),
                ).toResponse()
        } catch (exception: TrainingSessionAlreadyActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: RideEquipmentSelectionRequiredException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: RideEquipmentUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: RideSourceNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: RideSourceUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: RideSourceIncompatibleException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
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

    @GetMapping("/equipment", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getRideEquipment(): RideEquipmentResponse = coordinator.equipment().toResponse()

    @PutMapping(
        "/equipment",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun selectRideEquipment(
        @RequestBody request: RideEquipmentRequest,
    ): RideEquipmentResponse =
        try {
            coordinator.selectEquipment(request.toDomain()).toResponse()
        } catch (exception: TrainingSessionAlreadyActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: RideSourceNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: RideSourceUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: RideSourceIncompatibleException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
        }

    @PutMapping(
        "/equipment/{role}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun selectRideRole(
        @PathVariable role: String,
        @RequestBody request: RideRoleRequest,
    ): RideEquipmentResponse =
        try {
            val rideRole = role.toRideRole()
            if (request.sourceId == null) {
                coordinator.clearEquipmentRole(rideRole)
            } else {
                coordinator
                    .selectEquipment(
                        RideEquipmentSelection().withSource(rideRole, request.sourceId),
                    )
            }.toResponse()
        } catch (exception: TrainingSessionAlreadyActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: RideSourceNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: RideSourceUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: RideSourceIncompatibleException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
        }

    @DeleteMapping(
        "/equipment/{role}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun clearRideRole(
        @PathVariable role: String,
    ): RideEquipmentResponse =
        try {
            coordinator.clearEquipmentRole(role.toRideRole()).toResponse()
        } catch (exception: TrainingSessionAlreadyActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
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
        "/{sessionId}/workout-target-adjustment",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun adjustWorkoutTarget(
        @PathVariable sessionId: UUID,
        @RequestBody request: AdjustWorkoutTargetRequest,
    ): TrainingSessionResponse =
        try {
            coordinator.adjustWorkoutTarget(sessionId, request.deltaPercent).toResponse()
        } catch (exception: TrainingSessionNotActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionMismatchException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: WorkoutTargetAdjustmentNotAllowedException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
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
        "/{sessionId}/discard",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun discardTrainingSession(
        @PathVariable sessionId: UUID,
    ): TrainingSessionResponse =
        try {
            coordinator.discard(sessionId).toResponse()
        } catch (exception: TrainingSessionNotActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionMismatchException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
        } catch (exception: TrainingSessionNotStoppedException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
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
        } catch (exception: TrainingSessionNotStoppedException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingActivityUploadUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: ActivityUploadException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.message, exception)
        }
}

data class StartTrainingSessionRequest(
    val workout: WorkoutSelectionRequest? = null,
    val equipment: RideEquipmentRequest? = null,
)

data class RideEquipmentRequest(
    val controlSourceId: String? = null,
    val powerSourceId: String? = null,
    val cadenceSourceId: String? = null,
    val heartRateSourceId: String? = null,
)

data class RideRoleRequest(
    val sourceId: String? = null,
)

data class WorkoutSelectionRequest(
    val provider: String,
    val sourceType: WorkoutSourceType,
    val sourceId: String,
)

data class SetErgTargetRequest(
    val powerWatts: Int,
)

data class AdjustWorkoutTargetRequest(
    val deltaPercent: Long,
)

data class TrainingSessionResponse(
    val state: String,
    val sessionId: UUID?,
    val startedAt: Instant?,
    val changedAt: Instant,
    val controlMode: String,
    val ergRequestedTargetPowerWatts: Int?,
    val ergTargetPowerWatts: Int?,
    val workoutPowerTargetPercent: Long?,
    val ergProtection: ErgProtectionResponse,
    val trainerConnection: String,
    val trainerConnectionRetryAttempt: Int?,
    val trainerConnectionError: String?,
    val telemetry: TrainingSessionTelemetryResponse?,
    val workout: TrainingWorkoutResponse?,
    val activityUpload: TrainingActivityUploadResponse,
    val equipment: RideEquipmentResponse? = null,
)

data class RideEquipmentResponse(
    val readiness: String,
    val ready: Boolean,
    val readinessReasons: List<RideReadinessReasonResponse>,
    val assignments: RideEquipmentAssignmentsResponse,
    val roles: List<RideRoleResponse>,
    val sources: List<RideSourceResponse>,
)

data class RideReadinessReasonResponse(
    val code: String,
    val role: String,
    val sourceId: String?,
    val compatibleSourceIds: List<String>,
    val message: String,
)

data class RideEquipmentAssignmentsResponse(
    val controlSourceId: String?,
    val powerSourceId: String?,
    val cadenceSourceId: String?,
    val heartRateSourceId: String?,
)

data class RideRoleResponse(
    val role: String,
    val sourceId: String?,
    val status: String,
    val compatibleSourceIds: List<String>,
)

data class RideSourceResponse(
    val id: String,
    val state: String,
    val name: String?,
    val capabilities: Set<String>,
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

data class SessionCyclingTelemetryResponse(
    val powerWatts: Int?,
    val cadenceRpm: Double?,
    val speedKph: Double?,
    val distanceMeters: Double?,
    val receivedAt: Instant,
)

data class TrainingSessionTelemetryResponse(
    val cycling: TelemetryProjectionResponse<SessionCyclingTelemetryResponse>,
    val heartRate: TelemetryProjectionResponse<HeartRateResponse>,
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
    val steps: List<TrainingWorkoutStepResponse>,
    val stepText: String?,
    val stepStartedAt: Instant,
    val completion: TrainingStepCompletionResponse,
    val target: TrainingStepTargetResponse,
    val completed: Boolean,
)

data class TrainingWorkoutStepResponse(
    val text: String?,
    val intensity: String?,
    val completion: TrainingStepCompletionResponse,
    val target: TrainingStepTargetResponse,
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
    val sourceTarget: TrainingSourceTargetResponse? = null,
)

data class TrainingSourceTargetResponse(
    val value: Double?,
    val start: Double?,
    val end: Double?,
    val units: String?,
    val target: String?,
)

private fun WorkoutSelectionRequest.toDomain(): WorkoutSelection =
    WorkoutSelection(
        sourceType = sourceType,
        reference = WorkoutSourceReference(provider = provider, id = sourceId),
    )

private fun RideEquipmentRequest.toDomain(): RideEquipmentSelection =
    RideEquipmentSelection(
        controlSourceId = controlSourceId,
        powerSourceId = powerSourceId,
        cadenceSourceId = cadenceSourceId,
        heartRateSourceId = heartRateSourceId,
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
        workoutPowerTargetPercent = workoutPowerTargetPercent,
        ergProtection = ergProtection.toResponse(),
        trainerConnection = trainerConnection.name,
        trainerConnectionRetryAttempt = trainerConnectionRetryAttempt,
        trainerConnectionError = trainerConnectionError,
        telemetry =
            telemetry?.let { snapshot ->
                TrainingSessionTelemetryResponse(
                    cycling = snapshot.cycling.toResponse(CyclingTelemetry::toResponse),
                    heartRate = snapshot.heartRate.toResponse(HeartRateTelemetry::toResponse),
                )
            },
        workout = workout?.toResponse(),
        activityUpload =
            TrainingActivityUploadResponse(
                state = activityUpload.phase.name,
                remoteActivityId = activityUpload.remoteActivityId,
                error = activityUpload.error,
            ),
        equipment = equipment?.toResponse(),
    )

private fun CyclingTelemetry.toResponse(): SessionCyclingTelemetryResponse =
    SessionCyclingTelemetryResponse(
        powerWatts = powerWatts,
        cadenceRpm = cadenceRpm,
        speedKph = speedKph,
        distanceMeters = distanceMeters,
        receivedAt = receivedAt,
    )

private fun HeartRateTelemetry.toResponse(): HeartRateResponse =
    HeartRateResponse(
        heartRateBpm = heartRateBpm,
        receivedAt = receivedAt,
    )

private fun RideEquipmentState.toResponse(): RideEquipmentResponse =
    RideEquipmentResponse(
        readiness = readiness.name,
        ready = ready,
        readinessReasons = readinessReasons.map(RideReadinessReason::toResponse),
        assignments =
            RideEquipmentAssignmentsResponse(
                controlSourceId = selection.controlSourceId,
                powerSourceId = selection.powerSourceId,
                cadenceSourceId = selection.cadenceSourceId,
                heartRateSourceId = selection.heartRateSourceId,
            ),
        roles =
            RideRole.entries.map { role ->
                role(role).toResponse()
            },
        sources = sources.map(RideSourceDescriptor::toResponse),
    )

private fun RideReadinessReason.toResponse(): RideReadinessReasonResponse =
    RideReadinessReasonResponse(
        code = code.name,
        role = role.name,
        sourceId = sourceId,
        compatibleSourceIds = compatibleSourceIds,
        message = message,
    )

private fun RideRoleState.toResponse(): RideRoleResponse =
    RideRoleResponse(
        role = role.name,
        sourceId = sourceId,
        status = status.name,
        compatibleSourceIds = compatibleSourceIds,
    )

private fun RideSourceDescriptor.toResponse(): RideSourceResponse =
    RideSourceResponse(
        id = id,
        state = state.name,
        name = device?.name,
        capabilities = capabilities.map { capability -> capability.name }.toSet(),
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
        steps = steps.map(ExecutableWorkoutStep::toResponse),
        stepText = step.text,
        stepStartedAt = stepStartedAt,
        completion = step.completion.toResponse(),
        target = step.target.toResponse(step.sourceTarget),
        completed = completed,
    )

private fun ExecutableWorkoutStep.toResponse(): TrainingWorkoutStepResponse =
    TrainingWorkoutStepResponse(
        text = text,
        intensity = intensity,
        completion = completion.toResponse(),
        target = target.toResponse(sourceTarget),
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

private fun WorkoutStepTarget.toResponse(sourceTarget: WorkoutTargetSummary?): TrainingStepTargetResponse =
    when (this) {
        is WorkoutStepTarget.Power -> {
            TrainingStepTargetResponse(
                kind = "POWER",
                lowWatts = lowWatts,
                highWatts = highWatts,
                sourceTarget = sourceTarget?.toResponse(),
            )
        }

        is WorkoutStepTarget.Ramp -> {
            TrainingStepTargetResponse(
                kind = "RAMP",
                lowWatts = lowWatts,
                highWatts = highWatts,
                startWatts = startWatts,
                endWatts = endWatts,
                sourceTarget = sourceTarget?.toResponse(),
            )
        }

        WorkoutStepTarget.Open -> {
            TrainingStepTargetResponse(kind = "OPEN", lowWatts = null, highWatts = null)
        }
    }

private fun WorkoutTargetSummary.toResponse(): TrainingSourceTargetResponse =
    TrainingSourceTargetResponse(
        value = value,
        start = start,
        end = end,
        units = units,
        target = target,
    )

private fun String.toRideRole(): RideRole =
    when (trim().uppercase().replace('-', '_')) {
        "CONTROL", "RESISTANCE_CONTROL" -> {
            RideRole.RESISTANCE_CONTROL
        }

        "POWER" -> {
            RideRole.POWER
        }

        "CADENCE" -> {
            RideRole.CADENCE
        }

        "HEART_RATE" -> {
            RideRole.HEART_RATE
        }

        else -> {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unknown ride role '$this'",
            )
        }
    }
