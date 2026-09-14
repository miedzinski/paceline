package paceline.workout.domain

import kotlin.math.roundToInt

enum class ExecutableSport {
    CYCLING,
}

data class ExecutableWorkout(
    val source: WorkoutSourceReference,
    val name: String,
    val sport: ExecutableSport,
    val steps: List<ExecutableWorkoutStep>,
    val sourceType: WorkoutSourceType? = null,
) {
    init {
        require(steps.isNotEmpty()) { "An executable workout must contain at least one step" }
    }
}

data class ExecutableWorkoutStep(
    val text: String?,
    val completion: WorkoutStepCompletion,
    val target: WorkoutStepTarget,
)

sealed interface WorkoutStepCompletion {
    data class Time(
        val seconds: Int,
    ) : WorkoutStepCompletion {
        init {
            require(seconds > 0) { "A timed workout step must be longer than zero seconds" }
        }
    }

    data class Distance(
        val meters: Double,
    ) : WorkoutStepCompletion {
        init {
            require(meters.isFinite() && meters > 0.0) {
                "A distance workout step must be longer than zero meters"
            }
        }
    }

    data object Manual : WorkoutStepCompletion
}

sealed interface WorkoutStepTarget {
    data class Power(
        val lowWatts: Int,
        val highWatts: Int,
    ) : WorkoutStepTarget {
        init {
            require(lowWatts >= 0) { "Power targets cannot be negative" }
            require(highWatts >= lowWatts) { "Power target bounds are inverted" }
        }
    }

    data object Open : WorkoutStepTarget
}

class WorkoutNotExecutableException(
    message: String,
) : IllegalArgumentException(message)

object ExecutableWorkoutFactory {
    fun from(workout: ScheduledWorkout): ExecutableWorkout =
        from(
            sourceType = WorkoutSourceType.SCHEDULED,
            source = workout.reference,
            name = workout.name,
            type = workout.type,
            plan = workout.workout,
        )

    fun from(workout: LibraryWorkout): ExecutableWorkout =
        from(
            sourceType = WorkoutSourceType.LIBRARY,
            source = workout.reference,
            name = workout.name,
            type = workout.type,
            plan = workout.workout,
        )

    private fun from(
        sourceType: WorkoutSourceType,
        source: WorkoutSourceReference,
        name: String?,
        type: String?,
        plan: WorkoutPlanSummary?,
    ): ExecutableWorkout {
        val sport = type.toExecutableSport()
        val summary = plan ?: throw WorkoutNotExecutableException("Workout ${source.id} has no structured plan")
        val steps = summary.steps.flatMapIndexed { index, step -> expandStep(step, "step ${index + 1}") }

        if (steps.isEmpty()) {
            throw WorkoutNotExecutableException("Workout ${source.id} has no executable steps")
        }

        return ExecutableWorkout(
            source = source,
            name = name?.takeIf(String::isNotBlank) ?: "Workout",
            sport = sport,
            steps = steps,
            sourceType = sourceType,
        )
    }

    private fun expandStep(
        step: WorkoutStepSummary,
        path: String,
    ): List<ExecutableWorkoutStep> {
        val repetitions = step.repeats ?: 1
        if (repetitions < 1) {
            throw WorkoutNotExecutableException("$path has an invalid repetition count")
        }

        if (step.steps.isNotEmpty()) {
            val nested =
                step.steps.flatMapIndexed { index, child ->
                    expandStep(child, "$path.${index + 1}")
                }
            return List(repetitions) { nested }.flatten()
        }

        if (repetitions != 1) {
            throw WorkoutNotExecutableException("$path repeats without nested steps")
        }

        return listOf(
            ExecutableWorkoutStep(
                text = step.text,
                completion = step.completion(path),
                target = step.target(path),
            ),
        )
    }

    private fun WorkoutStepSummary.completion(path: String): WorkoutStepCompletion =
        when {
            untilLapPress == true -> {
                WorkoutStepCompletion.Manual
            }

            durationSeconds != null && distanceMeters == null -> {
                if (durationSeconds <= 0) {
                    throw WorkoutNotExecutableException("$path has an invalid duration")
                }

                WorkoutStepCompletion.Time(durationSeconds)
            }

            distanceMeters != null && durationSeconds == null -> {
                if (!distanceMeters.isFinite() || distanceMeters <= 0.0) {
                    throw WorkoutNotExecutableException("$path has an invalid distance")
                }

                WorkoutStepCompletion.Distance(distanceMeters)
            }

            durationSeconds != null && distanceMeters != null -> {
                throw WorkoutNotExecutableException("$path has both time and distance completion")
            }

            else -> {
                throw WorkoutNotExecutableException("$path has no supported completion condition")
            }
        }

    private fun WorkoutStepSummary.target(path: String): WorkoutStepTarget {
        val resolvedPower = resolvedPower?.toAbsolutePowerTarget()
        val absolutePower = power?.toAbsolutePowerTarget()

        return when {
            resolvedPower != null -> resolvedPower

            absolutePower != null -> absolutePower

            freeRide == true -> WorkoutStepTarget.Open

            else -> throw WorkoutNotExecutableException(
                "$path has no resolved power target or explicit open target",
            )
        }
    }

    private fun WorkoutTargetSummary.toAbsolutePowerTarget(): WorkoutStepTarget.Power? {
        val normalizedUnits = units?.trim()?.lowercase()
        val absoluteUnits = normalizedUnits == null || normalizedUnits in setOf("w", "watt", "watts")
        if (!absoluteUnits) {
            return null
        }

        val low = (start ?: value ?: end)?.toWatts() ?: return null
        val high = (end ?: value ?: start)?.toWatts() ?: return null
        return WorkoutStepTarget.Power(minOf(low, high), maxOf(low, high))
    }

    private fun Double.toWatts(): Int {
        if (!isFinite() || this < 0.0 || this > Short.MAX_VALUE) {
            throw WorkoutNotExecutableException("Power target is outside the supported range")
        }
        return roundToInt()
    }

    private fun String?.toExecutableSport(): ExecutableSport =
        when (this?.trim()?.lowercase()) {
            "ride", "virtualride", "cycling", "indoor cycling", "indoorbike" -> ExecutableSport.CYCLING
            else -> throw WorkoutNotExecutableException("Workout sport '$this' is not supported")
        }
}
