package paceline.training.domain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.domain.HeartRateSourceDescriptor
import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.IndoorBikePowerControl
import paceline.training.config.ErgProtectionProperties
import paceline.training.ports.ActivityUploadException
import paceline.training.ports.ActivityUploader
import paceline.training.ports.TrainingDevice
import paceline.workout.domain.ExecutableWorkout
import paceline.workout.domain.ExecutableWorkoutStep
import paceline.workout.domain.WorkoutStepCompletion
import paceline.workout.domain.WorkoutStepTarget
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.math.roundToInt

@Component
class TrainingSessionCoordinator(
    private val trainingDevice: TrainingDevice,
    private val clock: Clock = Clock.systemUTC(),
    private val activityUploader: ActivityUploader =
        ActivityUploader {
            throw ActivityUploadException("An Intervals.icu activity uploader is not configured")
        },
    private val ergProtectionProperties: ErgProtectionProperties = ErgProtectionProperties(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var state = TrainingSessionState.notStarted(clock.instant())
    private var activePowerControl: IndoorBikePowerControl? = null
    private var activeWorkout: ExecutableWorkout? = null
    private var stepDistanceStartMeters: Double? = null
    private var stepDistanceProgressAtPauseMeters: Double? = null
    private var pausedAt: Instant? = null
    private var pausedErgTargetPowerWatts: Int? = null
    private var pausedControlMode: TrainingControlMode? = null
    private var telemetryRegistration: AutoCloseable? = null
    private var activeHeartRateSourceId: String? = null
    private var heartRateRegistration: AutoCloseable? = null
    private val activityRecorder = InMemoryTrainingActivityRecorder()
    private var completedActivity: RecordedTrainingActivity? = null
    private val ergSpiralDetector = ErgSpiralDetector(ergProtectionProperties)
    private var connectionInterruptedAt: Instant? = null
    private var nextConnectionRecoveryAt: Instant? = null
    private var connectionRecoveryAttempt = 0
    private var connectionRecovery: CompletionStage<IndoorBikePowerControl?>? = null
    private var targetSynchronizationPending = false
    private var targetSynchronizationAt: Instant? = null
    private var pendingZeroPowerCommand = false
    private var lastTelemetryReceivedAt: Instant? = null
    private var telemetryObserved = false

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
        if (state.phase in setOf(TrainingSessionPhase.ACTIVE, TrainingSessionPhase.PAUSED)) {
            throw TrainingSessionAlreadyActiveException()
        }
        if (pendingZeroPowerCommand) {
            throw TrainingSessionUnavailableException(
                "The previous session is still waiting to confirm a 0 W trainer command",
            )
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
        ergSpiralDetector.reset(now)
        val progress = workout?.let { TrainingWorkoutProgress.firstStep(it, now) }
        val initialFreeRide = workout == null || progress?.let(::isFreeRideStep) == true
        val initialTarget =
            progress
                ?.takeUnless(::isFreeRideStep)
                ?.let { targetPower(it, now, DEFAULT_WORKOUT_POWER_TARGET_PERCENT) }
        if (initialFreeRide) {
            setFreeRide(powerControl, "initial Free Ride")
        } else if (initialTarget != null) {
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
                    controlMode = if (initialFreeRide) TrainingControlMode.FREE_RIDE else TrainingControlMode.ERG,
                    ergRequestedTargetPowerWatts = initialTarget.takeUnless { initialFreeRide },
                    ergTargetPowerWatts = initialTarget.takeUnless { initialFreeRide },
                    workoutPowerTargetPercent = workout?.let { DEFAULT_WORKOUT_POWER_TARGET_PERCENT },
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
                } ?: "Manual Free Ride",
            initialTargetPowerWatts = initialTarget,
            initialWorkoutStep = workout?.steps?.first(),
        )
        registerTelemetry(sessionId)
        activeHeartRateSourceId = selectedHeartRateSourceId
        registerHeartRate(sessionId, selectedHeartRateSourceId)
        completedActivity = null
        pausedControlMode = null
        pausedErgTargetPowerWatts = null
        connectionInterruptedAt = null
        nextConnectionRecoveryAt = null
        connectionRecoveryAttempt = 0
        connectionRecovery = null
        targetSynchronizationPending = false
        targetSynchronizationAt = null
        pendingZeroPowerCommand = false
        lastTelemetryReceivedAt = null
        telemetryObserved = false
        observeTelemetry(trainingDevice.currentTelemetry())
        return state
    }

    @Synchronized
    fun adjustWorkoutTarget(
        sessionId: UUID,
        deltaPercent: Long,
    ): TrainingSessionState {
        val activeState = requireActiveSession(sessionId)
        val progress =
            activeState.workout
                ?: throw WorkoutTargetAdjustmentNotAllowedException(
                    "The active session has no executable workout",
                )
        if (progress.completed || activeWorkout == null) {
            throw WorkoutTargetAdjustmentNotAllowedException(
                "The workout has already completed",
            )
        }
        if (activeState.ergProtection.status == ErgProtectionStatus.UNAVAILABLE) {
            throw TrainingSessionUnavailableException(
                "The workout target cannot change while ERG protection is unavailable",
            )
        }
        if (activeState.ergProtection.status == ErgProtectionStatus.RECOVERY_FAILED) {
            throw TrainingSessionUnavailableException(
                "The workout target cannot change while ERG recovery has failed",
            )
        }
        if (deltaPercent == 0L) {
            return activeState
        }

        val currentPercent =
            activeState.workoutPowerTargetPercent ?: DEFAULT_WORKOUT_POWER_TARGET_PERCENT
        val nextPercent = addWorkoutTargetPercent(currentPercent, deltaPercent)
        val changedAt = clock.instant()
        val nextFreeRide = isFreeRideStep(progress)
        val nextTarget = targetPower(progress, changedAt, nextPercent)

        if (nextFreeRide) {
            state =
                activeState.copy(
                    changedAt = changedAt,
                    workoutPowerTargetPercent = nextPercent,
                )
            recordWorkoutTargetAdjustment(changedAt, nextPercent, null)
            return state
        }

        if (activeState.ergProtection.status != ErgProtectionStatus.INACTIVE) {
            ergSpiralDetector.reset(changedAt)
            state =
                activeState.copy(
                    changedAt = changedAt,
                    ergRequestedTargetPowerWatts = nextTarget,
                    ergTargetPowerWatts =
                        if (nextTarget <= 0) 0 else activeState.ergTargetPowerWatts,
                    workoutPowerTargetPercent = nextPercent,
                    ergProtection =
                        if (isErgProtectionActive() && nextTarget <= 0) {
                            ErgProtectionState.inactive()
                        } else {
                            activeState.ergProtection
                        },
                )
            recordWorkoutTargetAdjustment(changedAt, nextPercent, nextTarget)
            return state
        }

        setTarget(requireActivePowerControl(), nextTarget, "workout target adjustment")
        ergSpiralDetector.reset(changedAt)
        state =
            activeState.copy(
                changedAt = changedAt,
                controlMode = TrainingControlMode.ERG,
                ergRequestedTargetPowerWatts = nextTarget,
                ergTargetPowerWatts = nextTarget,
                workoutPowerTargetPercent = nextPercent,
            )
        recordWorkoutTargetAdjustment(changedAt, nextPercent, nextTarget)
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
        registerHeartRate(sessionId, sourceId)
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

        val changedAt = clock.instant()
        val protectionActive = activeState.ergProtection.status == ErgProtectionStatus.BAILED_OUT
        if (protectionActive && powerWatts > 0) {
            logger.info(
                "ERG target deferred while protection is active: sessionId={} targetPowerWatts={} " +
                    "protectionStatus={}",
                sessionId,
                powerWatts,
                activeState.ergProtection.status,
            )
            ergSpiralDetector.reset(changedAt)
            state =
                activeState.copy(
                    changedAt = changedAt,
                    controlMode = TrainingControlMode.ERG,
                    ergRequestedTargetPowerWatts = powerWatts,
                )
            return state
        }

        val powerControl = requireActivePowerControl()
        setTarget(powerControl, powerWatts, "ERG target")
        ergSpiralDetector.reset(changedAt)

        state =
            activeState.copy(
                changedAt = changedAt,
                controlMode = TrainingControlMode.ERG,
                ergRequestedTargetPowerWatts = powerWatts,
                ergTargetPowerWatts = powerWatts,
                ergProtection = ErgProtectionState.inactive(),
            )
        return state
    }

    @Synchronized
    fun pause(sessionId: UUID): TrainingSessionState {
        if (state.sessionId == sessionId && state.phase == TrainingSessionPhase.PAUSED) {
            throw TrainingSessionPauseNotAllowedException(
                "The training session is already paused",
            )
        }
        val activeState = requireActiveSession(sessionId)
        val pauseAt = clock.instant()
        val targetPowerWatts =
            activeState
                .takeIf { it.controlMode == TrainingControlMode.ERG }
                ?.let { session ->
                    session.ergRequestedTargetPowerWatts
                        ?: session.ergTargetPowerWatts
                }
        val commandApplied =
            if (activePowerControl != null) {
                trySetTargetForExecution(0, "0 W pause", pauseAt)
            } else {
                markConnectionInterrupted(pauseAt)
                false
            }
        captureDistanceProgressAtPause()

        closeRecordingListeners()
        pausedAt = pauseAt
        pausedErgTargetPowerWatts = targetPowerWatts
        pausedControlMode = activeState.controlMode
        ergSpiralDetector.reset(pauseAt)
        state =
            activeState.copy(
                phase = TrainingSessionPhase.PAUSED,
                changedAt = pauseAt,
                controlMode = activeState.controlMode,
                ergRequestedTargetPowerWatts = targetPowerWatts,
                ergTargetPowerWatts = if (commandApplied) 0 else null,
                ergProtection = ErgProtectionState.inactive(),
            )
        return state
    }

    @Synchronized
    fun resume(sessionId: UUID): TrainingSessionState {
        val pausedState = requirePausedSession(sessionId)
        val powerControl = requireActivePowerControl()
        val resumedAt = clock.instant()
        val pauseStartedAt =
            pausedAt
                ?: throw TrainingSessionResumeNotAllowedException(
                    "The paused training session has no pause timestamp",
                )
        if (resumedAt.isBefore(pauseStartedAt)) {
            throw TrainingSessionResumeNotAllowedException(
                "The training session cannot resume before it was paused",
            )
        }

        val pausedDuration = Duration.between(pauseStartedAt, resumedAt)
        val progress = pausedState.workout
        val resumedWorkout =
            progress?.takeUnless { it.completed }?.copy(
                stepStartedAt = progress.stepStartedAt.plus(pausedDuration),
            )
        val resumedControlMode = pausedControlMode ?: pausedState.controlMode
        val freeRide =
            resumedControlMode == TrainingControlMode.FREE_RIDE ||
                resumedWorkout?.let(::isFreeRideStep) == true
        val targetPowerWatts =
            if (freeRide) {
                null
            } else {
                activeWorkout
                    ?.let { resumedWorkout?.let { workoutProgress -> targetPower(workoutProgress, resumedAt) } }
                    ?: pausedErgTargetPowerWatts
                    ?: pausedState.ergRequestedTargetPowerWatts
                    ?: 0
            }

        try {
            powerControl.requestControl()
        } catch (exception: Exception) {
            throw TrainingSessionUnavailableException(
                "The connected device did not grant ERG control on resume",
                exception,
            )
        }
        if (freeRide) {
            setFreeRide(powerControl, "resume Free Ride")
        } else {
            setTarget(powerControl, requireNotNull(targetPowerWatts), "resume")
        }

        resumeDistanceTracking()
        ergSpiralDetector.reset(resumedAt)
        pausedAt = null
        pausedErgTargetPowerWatts = null
        pausedControlMode = null
        registerTelemetry(sessionId)
        registerHeartRate(sessionId, activeHeartRateSourceId)
        state =
            pausedState.copy(
                phase = TrainingSessionPhase.ACTIVE,
                changedAt = resumedAt,
                controlMode = if (freeRide) TrainingControlMode.FREE_RIDE else TrainingControlMode.ERG,
                ergRequestedTargetPowerWatts = targetPowerWatts,
                ergTargetPowerWatts = targetPowerWatts,
                ergProtection = ErgProtectionState.inactive(),
                heartRate = activeHeartRateSourceId?.let(trainingDevice::currentHeartRate),
                workout = resumedWorkout ?: progress,
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

        val now = clock.instant()
        observeTelemetry(trainingDevice.currentTelemetry())
        val connection = ensureTrainerConnection(now)
        val telemetry = if (connection.connected) trainingDevice.currentTelemetry() else null
        return advanceFrom(progress, now, telemetry)
    }

    @Synchronized
    fun tick(
        now: Instant = clock.instant(),
        telemetry: IndoorBikeTelemetry? = trainingDevice.currentTelemetry(),
    ): TrainingSessionState {
        if (
            state.phase != TrainingSessionPhase.ACTIVE &&
            state.phase != TrainingSessionPhase.PAUSED &&
            !(state.phase == TrainingSessionPhase.STOPPED && pendingZeroPowerCommand)
        ) {
            return state
        }
        observeTelemetry(telemetry)
        val connection = ensureTrainerConnection(now)
        val effectiveTelemetry =
            if (!connection.connected) {
                null
            } else if (connection.recovered) {
                trainingDevice.currentTelemetry()
            } else {
                telemetry
            }
        observeTelemetry(effectiveTelemetry)
        return try {
            if (state.ergProtection.status == ErgProtectionStatus.UNAVAILABLE) {
                if (connection.connected && targetSynchronizationPending) {
                    synchronizeCurrentControl(now)
                }
                return state
            }
            if (state.phase == TrainingSessionPhase.ACTIVE && activeWorkout != null) {
                while (state.phase == TrainingSessionPhase.ACTIVE && activeWorkout != null) {
                    val progress = state.workout ?: break
                    if (isComplete(progress, now, effectiveTelemetry)) {
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
                        advanceFrom(progress, transitionAt, effectiveTelemetry)
                    } else {
                        refreshWorkoutTarget(progress, now)
                        break
                    }
                }
            }
            if (connection.connected && targetSynchronizationPending) {
                synchronizeCurrentControl(now)
            }
            if (state.phase == TrainingSessionPhase.ACTIVE && connection.connected) {
                evaluateErgProtection(now, effectiveTelemetry)
            }
            state
        } catch (exception: TrainingSessionUnavailableException) {
            if (targetSynchronizationPending) {
                markConnectionInterrupted(now, exception.message)
            }
            state
        }
    }

    @Synchronized
    fun stop(sessionId: UUID): TrainingSessionState {
        val activeState = requireSessionInProgress(sessionId)
        val stoppedAt = clock.instant()
        val commandApplied =
            if (activePowerControl != null) {
                trySetTargetForExecution(0, "0 W stop", stoppedAt)
            } else {
                markConnectionInterrupted(stoppedAt)
                false
            }
        pendingZeroPowerCommand = !commandApplied

        closeRecordingListeners()
        completedActivity = activityRecorder.finish(sessionId, stoppedAt)

        state =
            activeState.copy(
                phase = TrainingSessionPhase.STOPPED,
                changedAt = stoppedAt,
                controlMode = TrainingControlMode.ERG,
                ergRequestedTargetPowerWatts = 0,
                ergTargetPowerWatts = if (commandApplied) 0 else null,
                workoutPowerTargetPercent = null,
                ergProtection = ErgProtectionState.inactive(),
                activityUpload =
                    if (completedActivity?.samples?.isNotEmpty() == true) {
                        TrainingActivityUploadState.available()
                    } else {
                        TrainingActivityUploadState.unavailable()
                    },
            )
        clearActiveExecution(preserveTrainerRecovery = pendingZeroPowerCommand)
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

    private fun captureDistanceProgressAtPause() {
        val progress = state.workout ?: return
        if (progress.completed || progress.step.completion !is WorkoutStepCompletion.Distance) {
            stepDistanceProgressAtPauseMeters = null
            return
        }

        val currentDistance = trainingDevice.currentTelemetry()?.distanceMeters ?: return
        val startDistance = stepDistanceStartMeters
        if (startDistance == null) {
            stepDistanceStartMeters = currentDistance
            stepDistanceProgressAtPauseMeters = 0.0
        } else {
            stepDistanceProgressAtPauseMeters = maxOf(0.0, currentDistance - startDistance)
        }
    }

    private fun resumeDistanceTracking() {
        val progress = state.workout ?: return
        if (progress.completed || progress.step.completion !is WorkoutStepCompletion.Distance) {
            stepDistanceProgressAtPauseMeters = null
            return
        }

        val currentDistance = trainingDevice.currentTelemetry()?.distanceMeters
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

    private fun registerTelemetry(sessionId: UUID) {
        telemetryRegistration =
            trainingDevice.addTelemetryListener { telemetry ->
                synchronized(this) {
                    observeTelemetry(telemetry)
                    activityRecorder.record(sessionId, telemetry)
                }
            }
    }

    private fun observeTelemetry(telemetry: IndoorBikeTelemetry?) {
        if (telemetry == null || !runCatching { trainingDevice.hasTelemetryCapability() }.getOrDefault(false)) {
            return
        }
        val receivedAt = telemetry.receivedAt
        if (telemetryObserved && lastTelemetryReceivedAt?.let { !receivedAt.isAfter(it) } == true) {
            return
        }
        telemetryObserved = true
        lastTelemetryReceivedAt = receivedAt
    }

    private fun isTelemetryStale(now: Instant): Boolean {
        if (!runCatching { trainingDevice.hasTelemetryCapability() }.getOrDefault(false) || !telemetryObserved) {
            return false
        }
        val lastReceivedAt = lastTelemetryReceivedAt ?: return false
        return Duration.between(lastReceivedAt, now) > ergProtectionProperties.telemetryFreshness
    }

    private fun registerHeartRate(
        sessionId: UUID,
        sourceId: String?,
    ) {
        heartRateRegistration =
            sourceId?.let { selectedSourceId ->
                trainingDevice.addHeartRateListener(selectedSourceId) { telemetry ->
                    onHeartRate(sessionId, selectedSourceId, telemetry)
                }
            }
    }

    private fun closeRecordingListeners() {
        telemetryRegistration?.close()
        telemetryRegistration = null
        heartRateRegistration?.close()
        heartRateRegistration = null
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

    private fun advanceFrom(
        progress: TrainingWorkoutProgress,
        transitionAt: Instant,
        telemetry: IndoorBikeTelemetry?,
    ): TrainingSessionState {
        if (state.ergProtection.status == ErgProtectionStatus.UNAVAILABLE) {
            throw TrainingSessionUnavailableException(
                "The workout cannot advance while ERG protection is unavailable",
            )
        }
        val workout = requireNotNull(activeWorkout)
        val nextIndex = progress.currentStepNumber
        if (nextIndex >= workout.steps.size) {
            val freeRideApplied =
                if (state.controlMode != TrainingControlMode.FREE_RIDE || targetSynchronizationPending) {
                    trySetFreeRideForExecution("workout completion Free Ride", transitionAt)
                } else {
                    true
                }
            activityRecorder.completeWorkout(
                sessionId = requireNotNull(state.sessionId),
                completedAt = transitionAt,
            )
            state =
                state.copy(
                    changedAt = transitionAt,
                    controlMode = TrainingControlMode.FREE_RIDE,
                    ergRequestedTargetPowerWatts = null,
                    ergTargetPowerWatts = if (freeRideApplied) null else state.ergTargetPowerWatts,
                    workoutPowerTargetPercent = null,
                    ergProtection = ErgProtectionState.inactive(),
                    workout = progress.copy(completed = true),
                )
            ergSpiralDetector.reset(transitionAt)
            clearActiveWorkout()
            return state
        }

        val nextStep = workout.steps[nextIndex]
        val nextProgress =
            TrainingWorkoutProgress(
                source = workout.source,
                name = workout.name,
                currentStepNumber = nextIndex + 1,
                totalSteps = workout.steps.size,
                step = nextStep,
                stepStartedAt = transitionAt,
            )
        val nextTarget = targetPower(nextProgress, transitionAt)
        val nextFreeRide = isFreeRideStep(nextProgress)
        val protectionActive = isErgProtectionActive()
        val commandApplied =
            when {
                nextFreeRide &&
                    (state.controlMode != TrainingControlMode.FREE_RIDE || targetSynchronizationPending) -> {
                    trySetFreeRideForExecution("workout step ${nextIndex + 1} Free Ride", transitionAt)
                }

                nextFreeRide -> {
                    true
                }

                state.ergProtection.status == ErgProtectionStatus.INACTIVE -> {
                    trySetTargetForExecution(nextTarget, "workout step ${nextIndex + 1}", transitionAt)
                }

                else -> {
                    false
                }
            }
        activityRecorder.startSegment(
            sessionId = requireNotNull(state.sessionId),
            startedAt = transitionAt,
            name = activitySegmentName(nextIndex + 1, workout.steps.size, nextStep),
            targetPowerWatts = nextTarget.takeUnless { nextFreeRide },
            workoutStep = nextStep,
        )
        state =
            state.copy(
                changedAt = transitionAt,
                controlMode = if (nextFreeRide) TrainingControlMode.FREE_RIDE else TrainingControlMode.ERG,
                ergRequestedTargetPowerWatts = nextTarget.takeUnless { nextFreeRide },
                ergTargetPowerWatts =
                    if (nextFreeRide && commandApplied) {
                        null
                    } else if (!nextFreeRide && state.ergProtection.status == ErgProtectionStatus.INACTIVE && commandApplied) {
                        nextTarget
                    } else {
                        state.ergTargetPowerWatts
                    },
                ergProtection =
                    if (nextFreeRide || (protectionActive && nextTarget <= 0)) {
                        ErgProtectionState.inactive()
                    } else {
                        state.ergProtection
                    },
                workout = nextProgress,
            )
        ergSpiralDetector.reset(transitionAt)
        stepDistanceStartMeters =
            if (nextStep.completion is WorkoutStepCompletion.Distance) {
                telemetry?.distanceMeters
            } else {
                null
            }
        stepDistanceProgressAtPauseMeters = null
        return state
    }

    private fun targetPower(
        progress: TrainingWorkoutProgress,
        at: Instant,
        targetPercent: Long =
            state.workoutPowerTargetPercent ?: DEFAULT_WORKOUT_POWER_TARGET_PERCENT,
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

    private fun addWorkoutTargetPercent(
        currentPercent: Long,
        deltaPercent: Long,
    ): Long =
        when {
            deltaPercent > 0L && currentPercent > Long.MAX_VALUE - deltaPercent -> Long.MAX_VALUE
            deltaPercent < 0L && currentPercent < Long.MIN_VALUE - deltaPercent -> Long.MIN_VALUE
            else -> currentPercent + deltaPercent
        }

    private fun isFreeRideStep(progress: TrainingWorkoutProgress): Boolean = progress.step.target is WorkoutStepTarget.Open

    private fun refreshWorkoutTarget(
        progress: TrainingWorkoutProgress,
        at: Instant,
    ) {
        if (isFreeRideStep(progress)) {
            if (
                state.controlMode == TrainingControlMode.FREE_RIDE &&
                state.ergRequestedTargetPowerWatts == null &&
                state.ergTargetPowerWatts == null &&
                state.ergProtection.status == ErgProtectionStatus.INACTIVE &&
                !targetSynchronizationPending
            ) {
                return
            }

            val commandApplied =
                trySetFreeRideForExecution("Free Ride workout target", at)
            ergSpiralDetector.reset(at)
            state =
                state.copy(
                    changedAt = at,
                    controlMode = TrainingControlMode.FREE_RIDE,
                    ergRequestedTargetPowerWatts = null,
                    ergTargetPowerWatts = if (commandApplied) null else state.ergTargetPowerWatts,
                    ergProtection = ErgProtectionState.inactive(),
                )
            return
        }

        val nextTarget = targetPower(progress, at)
        val requestedTargetChanged = nextTarget != state.ergRequestedTargetPowerWatts
        if (
            state.controlMode == TrainingControlMode.ERG &&
            !requestedTargetChanged &&
            nextTarget == state.ergTargetPowerWatts &&
            !targetSynchronizationPending
        ) {
            return
        }

        if (state.ergProtection.status != ErgProtectionStatus.INACTIVE) {
            val commandApplied =
                if (targetSynchronizationPending) {
                    trySetTargetForExecution(0, "reconnected ERG protection", at)
                } else {
                    false
                }
            state =
                state.copy(
                    changedAt = at,
                    controlMode = TrainingControlMode.ERG,
                    ergRequestedTargetPowerWatts = nextTarget,
                    ergTargetPowerWatts = if (commandApplied) 0 else state.ergTargetPowerWatts,
                    ergProtection =
                        if (isErgProtectionActive() && nextTarget <= 0) {
                            ErgProtectionState.inactive()
                        } else {
                            state.ergProtection
                        },
                )
            return
        }

        val commandApplied =
            trySetTargetForExecution(nextTarget, "workout target", at)
        state =
            state.copy(
                changedAt = at,
                controlMode = TrainingControlMode.ERG,
                ergRequestedTargetPowerWatts = nextTarget,
                ergTargetPowerWatts = if (commandApplied) nextTarget else state.ergTargetPowerWatts,
            )
    }

    private fun evaluateErgProtection(
        now: Instant,
        telemetry: IndoorBikeTelemetry?,
    ) {
        when (state.ergProtection.status) {
            ErgProtectionStatus.UNAVAILABLE,
            ErgProtectionStatus.RECOVERY_FAILED,
            -> {
                return
            }

            ErgProtectionStatus.RECOVERY_RETRYING -> {
                retryRecovery(now, telemetry)
                return
            }

            ErgProtectionStatus.INACTIVE,
            ErgProtectionStatus.BAILED_OUT,
            -> {
                Unit
            }
        }

        val requestedTarget = state.ergRequestedTargetPowerWatts
        val protectionActive = isErgProtectionActive()
        when (
            val decision =
                ergSpiralDetector.evaluate(
                    now = now,
                    requestedTargetPowerWatts = requestedTarget,
                    telemetry = telemetry,
                    protectionActive = protectionActive,
                )
        ) {
            is ErgProtectionDecision.BailOut -> {
                if (protectionActive) {
                    return
                }
                logger.info(
                    "ERG spiral detected; requesting bailout: sessionId={} cadenceRpm={} " +
                        "requestedTargetPowerWatts={} lowCadenceThresholdRpm={} lowCadenceDuration={}",
                    state.sessionId,
                    decision.cadenceRpm,
                    requestedTarget,
                    ergProtectionProperties.lowCadenceRpm,
                    ergProtectionProperties.lowCadenceDuration,
                )
                try {
                    setTarget(requireActivePowerControl(), 0, "ERG protection")
                } catch (exception: TrainingSessionUnavailableException) {
                    logger.warn(
                        "ERG protection bailout failed: sessionId={} cadenceRpm={} " +
                            "requestedTargetPowerWatts={} error={}",
                        state.sessionId,
                        decision.cadenceRpm,
                        requestedTarget,
                        exception.message,
                        exception,
                    )
                    recordErgProtectionEvent(
                        type = TrainingActivityEventType.ERG_PROTECTION_FAILED,
                        occurredAt = now,
                        cadenceRpm = decision.cadenceRpm,
                    )
                    state =
                        state.copy(
                            changedAt = now,
                            controlMode = TrainingControlMode.ERG,
                            ergTargetPowerWatts = null,
                            ergProtection =
                                ErgProtectionState.unavailable(
                                    changedAt = now,
                                    cadenceRpm = decision.cadenceRpm,
                                    error = exception.message,
                                ),
                        )
                    return
                }

                logger.info(
                    "ERG protection bailout applied: sessionId={} cadenceRpm={} " +
                        "requestedTargetPowerWatts={} appliedTargetPowerWatts=0",
                    state.sessionId,
                    decision.cadenceRpm,
                    requestedTarget,
                )

                recordErgProtectionEvent(
                    type = TrainingActivityEventType.ERG_PROTECTION_STARTED,
                    occurredAt = now,
                    cadenceRpm = decision.cadenceRpm,
                )
                state =
                    state.copy(
                        changedAt = now,
                        controlMode = TrainingControlMode.ERG,
                        ergTargetPowerWatts = 0,
                        ergProtection = ErgProtectionState.bailedOut(now, decision.cadenceRpm),
                    )
            }

            is ErgProtectionDecision.Recover -> {
                val target = requestedTarget ?: return
                logger.info(
                    "ERG spiral recovery detected; requesting target restore: sessionId={} cadenceRpm={} " +
                        "targetPowerWatts={} recoveryCadenceThresholdRpm={} recoveryDuration={} attempt=1",
                    state.sessionId,
                    decision.cadenceRpm,
                    target,
                    ergProtectionProperties.recoveryCadenceRpm,
                    ergProtectionProperties.recoveryDuration,
                )
                attemptRecovery(
                    now = now,
                    cadenceRpm = decision.cadenceRpm,
                    targetPowerWatts = target,
                    attempt = 1,
                )
            }

            null -> {
                Unit
            }
        }
    }

    private fun retryRecovery(
        now: Instant,
        telemetry: IndoorBikeTelemetry?,
    ) {
        val protection = state.ergProtection
        val cadence = ergSpiralDetector.freshRecoveryCadence(now, telemetry)
        if (cadence == null) {
            logger.info(
                "ERG recovery retry cancelled; cadence is no longer fresh and high enough: " +
                    "sessionId={} retryAttempt={} observedCadenceRpm={} recoveryCadenceThresholdRpm={} " +
                    "telemetryReceivedAt={}",
                state.sessionId,
                protection.retryAttempt,
                telemetry?.cadenceRpm,
                ergProtectionProperties.recoveryCadenceRpm,
                telemetry?.receivedAt,
            )
            state =
                state.copy(
                    changedAt = now,
                    ergProtection =
                        ErgProtectionState.bailedOut(
                            changedAt = now,
                            cadenceRpm = protection.cadenceRpm,
                        ),
                )
            return
        }

        val nextRetryAt = protection.nextRetryAt ?: return
        if (now.isBefore(nextRetryAt)) {
            return
        }

        val target = state.ergRequestedTargetPowerWatts
        if (target == null || target <= 0) {
            logger.info(
                "ERG recovery retry cleared because no positive workout target remains: sessionId={} " +
                    "retryAttempt={} targetPowerWatts={}",
                state.sessionId,
                protection.retryAttempt,
                target,
            )
            state =
                state.copy(
                    changedAt = now,
                    controlMode = TrainingControlMode.ERG,
                    ergTargetPowerWatts = 0,
                    ergProtection = ErgProtectionState.inactive(),
                )
            ergSpiralDetector.reset(now)
            return
        }

        attemptRecovery(
            now = now,
            cadenceRpm = cadence,
            targetPowerWatts = target,
            attempt = (protection.retryAttempt ?: 0) + 1,
        )
    }

    private fun attemptRecovery(
        now: Instant,
        cadenceRpm: Double,
        targetPowerWatts: Int,
        attempt: Int,
    ) {
        logger.info(
            "ERG recovery command attempt: sessionId={} attempt={} targetPowerWatts={} cadenceRpm={} " +
                "maxAttempts={}",
            state.sessionId,
            attempt,
            targetPowerWatts,
            cadenceRpm,
            ergProtectionProperties.recoveryRetryMaxAttempts,
        )
        try {
            setTarget(requireActivePowerControl(), targetPowerWatts, "ERG recovery")
        } catch (exception: TrainingSessionUnavailableException) {
            logger.warn(
                "ERG recovery command rejected: sessionId={} attempt={} targetPowerWatts={} cadenceRpm={} " +
                    "error={}",
                state.sessionId,
                attempt,
                targetPowerWatts,
                cadenceRpm,
                exception.message,
                exception,
            )
            recordErgProtectionEvent(
                type = TrainingActivityEventType.ERG_PROTECTION_FAILED,
                occurredAt = now,
                cadenceRpm = cadenceRpm,
            )
            val nextProtection =
                if (activeWorkout != null && attempt < ergProtectionProperties.recoveryRetryMaxAttempts) {
                    ErgProtectionState.recoveryRetrying(
                        changedAt = now,
                        cadenceRpm = cadenceRpm,
                        retryAttempt = attempt,
                        nextRetryAt = now.plus(recoveryRetryDelay(attempt)),
                        error = exception.message,
                    )
                } else if (activeWorkout != null) {
                    ErgProtectionState.recoveryFailed(
                        changedAt = now,
                        cadenceRpm = cadenceRpm,
                        retryAttempt = attempt,
                        error = exception.message,
                    )
                } else {
                    ErgProtectionState.unavailable(
                        changedAt = now,
                        cadenceRpm = cadenceRpm,
                        error = exception.message,
                    )
                }
            state =
                state.copy(
                    changedAt = now,
                    controlMode = TrainingControlMode.ERG,
                    ergTargetPowerWatts = if (activeWorkout != null) 0 else null,
                    ergProtection = nextProtection,
                )
            if (nextProtection.status == ErgProtectionStatus.RECOVERY_RETRYING) {
                logger.info(
                    "ERG recovery retry scheduled: sessionId={} failedAttempt={} nextAttempt={} " +
                        "nextRetryAt={} targetPowerWatts={} cadenceRpm={}",
                    state.sessionId,
                    attempt,
                    attempt + 1,
                    nextProtection.nextRetryAt,
                    targetPowerWatts,
                    cadenceRpm,
                )
            } else {
                logger.warn(
                    "ERG recovery retries exhausted; workout remains at bailout power: sessionId={} " +
                        "attempt={} targetPowerWatts={} cadenceRpm={} status={}",
                    state.sessionId,
                    attempt,
                    targetPowerWatts,
                    cadenceRpm,
                    nextProtection.status,
                )
            }
            return
        }

        logger.info(
            "ERG protection recovered: sessionId={} attempt={} targetPowerWatts={} cadenceRpm={}",
            state.sessionId,
            attempt,
            targetPowerWatts,
            cadenceRpm,
        )

        recordErgProtectionEvent(
            type = TrainingActivityEventType.ERG_PROTECTION_ENDED,
            occurredAt = now,
            cadenceRpm = cadenceRpm,
        )
        state =
            state.copy(
                changedAt = now,
                controlMode = TrainingControlMode.ERG,
                ergTargetPowerWatts = targetPowerWatts,
                ergProtection = ErgProtectionState.inactive(),
            )
    }

    private fun recoveryRetryDelay(attempt: Int): Duration {
        var delay = ergProtectionProperties.recoveryRetryInitialDelay
        repeat((attempt - 1).coerceAtLeast(0)) {
            val doubled = delay.multipliedBy(2)
            delay =
                if (doubled.compareTo(ergProtectionProperties.recoveryRetryMaxDelay) > 0) {
                    ergProtectionProperties.recoveryRetryMaxDelay
                } else {
                    doubled
                }
        }
        return delay
    }

    private fun isErgProtectionActive(): Boolean =
        state.ergProtection.status in
            setOf(
                ErgProtectionStatus.BAILED_OUT,
                ErgProtectionStatus.RECOVERY_RETRYING,
                ErgProtectionStatus.RECOVERY_FAILED,
            )

    private fun recordErgProtectionEvent(
        type: TrainingActivityEventType,
        occurredAt: Instant,
        cadenceRpm: Double?,
    ) {
        val sessionId = state.sessionId ?: return
        logger.debug(
            "ERG protection activity event recorded: sessionId={} type={} cadenceRpm={} occurredAt={}",
            sessionId,
            type,
            cadenceRpm,
            occurredAt,
        )
        activityRecorder.recordEvent(
            sessionId = sessionId,
            event =
                TrainingActivityEvent(
                    type = type,
                    occurredAt = occurredAt,
                    cadenceRpm = cadenceRpm,
                ),
        )
    }

    private fun recordWorkoutTargetAdjustment(
        occurredAt: Instant,
        targetPercent: Long,
        targetPowerWatts: Int?,
    ) {
        val sessionId = state.sessionId ?: return
        activityRecorder.recordEvent(
            sessionId = sessionId,
            event =
                TrainingActivityEvent(
                    type = TrainingActivityEventType.WORKOUT_TARGET_ADJUSTED,
                    occurredAt = occurredAt,
                    workoutPowerTargetPercent = targetPercent,
                    targetPowerWatts = targetPowerWatts,
                ),
        )
    }

    private data class TrainerConnectionAvailability(
        val connected: Boolean,
        val recovered: Boolean,
    )

    private fun ensureTrainerConnection(now: Instant): TrainerConnectionAvailability {
        val currentPowerControl = runCatching { trainingDevice.currentPowerControl() }.getOrNull()
        val telemetryStale =
            state.phase != TrainingSessionPhase.PAUSED &&
                isTelemetryStale(now)
        if (connectionInterruptedAt == null && currentPowerControl != null && !telemetryStale) {
            activePowerControl = currentPowerControl
            updateTrainerConnectionStatus(
                status = TrainerConnectionStatus.CONNECTED,
                at = now,
                retryAttempt = null,
                error = null,
            )
            return TrainerConnectionAvailability(connected = true, recovered = false)
        }

        if (connectionInterruptedAt == null) {
            markConnectionInterrupted(now)
        }

        val pendingRecovery = connectionRecovery
        if (pendingRecovery != null) {
            val future = pendingRecovery.toCompletableFuture()
            if (!future.isDone) {
                updateTrainerConnectionStatus(
                    status = TrainerConnectionStatus.RECONNECTING,
                    at = now,
                    retryAttempt = connectionRecoveryAttempt.takeIf { it > 0 },
                    error = state.trainerConnectionError,
                )
                return TrainerConnectionAvailability(connected = false, recovered = false)
            }

            connectionRecovery = null
            val recoveryResult = runCatching { future.join() }
            val recoveredPowerControl = recoveryResult.getOrNull()
            if (recoveredPowerControl != null) {
                val latestPowerControl = runCatching { trainingDevice.currentPowerControl() }.getOrNull()
                return trainerConnectionRecovered(
                    powerControl = latestPowerControl ?: recoveredPowerControl,
                    at = now,
                )
            }

            val failure =
                recoveryResult.exceptionOrNull()?.let { exception ->
                    exception.cause?.message ?: exception.message
                } ?: "Trainer reconnection failed"
            val currentPowerControlAfterRecovery =
                runCatching { trainingDevice.currentPowerControl() }.getOrNull()
            if (currentPowerControlAfterRecovery != null) {
                telemetryObserved = false
                lastTelemetryReceivedAt = null
            }
            activePowerControl = null
            nextConnectionRecoveryAt = now.plus(connectionRecoveryDelay(connectionRecoveryAttempt.coerceAtLeast(1)))
            updateTrainerConnectionStatus(
                status = TrainerConnectionStatus.RECONNECTING,
                at = now,
                retryAttempt = connectionRecoveryAttempt.takeIf { it > 0 },
                error = failure,
            )
            return TrainerConnectionAvailability(connected = false, recovered = false)
        }

        val nextRecoveryAt = nextConnectionRecoveryAt
        if (nextRecoveryAt != null && now.isBefore(nextRecoveryAt)) {
            return TrainerConnectionAvailability(connected = false, recovered = false)
        }

        val attempt = connectionRecoveryAttempt + 1
        connectionRecoveryAttempt = attempt
        recordTrainerConnectionEvent(
            type = TrainingActivityEventType.TRAINER_RECONNECT_ATTEMPTED,
            occurredAt = now,
            retryAttempt = attempt,
        )
        logger.info(
            "Trainer connection recovery attempt: sessionId={} attempt={}",
            state.sessionId,
            attempt,
        )

        val recoveryStage: CompletionStage<IndoorBikePowerControl?> =
            if (currentPowerControl != null && !telemetryStale) {
                CompletableFuture.completedFuture(currentPowerControl)
            } else {
                runCatching { trainingDevice.reconnectPowerControl(force = telemetryStale) }
                    .onFailure { exception ->
                        logger.warn(
                            "Trainer connection recovery could not start: sessionId={} attempt={} error={}",
                            state.sessionId,
                            attempt,
                            exception.message,
                            exception,
                        )
                    }.getOrElse {
                        CompletableFuture.completedFuture<IndoorBikePowerControl?>(null)
                    }
            }
        startTrainerRecovery(now, recoveryStage)
        return TrainerConnectionAvailability(connected = false, recovered = false)
    }

    private fun startTrainerRecovery(
        now: Instant,
        recoveryStage: CompletionStage<IndoorBikePowerControl?>,
    ) {
        updateTrainerConnectionStatus(
            status = TrainerConnectionStatus.RECONNECTING,
            at = now,
            retryAttempt = connectionRecoveryAttempt,
            error = state.trainerConnectionError,
        )
        connectionRecovery =
            try {
                recoveryStage.thenApplyAsync { powerControl ->
                    powerControl?.also { it.requestControl() }
                }
            } catch (exception: Exception) {
                logger.warn(
                    "Trainer connection recovery could not be prepared: sessionId={} error={}",
                    state.sessionId,
                    exception.message,
                    exception,
                )
                CompletableFuture.completedFuture(null)
            }
    }

    private fun trainerConnectionRecovered(
        powerControl: IndoorBikePowerControl,
        at: Instant,
    ): TrainerConnectionAvailability {
        activePowerControl = powerControl
        connectionInterruptedAt = null
        nextConnectionRecoveryAt = null
        connectionRecoveryAttempt = 0
        targetSynchronizationPending = true
        targetSynchronizationAt = at
        telemetryObserved = false
        lastTelemetryReceivedAt = null
        rebindRecordingListenersAfterRecovery()
        state =
            state.copy(
                changedAt = at,
                trainerConnection = TrainerConnectionStatus.CONNECTED,
                trainerConnectionRetryAttempt = null,
                trainerConnectionError = null,
                heartRate = activeHeartRateSourceId?.let(trainingDevice::currentHeartRate),
            )
        recordTrainerConnectionEvent(
            type = TrainingActivityEventType.TRAINER_RECONNECTED,
            occurredAt = at,
        )
        logger.info("Trainer connection recovered: sessionId={}", state.sessionId)
        return TrainerConnectionAvailability(connected = true, recovered = true)
    }

    private fun markConnectionInterrupted(
        at: Instant,
        error: String? = null,
    ) {
        if (connectionInterruptedAt != null) {
            if (error != null) {
                state = state.copy(changedAt = at, trainerConnectionError = error)
            }
            return
        }

        connectionInterruptedAt = at
        nextConnectionRecoveryAt = at
        connectionRecoveryAttempt = 0
        activePowerControl = null
        targetSynchronizationPending = true
        targetSynchronizationAt = null
        telemetryRegistration?.close()
        telemetryRegistration = null
        updateTrainerConnectionStatus(
            status = TrainerConnectionStatus.INTERRUPTED,
            at = at,
            retryAttempt = null,
            error = error,
        )
        state = state.copy(heartRate = activeHeartRateSourceId?.let(trainingDevice::currentHeartRate))
        recordTrainerConnectionEvent(
            type = TrainingActivityEventType.TRAINER_CONNECTION_INTERRUPTED,
            occurredAt = at,
        )
        logger.warn("Trainer connection interrupted: sessionId={} at={}", state.sessionId, at)
    }

    private fun rebindRecordingListenersAfterRecovery() {
        val sessionId = state.sessionId ?: return
        telemetryRegistration?.close()
        telemetryRegistration = null
        heartRateRegistration?.close()
        heartRateRegistration = null
        if (state.phase == TrainingSessionPhase.ACTIVE) {
            registerTelemetry(sessionId)
            registerHeartRate(sessionId, activeHeartRateSourceId)
        }
    }

    private fun updateTrainerConnectionStatus(
        status: TrainerConnectionStatus,
        at: Instant,
        retryAttempt: Int?,
        error: String?,
    ) {
        if (
            state.trainerConnection == status &&
            state.trainerConnectionRetryAttempt == retryAttempt &&
            state.trainerConnectionError == error
        ) {
            return
        }
        state =
            state.copy(
                changedAt = at,
                trainerConnection = status,
                trainerConnectionRetryAttempt = retryAttempt,
                trainerConnectionError = error,
            )
    }

    private fun connectionRecoveryDelay(attempt: Int): Duration {
        var delay = CONNECTION_RECOVERY_INITIAL_DELAY
        repeat((attempt - 1).coerceAtLeast(0)) {
            val doubled = delay.multipliedBy(2)
            delay = if (doubled > CONNECTION_RECOVERY_MAX_DELAY) CONNECTION_RECOVERY_MAX_DELAY else doubled
        }
        return delay
    }

    private fun recordTrainerConnectionEvent(
        type: TrainingActivityEventType,
        occurredAt: Instant,
        retryAttempt: Int? = null,
        targetPowerWatts: Int? = null,
    ) {
        val sessionId = state.sessionId ?: return
        activityRecorder.recordEvent(
            sessionId = sessionId,
            event =
                TrainingActivityEvent(
                    type = type,
                    occurredAt = occurredAt,
                    retryAttempt = retryAttempt,
                    targetPowerWatts = targetPowerWatts,
                ),
        )
    }

    private fun trySetTargetForExecution(
        powerWatts: Int,
        description: String,
        at: Instant,
    ): Boolean {
        val powerControl = activePowerControl ?: return false
        return try {
            setTarget(powerControl, powerWatts, description)
            true
        } catch (exception: TrainingSessionUnavailableException) {
            if (runCatching { trainingDevice.currentPowerControl() }.getOrNull() == null) {
                markConnectionInterrupted(at)
                false
            } else {
                throw exception
            }
        }
    }

    private fun trySetFreeRideForExecution(
        description: String,
        at: Instant,
    ): Boolean {
        val powerControl = activePowerControl ?: return false
        return try {
            setFreeRide(powerControl, description)
            true
        } catch (exception: TrainingSessionUnavailableException) {
            if (runCatching { trainingDevice.currentPowerControl() }.getOrNull() == null) {
                markConnectionInterrupted(at)
                false
            } else {
                throw exception
            }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val DEFAULT_WORKOUT_POWER_TARGET_PERCENT = 100L
        val CONNECTION_RECOVERY_INITIAL_DELAY: Duration = Duration.ofSeconds(1)
        val CONNECTION_RECOVERY_MAX_DELAY: Duration = Duration.ofSeconds(8)
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
        logger.debug(
            "ERG target command: sessionId={} targetPowerWatts={} reason={}",
            state.sessionId,
            powerWatts,
            description,
        )
        try {
            powerControl.setTargetPower(powerWatts)
            logger.debug(
                "ERG target command accepted: sessionId={} targetPowerWatts={} reason={}",
                state.sessionId,
                powerWatts,
                description,
            )
            recordTargetSynchronizationIfPending(powerWatts)
        } catch (exception: Exception) {
            logger.warn(
                "ERG target command rejected: sessionId={} targetPowerWatts={} reason={} error={}",
                state.sessionId,
                powerWatts,
                description,
                exception.message,
                exception,
            )
            throw TrainingSessionUnavailableException(
                "The connected device rejected the $description target",
                exception,
            )
        }
    }

    private fun setFreeRide(
        powerControl: IndoorBikePowerControl,
        description: String,
    ) {
        logger.debug(
            "Free Ride command: sessionId={} reason={}",
            state.sessionId,
            description,
        )
        try {
            powerControl.setFreeRide()
            logger.debug(
                "Free Ride command accepted: sessionId={} reason={}",
                state.sessionId,
                description,
            )
            recordTargetSynchronizationIfPending(null)
        } catch (exception: Exception) {
            logger.warn(
                "Free Ride command rejected: sessionId={} reason={} error={}",
                state.sessionId,
                description,
                exception.message,
                exception,
            )
            throw TrainingSessionUnavailableException(
                "The connected device rejected the $description command",
                exception,
            )
        }
    }

    private fun requireActiveSession(sessionId: UUID): TrainingSessionState =
        when {
            state.phase != TrainingSessionPhase.ACTIVE -> {
                throw TrainingSessionNotActiveException()
            }

            state.sessionId != sessionId -> {
                throw TrainingSessionMismatchException(sessionId)
            }

            else -> {
                state
            }
        }

    private fun requirePausedSession(sessionId: UUID): TrainingSessionState =
        when {
            state.sessionId != sessionId -> {
                throw TrainingSessionMismatchException(sessionId)
            }

            state.phase != TrainingSessionPhase.PAUSED -> {
                throw TrainingSessionResumeNotAllowedException(
                    "The training session is not paused",
                )
            }

            else -> {
                state
            }
        }

    private fun requireSessionInProgress(sessionId: UUID): TrainingSessionState =
        when {
            state.sessionId != sessionId -> {
                throw TrainingSessionMismatchException(sessionId)
            }

            state.phase !in setOf(TrainingSessionPhase.ACTIVE, TrainingSessionPhase.PAUSED) -> {
                throw TrainingSessionNotActiveException()
            }

            else -> {
                state
            }
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

    private fun clearActiveExecution(preserveTrainerRecovery: Boolean = false) {
        activePowerControl = null
        activeHeartRateSourceId = null
        if (!preserveTrainerRecovery) {
            connectionInterruptedAt = null
            nextConnectionRecoveryAt = null
            connectionRecoveryAttempt = 0
            connectionRecovery = null
            targetSynchronizationPending = false
            targetSynchronizationAt = null
            pendingZeroPowerCommand = false
            lastTelemetryReceivedAt = null
            telemetryObserved = false
        }
        pausedAt = null
        pausedErgTargetPowerWatts = null
        pausedControlMode = null
        clearActiveWorkout()
    }

    private fun clearActiveWorkout() {
        activeWorkout = null
        stepDistanceStartMeters = null
        stepDistanceProgressAtPauseMeters = null
    }

    private fun recordTargetSynchronizationIfPending(targetPowerWatts: Int?) {
        if (!targetSynchronizationPending) {
            return
        }

        recordTrainerConnectionEvent(
            type = TrainingActivityEventType.TRAINER_TARGET_SYNCHRONIZED,
            occurredAt = targetSynchronizationAt ?: clock.instant(),
            targetPowerWatts = targetPowerWatts,
        )
        targetSynchronizationPending = false
        targetSynchronizationAt = null
    }

    private fun synchronizeCurrentControl(at: Instant) {
        if (
            pendingZeroPowerCommand ||
            state.phase == TrainingSessionPhase.PAUSED ||
            state.ergProtection.status == ErgProtectionStatus.UNAVAILABLE
        ) {
            val commandApplied = trySetTargetForExecution(0, "reconnected 0 W hold", at)
            if (commandApplied) {
                pendingZeroPowerCommand = false
            }
            state =
                state.copy(
                    changedAt = at,
                    ergTargetPowerWatts = if (commandApplied) 0 else state.ergTargetPowerWatts,
                    ergProtection =
                        if (commandApplied && state.ergProtection.status == ErgProtectionStatus.UNAVAILABLE) {
                            ErgProtectionState.bailedOut(at, state.ergProtection.cadenceRpm)
                        } else {
                            state.ergProtection
                        },
                )
            return
        }
        if (state.controlMode == TrainingControlMode.FREE_RIDE) {
            val commandApplied = trySetFreeRideForExecution("reconnected Free Ride", at)
            state =
                state.copy(
                    changedAt = at,
                    ergRequestedTargetPowerWatts = null,
                    ergTargetPowerWatts = if (commandApplied) null else state.ergTargetPowerWatts,
                )
            return
        }

        val targetPowerWatts =
            if (state.ergProtection.status == ErgProtectionStatus.UNAVAILABLE || isErgProtectionActive()) {
                0
            } else {
                state.ergRequestedTargetPowerWatts ?: state.ergTargetPowerWatts ?: 0
            }
        val commandApplied = trySetTargetForExecution(targetPowerWatts, "reconnected ERG target", at)
        state =
            state.copy(
                changedAt = at,
                ergTargetPowerWatts = if (commandApplied) targetPowerWatts else state.ergTargetPowerWatts,
            )
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
