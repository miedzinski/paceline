package paceline.training.domain

import java.time.Instant
import java.util.UUID

enum class TrainingSessionPhase {
    NOT_STARTED,
    ACTIVE,
    STOPPED,
}

data class TrainingSessionState(
    val phase: TrainingSessionPhase,
    val sessionId: UUID? = null,
    val startedAt: Instant? = null,
    val changedAt: Instant,
    val ergTargetPowerWatts: Int? = null,
) {
    companion object {
        fun notStarted(now: Instant): TrainingSessionState =
            TrainingSessionState(
                phase = TrainingSessionPhase.NOT_STARTED,
                changedAt = now,
            )

        fun active(
            sessionId: UUID,
            now: Instant,
        ): TrainingSessionState =
            TrainingSessionState(
                phase = TrainingSessionPhase.ACTIVE,
                sessionId = sessionId,
                startedAt = now,
                changedAt = now,
            )
    }
}

class TrainingSessionAlreadyActiveException : IllegalStateException("A training session is already active")

class TrainingSessionNotActiveException : IllegalStateException("No active training session exists")

class TrainingSessionUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class TrainingSessionMismatchException(
    sessionId: UUID,
) : IllegalArgumentException("Training session $sessionId is not the active session")
