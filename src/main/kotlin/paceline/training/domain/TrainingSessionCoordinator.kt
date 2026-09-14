package paceline.training.domain

import org.springframework.stereotype.Component
import paceline.device.domain.HeartRateSourceDescriptor
import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.IndoorBikePowerControl
import paceline.training.ports.ActivityUploadException
import paceline.training.ports.ActivityUploader
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
    private val activityUploader: ActivityUploader =
        ActivityUploader {
            throw ActivityUploadException("An Intervals.icu activity uploader is not configured")
        },
) {
    private var state = TrainingSessionState.notStarted(clock.instant())
    private var activePowerControl: IndoorBikePowerControl? = null
    private var activeWorkout: ExecutableWorkout? = null
    private var stepDistanceStartMeters: Double? = null
    private var telemetryRegistration: AutoCloseable? = null
    private var activeHeartRateSourceId: String? = null
    private var heartRateRegistration: AutoCloseable? = null
    private val activityRecorder = InMemoryTrainingActivityRecorder()
    private var completedActivity: RecordedTrainingActivity? = null

    @Synchronized
    fun current(): TrainingSessionState {
        refreshHeartRate()
        return state
    }

    @Synchronized
    fun start(
        workout: ExecutableWorkout? = null,
        heartRateSourceId: String? = null,
    ): TrainingSessionState {
        if (state.phase == TrainingSessionPhase.ACTIVE) {
            throw TrainingSessionAlreadyActiveException()
        }

        val selectedHeartRateSourceId = resolveHeartRateSource(heartRateSourceId)

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

        val sessionId = UUID.randomUUID()
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
                    sessionId = sessionId,
                    now = now,
                    workout = progress,
                ).copy(
                    ergTargetPowerWatts = initialTarget,
                    heartRateSourceId = selectedHeartRateSourceId,
                    heartRate = selectedHeartRateSourceId?.let(trainingDevice::currentHeartRate),
                )
        activityRecorder.start(
            sessionId = sessionId,
            startedAt = now,
            name = workout?.name ?: "Paceline ride",
            workoutSource = workout?.source,
            workoutSourceType = workout?.sourceType,
            initialSegmentName =
                workout?.let {
                    activitySegmentName(
                        stepNumber = 1,
                        totalSteps = it.steps.size,
                        step = it.steps.first(),
                    )
                } ?: "Manual ERG",
            initialTargetPowerWatts = initialTarget,
        )
        telemetryRegistration =
            trainingDevice.addTelemetryListener { telemetry ->
                activityRecorder.record(sessionId, telemetry)
            }
        activeHeartRateSourceId = selectedHeartRateSourceId
        heartRateRegistration =
            selectedHeartRateSourceId?.let { sourceId ->
                trainingDevice.addHeartRateListener(sourceId) { telemetry ->
                    onHeartRate(sessionId, sourceId, telemetry)
                }
            }
        completedActivity = null
        return state
    }

    @Synchronized
    fun selectHeartRateSource(
        sessionId: UUID,
        sourceId: String,
    ): TrainingSessionState {
        val activeState = requireActiveSession(sessionId)
        requireConnectedHeartRateSource(sourceId)
        if (activeHeartRateSourceId == sourceId) {
            refreshHeartRate()
            return state
        }

        heartRateRegistration?.close()
        activeHeartRateSourceId = sourceId
        heartRateRegistration =
            trainingDevice.addHeartRateListener(sourceId) { telemetry ->
                onHeartRate(sessionId, sourceId, telemetry)
            }
        state =
            activeState.copy(
                changedAt = clock.instant(),
                heartRateSourceId = sourceId,
                heartRate = trainingDevice.currentHeartRate(sourceId),
            )
        return state
    }

    @Synchronized
    fun setTargetPower(
        sessionId: UUID,
        powerWatts: Int,
    ): TrainingSessionState {
        val activeState = requireActiveSession(sessionId)
        if (activeWorkout != null) {
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
        if (progress.completed || activeWorkout == null) {
            throw WorkoutStepAdvanceNotAllowedException(
                "The workout has already completed",
            )
        }
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
            while (state.phase == TrainingSessionPhase.ACTIVE && activeWorkout != null) {
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
        val stoppedAt = clock.instant()
        setTarget(powerControl, 0, "0 W stop")

        telemetryRegistration?.close()
        telemetryRegistration = null
        heartRateRegistration?.close()
        heartRateRegistration = null
        completedActivity = activityRecorder.finish(sessionId, stoppedAt)

        state =
            activeState.copy(
                phase = TrainingSessionPhase.STOPPED,
                changedAt = stoppedAt,
                ergTargetPowerWatts = 0,
                activityUpload =
                    if (completedActivity?.samples?.isNotEmpty() == true) {
                        TrainingActivityUploadState.available()
                    } else {
                        TrainingActivityUploadState.unavailable()
                    },
            )
        clearActiveExecution()
        return state
    }

    @Synchronized
    fun upload(sessionId: UUID): TrainingSessionState {
        val stoppedState = requireStoppedSession(sessionId)
        val activity =
            completedActivity
                ?: throw TrainingActivityUploadUnavailableException(
                    "The stopped session has no in-memory activity recording",
                )
        if (activity.samples.isEmpty()) {
            throw TrainingActivityUploadUnavailableException(
                "The stopped session has no telemetry to upload",
            )
        }
        if (stoppedState.activityUpload.phase == TrainingActivityUploadPhase.UPLOADED) {
            return stoppedState
        }

        state =
            stoppedState.copy(
                activityUpload = TrainingActivityUploadState(TrainingActivityUploadPhase.UPLOADING),
            )
        return try {
            val receipt = activityUploader.upload(activity)
            state =
                state.copy(
                    activityUpload =
                        TrainingActivityUploadState(
                            phase = TrainingActivityUploadPhase.UPLOADED,
                            remoteActivityId = receipt.remoteActivityId,
                        ),
                )
            state
        } catch (exception: ActivityUploadException) {
            state =
                state.copy(
                    activityUpload =
                        TrainingActivityUploadState(
                            phase = TrainingActivityUploadPhase.FAILED,
                            error = exception.message,
                        ),
                )
            throw exception
        } catch (exception: Exception) {
            val uploadException = ActivityUploadException("The training activity could not be uploaded", exception)
            state =
                state.copy(
                    activityUpload =
                        TrainingActivityUploadState(
                            phase = TrainingActivityUploadPhase.FAILED,
                            error = uploadException.message,
                        ),
                )
            throw uploadException
        }
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
            activityRecorder.completeWorkout(
                sessionId = requireNotNull(state.sessionId),
                completedAt = transitionAt,
            )
            state =
                state.copy(
                    changedAt = transitionAt,
                    ergTargetPowerWatts = 0,
                    workout = progress.copy(completed = true),
                )
            clearActiveWorkout()
            return state
        }

        val nextStep = workout.steps[nextIndex]
        val nextTarget = targetPower(nextStep)
        val powerControl = requireActivePowerControl()
        setTarget(powerControl, nextTarget, "workout step ${nextIndex + 1}")
        activityRecorder.startSegment(
            sessionId = requireNotNull(state.sessionId),
            startedAt = transitionAt,
            name = activitySegmentName(nextIndex + 1, workout.steps.size, nextStep),
            targetPowerWatts = nextTarget,
        )
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

    private fun activitySegmentName(
        stepNumber: Int,
        totalSteps: Int,
        step: ExecutableWorkoutStep,
    ): String {
        val stepName = step.text?.trim()?.takeIf(String::isNotBlank) ?: "Step $stepNumber"
        return "Step $stepNumber/$totalSteps: $stepName"
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

    private fun requireStoppedSession(sessionId: UUID): TrainingSessionState =
        when {
            state.sessionId != sessionId -> {
                throw TrainingSessionMismatchException(sessionId)
            }

            state.phase != TrainingSessionPhase.STOPPED -> {
                throw TrainingActivityUploadUnavailableException(
                    "A training activity can be uploaded only after the session is stopped",
                )
            }

            else -> {
                state
            }
        }

    private fun requireActivePowerControl(): IndoorBikePowerControl =
        activePowerControl
            ?: throw TrainingSessionUnavailableException(
                "The active training session no longer has a connected ERG power-control device",
            )

    private fun clearActiveExecution() {
        activePowerControl = null
        activeHeartRateSourceId = null
        clearActiveWorkout()
    }

    private fun clearActiveWorkout() {
        activeWorkout = null
        stepDistanceStartMeters = null
    }

    private fun resolveHeartRateSource(sourceId: String?): String? {
        val sources = connectedHeartRateSources()
        if (sourceId != null) {
            requireConnectedHeartRateSource(sourceId, sources)
            return sourceId
        }
        if (sources.size > 1) {
            throw HeartRateSourceSelectionRequiredException()
        }
        return sources.singleOrNull()?.id
    }

    private fun connectedHeartRateSources(): List<HeartRateSourceDescriptor> =
        trainingDevice
            .heartRateSources()
            .filter { source -> source.state == paceline.device.domain.ConnectionPhase.CONNECTED }

    private fun requireConnectedHeartRateSource(
        sourceId: String,
        sources: List<HeartRateSourceDescriptor> = connectedHeartRateSources(),
    ) {
        if (sources.none { source -> source.id == sourceId }) {
            throw HeartRateSourceNotFoundException(sourceId)
        }
    }

    private fun onHeartRate(
        sessionId: UUID,
        sourceId: String,
        telemetry: HeartRateTelemetry,
    ) {
        synchronized(this) {
            if (
                state.phase != TrainingSessionPhase.ACTIVE ||
                state.sessionId != sessionId ||
                activeHeartRateSourceId != sourceId
            ) {
                return
            }
            activityRecorder.recordHeartRate(sessionId, sourceId, telemetry)
            state = state.copy(changedAt = clock.instant(), heartRate = telemetry)
        }
    }

    private fun refreshHeartRate() {
        val sourceId = activeHeartRateSourceId ?: return
        if (state.phase != TrainingSessionPhase.ACTIVE) {
            return
        }
        val latest = trainingDevice.currentHeartRate(sourceId)
        if (latest != state.heartRate) {
            state = state.copy(heartRate = latest)
        }
    }
}
