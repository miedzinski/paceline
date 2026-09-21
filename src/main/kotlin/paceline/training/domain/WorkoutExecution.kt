package paceline.training.domain

import paceline.telemetry.domain.CyclingTelemetry
import paceline.workout.domain.ExecutableWorkout
import paceline.workout.domain.WorkoutStepCompletion
import paceline.workout.domain.WorkoutStepTarget
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

class WorkoutExecution {
    private var activeWorkout: ExecutableWorkout? = null
    private var stepDistanceStartMeters: Double? = null
    private var stepDistanceProgressAtPauseMeters: Double? = null

    fun attach(
        workout: ExecutableWorkout,
        progress: TrainingWorkoutProgress,
        telemetry: CyclingTelemetry?,
    ) {
        activeWorkout = workout
        resetDistanceTracking(progress, telemetry)
    }

    fun hasActiveWorkout(): Boolean = activeWorkout != null

    fun clear() {
        activeWorkout = null
        stepDistanceStartMeters = null
        stepDistanceProgressAtPauseMeters = null
    }

    fun isFreeRide(progress: TrainingWorkoutProgress): Boolean = progress.step.target is WorkoutStepTarget.Open

    fun targetPower(
        progress: TrainingWorkoutProgress,
        at: Instant,
        targetPercent: Long,
    ): Int {
        val prescribedTarget =
            when (val target = progress.step.target) {
                is WorkoutStepTarget.Power -> {
                    ((target.lowWatts.toLong() + target.highWatts.toLong()) / 2.0).roundToInt()
                }

                is WorkoutStepTarget.Ramp -> {
                    val completion =
                        progress.step.completion as? WorkoutStepCompletion.Time
                            ?: throw IllegalStateException("A ramp target must have a timed completion condition")
                    val durationMillis = completion.seconds.toLong() * MILLIS_PER_SECOND
                    val elapsedMillis =
                        Duration
                            .between(progress.stepStartedAt, at)
                            .toMillis()
                            .coerceIn(0L, durationMillis)
                    val fraction = elapsedMillis.toDouble() / durationMillis
                    (
                        target.startWatts.toDouble() +
                            (target.endWatts - target.startWatts) * fraction
                    ).roundToInt()
                }

                WorkoutStepTarget.Open -> {
                    0
                }
            }
        return adjustTargetPower(prescribedTarget, targetPercent)
    }

    fun addTargetPercent(
        currentPercent: Long,
        deltaPercent: Long,
    ): Long =
        when {
            deltaPercent > 0L && currentPercent > Long.MAX_VALUE - deltaPercent -> Long.MAX_VALUE
            deltaPercent < 0L && currentPercent < Long.MIN_VALUE - deltaPercent -> Long.MIN_VALUE
            else -> currentPercent + deltaPercent
        }

    fun isComplete(
        progress: TrainingWorkoutProgress,
        now: Instant,
        telemetry: CyclingTelemetry?,
    ): Boolean =
        when (val completion = progress.step.completion) {
            is WorkoutStepCompletion.Time -> {
                !now.isBefore(progress.stepStartedAt.plusSeconds(completion.seconds.toLong()))
            }

            is WorkoutStepCompletion.Distance -> {
                val currentDistance = telemetry?.distanceMeters ?: return false
                stepDistanceProgressAtPauseMeters?.let { progressAtPause ->
                    stepDistanceStartMeters = currentDistance - progressAtPause
                    stepDistanceProgressAtPauseMeters = null
                    return progressAtPause >= completion.meters
                }
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

    fun nextProgress(
        progress: TrainingWorkoutProgress,
        transitionAt: Instant,
    ): TrainingWorkoutProgress? {
        val workout = requireNotNull(activeWorkout) { "No executable workout is attached" }
        val nextIndex = progress.currentStepNumber
        if (nextIndex >= workout.steps.size) {
            return null
        }

        val nextStep = workout.steps[nextIndex]
        return TrainingWorkoutProgress(
            source = workout.source,
            name = workout.name,
            currentStepNumber = nextIndex + 1,
            totalSteps = workout.steps.size,
            steps = workout.steps,
            step = nextStep,
            stepStartedAt = transitionAt,
        )
    }

    fun beginStep(
        progress: TrainingWorkoutProgress,
        telemetry: CyclingTelemetry?,
    ) {
        resetDistanceTracking(progress, telemetry)
    }

    fun captureDistanceProgressAtPause(
        progress: TrainingWorkoutProgress?,
        telemetry: CyclingTelemetry?,
    ) {
        if (progress == null || progress.completed || progress.step.completion !is WorkoutStepCompletion.Distance) {
            stepDistanceProgressAtPauseMeters = null
            return
        }

        val currentDistance = telemetry?.distanceMeters ?: return
        val startDistance = stepDistanceStartMeters
        if (startDistance == null) {
            stepDistanceStartMeters = currentDistance
            stepDistanceProgressAtPauseMeters = 0.0
        } else {
            stepDistanceProgressAtPauseMeters = maxOf(0.0, currentDistance - startDistance)
        }
    }

    fun resumeDistanceTracking(
        progress: TrainingWorkoutProgress?,
        telemetry: CyclingTelemetry?,
    ) {
        if (progress == null || progress.completed || progress.step.completion !is WorkoutStepCompletion.Distance) {
            stepDistanceProgressAtPauseMeters = null
            return
        }

        val currentDistance = telemetry?.distanceMeters
        val progressAtPause = stepDistanceProgressAtPauseMeters
        when {
            currentDistance != null && progressAtPause != null -> {
                stepDistanceStartMeters = currentDistance - progressAtPause
                stepDistanceProgressAtPauseMeters = null
            }

            currentDistance != null && stepDistanceStartMeters == null -> {
                stepDistanceStartMeters = currentDistance
            }
        }
    }

    private fun resetDistanceTracking(
        progress: TrainingWorkoutProgress,
        telemetry: CyclingTelemetry?,
    ) {
        stepDistanceProgressAtPauseMeters = null
        stepDistanceStartMeters =
            if (progress.step.completion is WorkoutStepCompletion.Distance) {
                telemetry?.distanceMeters
            } else {
                null
            }
    }

    private fun adjustTargetPower(
        prescribedTargetWatts: Int,
        targetPercent: Long,
    ): Int {
        if (prescribedTargetWatts <= 0 || targetPercent <= 0L) {
            return 0
        }
        val adjustedTarget =
            BigDecimal
                .valueOf(prescribedTargetWatts.toLong())
                .multiply(BigDecimal.valueOf(targetPercent))
                .divide(BigDecimal.valueOf(100L), 0, RoundingMode.HALF_UP)
        return adjustedTarget
            .min(BigDecimal.valueOf(Short.MAX_VALUE.toLong()))
            .intValueExact()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
