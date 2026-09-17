package paceline.training.domain

import paceline.device.domain.HeartRateTelemetry
import paceline.workout.domain.ExecutableWorkout
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutSourceReference
import java.time.Instant
import java.util.UUID

enum class TrainingSessionPhase {
    NOT_STARTED,
    ACTIVE,
    PAUSED,
    STOPPED,
    COMPLETED,
}

enum class TrainingControlMode {
    ERG,
    FREE_RIDE,
}

enum class TrainerConnectionStatus {
    NOT_ACTIVE,
    CONNECTED,
    INTERRUPTED,
    RECONNECTING,
}

enum class TrainingActivityUploadPhase {
    UNAVAILABLE,
    AVAILABLE,
    UPLOADING,
    FAILED,
}

data class TrainingActivityUploadState(
    val phase: TrainingActivityUploadPhase,
    val remoteActivityId: String? = null,
    val error: String? = null,
) {
    companion object {
        fun unavailable(): TrainingActivityUploadState = TrainingActivityUploadState(phase = TrainingActivityUploadPhase.UNAVAILABLE)

        fun available(): TrainingActivityUploadState = TrainingActivityUploadState(phase = TrainingActivityUploadPhase.AVAILABLE)
    }
}

data class TrainingWorkoutProgress(
    val source: WorkoutSourceReference,
    val name: String,
    val currentStepNumber: Int,
    val totalSteps: Int,
    val step: ExecutableWorkoutStep,
    val stepStartedAt: Instant,
    val completed: Boolean = false,
) {
    init {
        require(currentStepNumber in 1..totalSteps) {
            "The current workout step must be within the executable workout"
        }
    }

    companion object {
        fun firstStep(
            workout: ExecutableWorkout,
            now: Instant,
        ): TrainingWorkoutProgress =
            TrainingWorkoutProgress(
                source = workout.source,
                name = workout.name,
                currentStepNumber = 1,
                totalSteps = workout.steps.size,
                step = workout.steps.first(),
                stepStartedAt = now,
            )
    }
}

data class TrainingSessionState(
    val phase: TrainingSessionPhase,
    val sessionId: UUID? = null,
    val startedAt: Instant? = null,
    val changedAt: Instant,
    val controlMode: TrainingControlMode = TrainingControlMode.ERG,
    val ergRequestedTargetPowerWatts: Int? = null,
    val ergTargetPowerWatts: Int? = null,
    val workoutPowerTargetPercent: Long? = null,
    val ergProtection: ErgProtectionState = ErgProtectionState.inactive(),
    val trainerConnection: TrainerConnectionStatus = TrainerConnectionStatus.NOT_ACTIVE,
    val trainerConnectionRetryAttempt: Int? = null,
    val trainerConnectionError: String? = null,
    val heartRateSourceId: String? = null,
    val heartRate: HeartRateTelemetry? = null,
    val workout: TrainingWorkoutProgress? = null,
    val activityUpload: TrainingActivityUploadState = TrainingActivityUploadState.unavailable(),
    val pauseStartedAt: Instant? = null,
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
            workout: TrainingWorkoutProgress? = null,
        ): TrainingSessionState =
            TrainingSessionState(
                phase = TrainingSessionPhase.ACTIVE,
                sessionId = sessionId,
                startedAt = now,
                changedAt = now,
                trainerConnection = TrainerConnectionStatus.CONNECTED,
                workout = workout,
            )
    }
}

class TrainingSessionAlreadyActiveException : IllegalStateException("A training session is already active")

class TrainingSessionNotActiveException : IllegalStateException("No active training session exists")

class TrainingSessionNotStoppedException : IllegalStateException("The training session must be stopped first")

class TrainingSessionUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class HeartRateSourceSelectionRequiredException :
    IllegalStateException(
        "More than one heart-rate source is connected; select the source to use for this session",
    )

class HeartRateSourceNotFoundException(
    val sourceId: String,
) : IllegalArgumentException("Heart-rate source $sourceId is not connected")

class TrainingActivityUploadUnavailableException(
    message: String,
) : IllegalStateException(message)

class TrainingSessionMismatchException(
    sessionId: UUID,
) : IllegalArgumentException("Training session $sessionId is not the active session")

class WorkoutTargetManagedException : IllegalStateException("The active workout controls the ERG target")

class WorkoutTargetAdjustmentNotAllowedException(
    message: String = "The active workout has no adjustable power target",
) : IllegalStateException(message)

class WorkoutStepAdvanceNotAllowedException(
    message: String,
) : IllegalStateException(message)

class TrainingSessionPauseNotAllowedException(
    message: String,
) : IllegalStateException(message)

class TrainingSessionResumeNotAllowedException(
    message: String,
) : IllegalStateException(message)
