package paceline.training.domain

import org.springframework.stereotype.Component
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.IndoorBikePowerControl
import paceline.training.ports.TrainingDevice
import paceline.workout.domain.ExecutableWorkout
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutStepCompletion
import paceline.workout.domain.WorkoutStepTarget
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

@Component
class TrainingSessionCoordinator(
    private val trainingDevice: TrainingDevice,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var state = TrainingSessionState.notStarted(clock.instant())
    private var activePowerControl: IndoorBikePowerControl? = null
    private var activeWorkout: ExecutableWorkout? = null
    private var stepDistanceStartMeters: Double? = null

    @Synchronized
    fun current(): TrainingSessionState = state

    @Synchronized
    fun start(workout: ExecutableWorkout? = null): TrainingSessionState {
        if (state.phase == TrainingSessionPhase.ACTIVE) {
            throw TrainingSessionAlreadyActiveException()
        }

        val powerControl =
            trainingDevice.currentPowerControl()
                ?: throw TrainingSessionUnavailableException(
                    "A connected device with ERG power control is required to start a training session",
                )
        try {
            powerControl.requestControl()
        } catch (exception: Exception) {
            throw TrainingSessionUnavailableException(
                "The connected device did not grant ERG control",
                exception,
            )
        }

        val now = clock.instant()
        val progress = workout?.let { TrainingWorkoutProgress.firstStep(it, now) }
        val initialTarget = progress?.let { targetPower(it.step) }
        if (initialTarget != null) {
            setTarget(powerControl, initialTarget, "initial workout")
        }

        activePowerControl = powerControl
        activeWorkout = workout
        stepDistanceStartMeters =
            progress
                ?.step
                ?.completion
                ?.let { completion ->
                    if (completion is WorkoutStepCompletion.Distance) {
                        trainingDevice.currentTelemetry()?.distanceMeters
                    } else {
                        null
                    }
                }
        state =
            TrainingSessionState
                .active(
                    sessionId = UUID.randomUUID(),
                    now = now,
                    workout = progress,
                ).copy(ergTargetPowerWatts = initialTarget)
        return state
    }

    @Synchronized
    fun setTargetPower(
        sessionId: UUID,
        powerWatts: Int,
    ): TrainingSessionState {
        val activeState = requireActiveSession(sessionId)
        if (activeState.workout != null) {
            throw WorkoutTargetManagedException()
        }

        require(powerWatts in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()) {
            "ERG target power must fit the FTMS signed 16-bit watt field"
        }

        val powerControl = requireActivePowerControl()
        setTarget(powerControl, powerWatts, "ERG target")

        state =
            activeState.copy(
                changedAt = clock.instant(),
                ergTargetPowerWatts = powerWatts,
            )
        return state
    }

    @Synchronized
    fun advance(sessionId: UUID): TrainingSessionState {
        val activeState = requireActiveSession(sessionId)
        val progress =
            activeState.workout
                ?: throw WorkoutStepAdvanceNotAllowedException(
                    "The active session has no executable workout",
                )
        if (progress.step.completion !is WorkoutStepCompletion.Manual) {
            throw WorkoutStepAdvanceNotAllowedException(
                "The current workout step advances automatically",
            )
        }

        return advanceFrom(progress, clock.instant(), trainingDevice.currentTelemetry())
    }

    @Synchronized
    fun tick(
        now: Instant = clock.instant(),
        telemetry: IndoorBikeTelemetry? = trainingDevice.currentTelemetry(),
    ): TrainingSessionState {
        if (state.phase != TrainingSessionPhase.ACTIVE || activeWorkout == null) {
            return state
        }

        return try {
            while (state.phase == TrainingSessionPhase.ACTIVE) {
                val progress = state.workout ?: break
                if (!isComplete(progress, now, telemetry)) {
                    break
                }
                val transitionAt =
                    when (val completion = progress.step.completion) {
                        is WorkoutStepCompletion.Time -> {
                            progress.stepStartedAt.plusSeconds(completion.seconds.toLong())
                        }

                        is WorkoutStepCompletion.Distance,
                        WorkoutStepCompletion.Manual,
                        -> {
                            now
                        }
                    }
                advanceFrom(progress, transitionAt, telemetry)
            }
            state
        } catch (_: TrainingSessionUnavailableException) {
            state
        }
    }

    @Synchronized
    fun stop(sessionId: UUID): TrainingSessionState {
        val activeState = requireActiveSession(sessionId)
        val powerControl = requireActivePowerControl()
        setTarget(powerControl, 0, "0 W stop")

        state =
            activeState.copy(
                phase = TrainingSessionPhase.STOPPED,
                changedAt = clock.instant(),
                ergTargetPowerWatts = 0,
            )
        clearActiveExecution()
        return state
    }

    private fun isComplete(
        progress: TrainingWorkoutProgress,
        now: Instant,
        telemetry: IndoorBikeTelemetry?,
    ): Boolean =
        when (val completion = progress.step.completion) {
            is WorkoutStepCompletion.Time -> {
                !now.isBefore(progress.stepStartedAt.plusSeconds(completion.seconds.toLong()))
            }

            is WorkoutStepCompletion.Distance -> {
                val currentDistance = telemetry?.distanceMeters ?: return false
                val startDistance = stepDistanceStartMeters
                if (startDistance == null) {
                    stepDistanceStartMeters = currentDistance
                    false
                } else {
                    currentDistance - startDistance >= completion.meters
                }
            }

            WorkoutStepCompletion.Manual -> {
                false
            }
        }

    private fun advanceFrom(
        progress: TrainingWorkoutProgress,
        transitionAt: Instant,
        telemetry: IndoorBikeTelemetry?,
    ): TrainingSessionState {
        val workout = requireNotNull(activeWorkout)
        val nextIndex = progress.currentStepNumber
        if (nextIndex >= workout.steps.size) {
            val powerControl = requireActivePowerControl()
            setTarget(powerControl, 0, "workout completion")
            state =
                state.copy(
                    phase = TrainingSessionPhase.COMPLETED,
                    changedAt = transitionAt,
                    ergTargetPowerWatts = 0,
                )
            clearActiveExecution()
            return state
        }

        val nextStep = workout.steps[nextIndex]
        val nextTarget = targetPower(nextStep)
        val powerControl = requireActivePowerControl()
        setTarget(powerControl, nextTarget, "workout step ${nextIndex + 1}")
        val nextProgress =
            TrainingWorkoutProgress(
                source = workout.source,
                name = workout.name,
                currentStepNumber = nextIndex + 1,
                totalSteps = workout.steps.size,
                step = nextStep,
                stepStartedAt = transitionAt,
            )
        state =
            state.copy(
                changedAt = transitionAt,
                ergTargetPowerWatts = nextTarget,
                workout = nextProgress,
            )
        stepDistanceStartMeters =
            if (nextStep.completion is WorkoutStepCompletion.Distance) {
                telemetry?.distanceMeters
            } else {
                null
            }
        return state
    }

    private fun targetPower(step: ExecutableWorkoutStep): Int =
        when (val target = step.target) {
            is WorkoutStepTarget.Power -> {
                ((target.lowWatts.toLong() + target.highWatts.toLong()) / 2.0).roundToInt()
            }

            WorkoutStepTarget.Open -> {
                0
            }
        }

    private fun setTarget(
        powerControl: IndoorBikePowerControl,
        powerWatts: Int,
        description: String,
    ) {
        try {
            powerControl.setTargetPower(powerWatts)
        } catch (exception: Exception) {
            throw TrainingSessionUnavailableException(
                "The connected device rejected the $description target",
                exception,
            )
        }
    }

    private fun requireActiveSession(sessionId: UUID): TrainingSessionState =
        when {
            state.phase != TrainingSessionPhase.ACTIVE -> throw TrainingSessionNotActiveException()
            state.sessionId != sessionId -> throw TrainingSessionMismatchException(sessionId)
            else -> state
        }

    private fun requireActivePowerControl(): IndoorBikePowerControl =
        activePowerControl
            ?: throw TrainingSessionUnavailableException(
                "The active training session no longer has a connected ERG power-control device",
            )

    private fun clearActiveExecution() {
        activePowerControl = null
        activeWorkout = null
        stepDistanceStartMeters = null
    }
}
