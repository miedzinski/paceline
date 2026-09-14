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
import paceline.training.domain.TrainingSessionAlreadyActiveException
import paceline.training.domain.TrainingSessionCoordinator
import paceline.training.domain.TrainingSessionMismatchException
import paceline.training.domain.TrainingSessionNotActiveException
import paceline.training.domain.TrainingSessionState
import paceline.training.domain.TrainingSessionUnavailableException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/training-sessions")
class TrainingSessionController(
    private val coordinator: TrainingSessionCoordinator,
) {
    @PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun startTrainingSession(): TrainingSessionResponse =
        try {
            coordinator.start().toResponse()
        } catch (exception: TrainingSessionAlreadyActiveException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: TrainingSessionUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        }

    @GetMapping("/current", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getCurrentTrainingSession(): TrainingSessionResponse = coordinator.current().toResponse()

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
        } catch (exception: TrainingSessionUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, exception.message, exception)
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
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
}

data class SetErgTargetRequest(
    val powerWatts: Int,
)

data class TrainingSessionResponse(
    val state: String,
    val sessionId: UUID?,
    val startedAt: Instant?,
    val changedAt: Instant,
    val ergTargetPowerWatts: Int?,
)

private fun TrainingSessionState.toResponse(): TrainingSessionResponse =
    TrainingSessionResponse(
        state = phase.name,
        sessionId = sessionId,
        startedAt = startedAt,
        changedAt = changedAt,
        ergTargetPowerWatts = ergTargetPowerWatts,
    )
