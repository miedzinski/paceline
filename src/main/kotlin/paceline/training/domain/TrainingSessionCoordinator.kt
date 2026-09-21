package paceline.training.domain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.config.TelemetryProperties
import paceline.device.domain.CyclingTelemetry
import paceline.device.domain.HeartRateTelemetry
import paceline.device.domain.TelemetryProjection
import paceline.device.domain.isTelemetryFresh
import paceline.training.config.ErgProtectionProperties
import paceline.training.ports.ActivityUploadException
import paceline.training.ports.ActivityUploader
import paceline.training.ports.ConnectedRideSource
import paceline.training.ports.RideSourceCatalog
import paceline.training.ports.SelectedRideEquipment
import paceline.workout.domain.ExecutableWorkout
import paceline.workout.domain.WorkoutStepCompletion
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class TrainingSessionCoordinator(
    private val rideSourceCatalog: RideSourceCatalog,
    private val clock: Clock = Clock.systemUTC(),
    private val activityUploader: ActivityUploader,
    private val ergProtectionProperties: ErgProtectionProperties,
    private val telemetryProperties: TelemetryProperties = TelemetryProperties(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var state = TrainingSessionState.notStarted(clock.instant())
    private val workoutExecution = WorkoutExecution()
    private val rideEquipment = RideEquipmentSelector()
    private val activitySession = TrainingActivitySession()
    private var lastCyclingReceivedAt: Instant? = null
    private var lastHeartRateReceivedAt: Instant? = null
    private val trainerConnection =
        TrainerControlCoordinator(
            clock = clock,
            telemetryFreshness = telemetryProperties.freshness,
            onInterrupted = ::onTrainerConnectionInterrupted,
            onRecovered = ::onTrainerConnectionRecovered,
            recordActivityEvent = ::recordActivityEvent,
        )
    private val ergProtection =
        ErgProtectionCoordinator(
            properties = ergProtectionProperties,
            telemetryFreshness = telemetryProperties.freshness,
            setTarget = { powerWatts, description, at ->
                trainerConnection.trySetTargetForExecution(powerWatts, description, at)
            },
            recordActivityEvent = ::recordActivityEvent,
        )

    @Synchronized
    fun current(): TrainingSessionState {
        refreshRideEquipment()
        refreshTelemetry()
        return state
    }

    @Synchronized
    fun equipment(): RideEquipmentState = rideEquipment.current(rideSourceCatalog.sources())

    @Synchronized
    fun selectEquipment(selection: RideEquipmentSelection): RideEquipmentState {
        if (state.phase in setOf(TrainingSessionPhase.ACTIVE, TrainingSessionPhase.PAUSED)) {
            throw TrainingSessionAlreadyActiveException()
        }
        return rideEquipment.apply(selection, rideSourceCatalog.sources())
    }

    @Synchronized
    fun clearEquipmentRole(role: RideRole): RideEquipmentState {
        if (state.phase in setOf(TrainingSessionPhase.ACTIVE, TrainingSessionPhase.PAUSED)) {
            throw TrainingSessionAlreadyActiveException()
        }
        return rideEquipment.clear(role, rideSourceCatalog.sources())
    }

    @Synchronized
    fun start(
        workout: ExecutableWorkout? = null,
        equipment: RideEquipmentSelection? = null,
    ): TrainingSessionState {
        if (state.phase in setOf(TrainingSessionPhase.ACTIVE, TrainingSessionPhase.PAUSED)) {
            throw TrainingSessionAlreadyActiveException()
        }
        if (trainerConnection.hasPendingZeroPowerCommand()) {
            throw TrainingSessionUnavailableException(
                "The previous session is still waiting to confirm a 0 W trainer command",
            )
        }

        val resolvedEquipment = resolveRideEquipment(equipment)
        val selectedEquipment = bindRideEquipment(resolvedEquipment)
        val trainer = selectedEquipment.trainerControlConnection
        val trainerControl =
            trainer.control()
                ?: throw TrainingSessionUnavailableException(
                    "A connected trainer with resistance control is required to start a training session",
                )
        val initialControlTelemetry = selectedEquipment.controlTelemetry?.current()
        trainerConnection.requestControl(trainerControl, reason = "")

        val now = clock.instant()
        lastCyclingReceivedAt = null
        lastHeartRateReceivedAt = null
        ergProtection.reset(now)
        val progress = workout?.let { TrainingWorkoutProgress.firstStep(it, now) }
        val initialFreeRide = workout == null || progress?.let(workoutExecution::isFreeRide) == true
        val initialTarget =
            progress
                ?.takeUnless(workoutExecution::isFreeRide)
                ?.let { workoutExecution.targetPower(it, now, DEFAULT_WORKOUT_POWER_TARGET_PERCENT) }
        if (initialFreeRide) {
            trainerConnection.setFreeRide(trainerControl, "initial Free Ride")
        } else if (initialTarget != null) {
            trainerConnection.setTarget(trainerControl, initialTarget, "initial workout")
        }

        val sessionId = UUID.randomUUID()
        trainerConnection.beginSession(
            sessionId = sessionId,
            trainer = trainer,
            trainerControl = trainerControl,
            trainerTelemetry = selectedEquipment.controlTelemetry,
        )
        if (workout != null && progress != null) {
            workoutExecution.attach(workout, progress, initialControlTelemetry)
        } else {
            workoutExecution.clear()
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
                    equipment = resolvedEquipment,
                )
        activitySession.start(
            sessionId = sessionId,
            startedAt = now,
            workout = workout,
            initialTargetPowerWatts = initialTarget,
            onTelemetry = trainerConnection::observeTelemetry,
            onHeartRate = ::onHeartRate,
            equipment = selectedEquipment,
            onSourceTelemetry = { sourceId, telemetry ->
                synchronized(this) {
                    if (sourceId == selectedEquipment.selection.controlSourceId) {
                        trainerConnection.observeTelemetry(telemetry)
                    }
                    refreshTelemetry()
                }
            },
        )
        trainerConnection.observeTelemetry(initialControlTelemetry)
        refreshTelemetry(now)
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
        if (progress.completed || !workoutExecution.hasActiveWorkout()) {
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
        val nextPercent = workoutExecution.addTargetPercent(currentPercent, deltaPercent)
        val changedAt = clock.instant()
        val nextFreeRide = workoutExecution.isFreeRide(progress)
        val nextTarget = workoutExecution.targetPower(progress, changedAt, nextPercent)

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
            ergProtection.reset(changedAt)
            state =
                activeState.copy(
                    changedAt = changedAt,
                    ergRequestedTargetPowerWatts = nextTarget,
                    ergTargetPowerWatts =
                        if (nextTarget <= 0) 0 else activeState.ergTargetPowerWatts,
                    workoutPowerTargetPercent = nextPercent,
                    ergProtection =
                        if (ergProtection.isActive(activeState) && nextTarget <= 0) {
                            ErgProtectionState.inactive()
                        } else {
                            activeState.ergProtection
                        },
                )
            recordWorkoutTargetAdjustment(changedAt, nextPercent, nextTarget)
            return state
        }

        trainerConnection.setTarget(
            trainerConnection.activeTrainerControlOrThrow(),
            nextTarget,
            "workout target adjustment",
        )
        ergProtection.reset(changedAt)
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
    fun setTargetPower(
        sessionId: UUID,
        powerWatts: Int,
    ): TrainingSessionState {
        val activeState = requireActiveSession(sessionId)
        if (workoutExecution.hasActiveWorkout()) {
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
            ergProtection.reset(changedAt)
            state =
                activeState.copy(
                    changedAt = changedAt,
                    controlMode = TrainingControlMode.ERG,
                    ergRequestedTargetPowerWatts = powerWatts,
                )
            return state
        }

        val trainerControl = trainerConnection.activeTrainerControlOrThrow()
        trainerConnection.setTarget(trainerControl, powerWatts, "ERG target")
        ergProtection.reset(changedAt)

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
            if (trainerConnection.currentControl() != null) {
                trainerConnection.trySetTargetForExecution(0, "0 W pause", pauseAt)
            } else {
                trainerConnection.markConnectionInterrupted(pauseAt)
                false
            }
        workoutExecution.captureDistanceProgressAtPause(activeState.workout, currentControlTelemetry())

        activitySession.pauseRecording()
        recordActivityEvent(
            sessionId,
            TrainingActivityEvent(
                type = TrainingActivityEventType.TRAINING_PAUSED,
                occurredAt = pauseAt,
            ),
        )
        ergProtection.reset(pauseAt)
        state =
            activeState.copy(
                phase = TrainingSessionPhase.PAUSED,
                changedAt = pauseAt,
                pauseStartedAt = pauseAt,
                controlMode = activeState.controlMode,
                ergRequestedTargetPowerWatts = targetPowerWatts,
                ergTargetPowerWatts = if (commandApplied) 0 else null,
                ergProtection = ErgProtectionState.inactive(),
                telemetry = interruptTelemetry(activeState.telemetry),
            )
        return state
    }

    @Synchronized
    fun resume(sessionId: UUID): TrainingSessionState {
        val pausedState = requirePausedSession(sessionId)
        val trainerControl = trainerConnection.activeTrainerControlOrThrow()
        val resumedAt = clock.instant()
        val pauseStartedAt =
            pausedState.pauseStartedAt
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
        val resumedControlMode = pausedState.controlMode
        val freeRide =
            resumedControlMode == TrainingControlMode.FREE_RIDE ||
                resumedWorkout?.let(workoutExecution::isFreeRide) == true
        val targetPowerWatts =
            if (freeRide) {
                null
            } else {
                if (workoutExecution.hasActiveWorkout()) {
                    resumedWorkout?.let { workoutProgress ->
                        workoutExecution.targetPower(
                            progress = workoutProgress,
                            at = resumedAt,
                            targetPercent =
                                pausedState.workoutPowerTargetPercent ?: DEFAULT_WORKOUT_POWER_TARGET_PERCENT,
                        )
                    }
                } else {
                    null
                } ?: pausedState.ergRequestedTargetPowerWatts
                    ?: 0
            }

        trainerConnection.requestControl(trainerControl, reason = " on resume")
        if (freeRide) {
            trainerConnection.setFreeRide(trainerControl, "resume Free Ride")
        } else {
            trainerConnection.setTarget(trainerControl, requireNotNull(targetPowerWatts), "resume")
        }

        workoutExecution.resumeDistanceTracking(progress, currentControlTelemetry())
        ergProtection.reset(resumedAt)
        activitySession.resumeRecording(sessionId)
        recordActivityEvent(
            sessionId,
            TrainingActivityEvent(
                type = TrainingActivityEventType.TRAINING_RESUMED,
                occurredAt = resumedAt,
            ),
        )
        state =
            pausedState.copy(
                phase = TrainingSessionPhase.ACTIVE,
                changedAt = resumedAt,
                pauseStartedAt = null,
                controlMode = if (freeRide) TrainingControlMode.FREE_RIDE else TrainingControlMode.ERG,
                ergRequestedTargetPowerWatts = targetPowerWatts,
                ergTargetPowerWatts = targetPowerWatts,
                ergProtection = ErgProtectionState.inactive(),
                workout = resumedWorkout ?: progress,
            )
        refreshTelemetry(resumedAt)
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
        if (progress.completed || !workoutExecution.hasActiveWorkout()) {
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
        val controlTelemetry = currentControlTelemetry()
        trainerConnection.observeTelemetry(controlTelemetry)
        val connection = ensureTrainerConnection(now)
        val telemetry = if (connection.connected) currentExecutionTelemetry(controlTelemetry, now) else null
        updateCyclingTelemetry(telemetry)
        return advanceFrom(progress, now, telemetry)
    }

    @Synchronized
    fun tick(now: Instant = clock.instant()): TrainingSessionState = tickInternal(now, currentControlTelemetry())

    @Synchronized
    fun tick(
        now: Instant,
        telemetry: CyclingTelemetry?,
    ): TrainingSessionState = tickInternal(now, telemetry)

    private fun tickInternal(
        now: Instant,
        telemetry: CyclingTelemetry?,
    ): TrainingSessionState {
        if (
            state.phase != TrainingSessionPhase.ACTIVE &&
            state.phase != TrainingSessionPhase.PAUSED &&
            !(state.phase == TrainingSessionPhase.STOPPED && trainerConnection.hasPendingZeroPowerCommand())
        ) {
            return state
        }
        refreshRideEquipment()
        val suppliedTelemetry = telemetry
        val controlTelemetry =
            if (state.equipment?.selection?.controlSourceId != null) {
                suppliedTelemetry?.let { currentControlTelemetry() }
            } else {
                suppliedTelemetry
            }
        trainerConnection.observeTelemetry(controlTelemetry)
        val connection = ensureTrainerConnection(now)
        val effectiveControlTelemetry =
            if (!connection.connected) {
                null
            } else if (connection.recovered) {
                currentControlTelemetry()
            } else {
                controlTelemetry
            }
        val effectiveTelemetry = currentExecutionTelemetry(effectiveControlTelemetry, now)
        updateCyclingTelemetry(effectiveTelemetry)
        trainerConnection.observeTelemetry(effectiveControlTelemetry)
        return try {
            if (state.ergProtection.status == ErgProtectionStatus.UNAVAILABLE) {
                if (connection.connected && trainerConnection.hasPendingTargetSynchronization()) {
                    synchronizeCurrentControl(now)
                }
                return state
            }
            if (state.phase == TrainingSessionPhase.ACTIVE && workoutExecution.hasActiveWorkout()) {
                while (state.phase == TrainingSessionPhase.ACTIVE && workoutExecution.hasActiveWorkout()) {
                    val progress = state.workout ?: break
                    if (workoutExecution.isComplete(progress, now, effectiveTelemetry)) {
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
            if (connection.connected && trainerConnection.hasPendingTargetSynchronization()) {
                synchronizeCurrentControl(now)
            }
            if (state.phase == TrainingSessionPhase.ACTIVE && connection.connected) {
                state = ergProtection.evaluate(state, now, effectiveTelemetry, workoutExecution.hasActiveWorkout())
            }
            state
        } catch (exception: TrainingSessionUnavailableException) {
            if (trainerConnection.hasPendingTargetSynchronization()) {
                trainerConnection.markConnectionInterrupted(now, exception.message)
            }
            state
        }
    }

    @Synchronized
    fun stop(sessionId: UUID): TrainingSessionState {
        val activeState = requireSessionInProgress(sessionId)
        val stoppedAt = clock.instant()
        if (activeState.phase == TrainingSessionPhase.ACTIVE) {
            refreshTelemetry(stoppedAt)
        }
        val stateAtStop = state
        val commandApplied =
            if (trainerConnection.currentControl() != null) {
                trainerConnection.trySetTargetForExecution(0, "0 W stop", stoppedAt)
            } else {
                trainerConnection.markConnectionInterrupted(stoppedAt)
                false
            }
        trainerConnection.markZeroPowerCommandPending(!commandApplied)

        val completedActivity = activitySession.finish(sessionId, stoppedAt)

        state =
            stateAtStop.copy(
                phase = TrainingSessionPhase.STOPPED,
                changedAt = stoppedAt,
                pauseStartedAt = null,
                controlMode = TrainingControlMode.ERG,
                ergRequestedTargetPowerWatts = 0,
                ergTargetPowerWatts = if (commandApplied) 0 else null,
                workoutPowerTargetPercent = null,
                ergProtection = ErgProtectionState.inactive(),
                workout = null,
                activityUpload =
                    if (completedActivity.exportSamples.isNotEmpty()) {
                        TrainingActivityUploadState.available()
                    } else {
                        TrainingActivityUploadState.unavailable()
                    },
                activitySummary = completedActivity.summary(),
            )
        workoutExecution.clear()
        trainerConnection.clear(preserveRecovery = trainerConnection.hasPendingZeroPowerCommand())
        return state
    }

    @Synchronized
    fun discard(sessionId: UUID): TrainingSessionState {
        requireStoppedSession(sessionId)
        return clearStoppedSession(sessionId, clock.instant())
    }

    @Synchronized
    fun upload(sessionId: UUID): TrainingSessionState {
        val stoppedState = requireStoppedSession(sessionId)
        val activity =
            activitySession.completedActivity()
                ?: throw TrainingActivityUploadUnavailableException(
                    "The stopped session has no in-memory activity recording",
                )
        if (activity.exportSamples.isEmpty()) {
            throw TrainingActivityUploadUnavailableException(
                "The stopped session has no telemetry to upload",
            )
        }
        state =
            stoppedState.copy(
                activityUpload = TrainingActivityUploadState(TrainingActivityUploadPhase.UPLOADING),
            )
        return try {
            try {
                activityUploader.upload(activity)
            } catch (exception: ActivityUploadException) {
                throw exception
            } catch (exception: Exception) {
                throw ActivityUploadException("The training activity could not be uploaded", exception)
            }
            clearStoppedSession(sessionId, clock.instant())
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
        }
    }

    private fun clearStoppedSession(
        sessionId: UUID,
        clearedAt: Instant,
    ): TrainingSessionState {
        activitySession.discard(sessionId)
        workoutExecution.clear()
        trainerConnection.clear()
        lastCyclingReceivedAt = null
        lastHeartRateReceivedAt = null
        state = TrainingSessionState.notStarted(clearedAt)
        return state
    }

    private fun advanceFrom(
        progress: TrainingWorkoutProgress,
        transitionAt: Instant,
        telemetry: CyclingTelemetry?,
    ): TrainingSessionState {
        if (state.ergProtection.status == ErgProtectionStatus.UNAVAILABLE) {
            throw TrainingSessionUnavailableException(
                "The workout cannot advance while ERG protection is unavailable",
            )
        }
        val nextProgress = workoutExecution.nextProgress(progress, transitionAt)
        if (nextProgress == null) {
            val freeRideApplied =
                if (
                    state.controlMode != TrainingControlMode.FREE_RIDE ||
                    trainerConnection.hasPendingTargetSynchronization()
                ) {
                    trainerConnection.trySetFreeRideForExecution("workout completion Free Ride", transitionAt)
                } else {
                    true
                }
            activitySession.completeWorkout(
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
            ergProtection.reset(transitionAt)
            workoutExecution.clear()
            return state
        }

        val nextIndex = nextProgress.currentStepNumber - 1
        val nextTarget =
            workoutExecution.targetPower(
                progress = nextProgress,
                at = transitionAt,
                targetPercent = state.workoutPowerTargetPercent ?: DEFAULT_WORKOUT_POWER_TARGET_PERCENT,
            )
        val nextFreeRide = workoutExecution.isFreeRide(nextProgress)
        val protectionActive = ergProtection.isActive(state)
        val commandApplied =
            when {
                nextFreeRide &&
                    (
                        state.controlMode != TrainingControlMode.FREE_RIDE ||
                            trainerConnection.hasPendingTargetSynchronization()
                    ) -> {
                    trainerConnection.trySetFreeRideForExecution("workout step ${nextIndex + 1} Free Ride", transitionAt)
                }

                nextFreeRide -> {
                    true
                }

                state.ergProtection.status == ErgProtectionStatus.INACTIVE -> {
                    trainerConnection.trySetTargetForExecution(nextTarget, "workout step ${nextIndex + 1}", transitionAt)
                }

                else -> {
                    false
                }
            }
        activitySession.startWorkoutSegment(
            sessionId = requireNotNull(state.sessionId),
            startedAt = transitionAt,
            stepNumber = nextIndex + 1,
            totalSteps = nextProgress.totalSteps,
            targetPowerWatts = nextTarget.takeUnless { nextFreeRide },
            workoutStep = nextProgress.step,
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
        ergProtection.reset(transitionAt)
        workoutExecution.beginStep(nextProgress, telemetry)
        return state
    }

    private fun refreshWorkoutTarget(
        progress: TrainingWorkoutProgress,
        at: Instant,
    ) {
        if (workoutExecution.isFreeRide(progress)) {
            if (
                state.controlMode == TrainingControlMode.FREE_RIDE &&
                state.ergRequestedTargetPowerWatts == null &&
                state.ergTargetPowerWatts == null &&
                state.ergProtection.status == ErgProtectionStatus.INACTIVE &&
                !trainerConnection.hasPendingTargetSynchronization()
            ) {
                return
            }

            val commandApplied =
                trainerConnection.trySetFreeRideForExecution("Free Ride workout target", at)
            ergProtection.reset(at)
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

        val nextTarget =
            workoutExecution.targetPower(
                progress = progress,
                at = at,
                targetPercent = state.workoutPowerTargetPercent ?: DEFAULT_WORKOUT_POWER_TARGET_PERCENT,
            )
        val requestedTargetChanged = nextTarget != state.ergRequestedTargetPowerWatts
        if (
            state.controlMode == TrainingControlMode.ERG &&
            !requestedTargetChanged &&
            nextTarget == state.ergTargetPowerWatts &&
            !trainerConnection.hasPendingTargetSynchronization()
        ) {
            return
        }

        if (state.ergProtection.status != ErgProtectionStatus.INACTIVE) {
            val commandApplied =
                if (trainerConnection.hasPendingTargetSynchronization()) {
                    trainerConnection.trySetTargetForExecution(0, "reconnected ERG protection", at)
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
                        if (ergProtection.isActive(state) && nextTarget <= 0) {
                            ErgProtectionState.inactive()
                        } else {
                            state.ergProtection
                        },
                )
            return
        }

        val commandApplied =
            trainerConnection.trySetTargetForExecution(nextTarget, "workout target", at)
        state =
            state.copy(
                changedAt = at,
                controlMode = TrainingControlMode.ERG,
                ergRequestedTargetPowerWatts = nextTarget,
                ergTargetPowerWatts = if (commandApplied) nextTarget else state.ergTargetPowerWatts,
            )
    }

    private fun recordWorkoutTargetAdjustment(
        occurredAt: Instant,
        targetPercent: Long,
        targetPowerWatts: Int?,
    ) {
        val sessionId = state.sessionId ?: return
        activitySession.recordEvent(
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
                throw TrainingSessionNotStoppedException()
            }

            else -> {
                state
            }
        }

    private fun resolveRideEquipment(requested: RideEquipmentSelection?): RideEquipmentState {
        val sources = rideSourceCatalog.sources()
        requested?.let { rideEquipment.apply(it, sources) }
        val resolved = rideEquipment.current(sources)
        if (resolved.readiness != RideReadiness.READY) {
            val ambiguousRole =
                resolved.roles.values
                    .firstOrNull { role -> role.status == RideRoleStatus.AMBIGUOUS }
                    ?.role
            if (ambiguousRole != null) {
                throw RideEquipmentSelectionRequiredException(ambiguousRole)
            }
            throw RideEquipmentUnavailableException(resolved)
        }
        return resolved
    }

    private fun bindRideEquipment(equipment: RideEquipmentState): SelectedRideEquipment {
        val selection = equipment.selection
        val controlSource = connectedSource(selection.controlSourceId, RideRole.RESISTANCE_CONTROL)
        val trainer =
            controlSource.trainerControlConnection
                ?: throw RideSourceIncompatibleException(controlSource.id, RideRole.RESISTANCE_CONTROL)
        val powerTelemetry =
            optionalRideSource(selection.powerSourceId, RideRole.POWER) { source ->
                source.cyclingTelemetry
            }
        val cadenceTelemetry =
            optionalRideSource(selection.cadenceSourceId, RideRole.CADENCE) { source ->
                source.cyclingTelemetry
            }
        val heartRate =
            optionalRideSource(selection.heartRateSourceId, RideRole.HEART_RATE) { source ->
                source.heartRate
            }
        return SelectedRideEquipment(
            selection = selection,
            trainerControlConnection = trainer,
            controlTelemetry = controlSource.cyclingTelemetry,
            powerTelemetry = powerTelemetry,
            cadenceTelemetry = cadenceTelemetry,
            heartRate = heartRate,
        )
    }

    private fun <T> optionalRideSource(
        sourceId: String?,
        role: RideRole,
        project: (ConnectedRideSource) -> T?,
    ): T? =
        sourceId?.let { id ->
            rideSourceCatalog
                .source(id)
                ?.takeIf { source ->
                    source.descriptor.connected && source.descriptor.supports(role)
                }?.let(project)
        }

    private fun connectedSource(
        sourceId: String?,
        role: RideRole,
    ): paceline.training.ports.ConnectedRideSource {
        val id = requireNotNull(sourceId) { "No source is selected for the $role ride role" }
        val source = rideSourceCatalog.source(id) ?: throw RideSourceNotFoundException(id)
        if (!source.descriptor.connected) {
            throw RideSourceUnavailableException(id)
        }
        return source
    }

    private fun refreshRideEquipment() {
        val sources = rideSourceCatalog.sources()
        if (sources.isEmpty() && state.equipment == null) {
            return
        }
        val equipment = rideEquipment.current(sources)
        if (state.equipment != equipment) {
            state = state.copy(equipment = equipment)
        }
    }

    private fun currentControlTelemetry(): CyclingTelemetry? {
        val controlSourceId = state.equipment?.selection?.controlSourceId
        return controlSourceId
            ?.let { sourceId -> rideSourceCatalog.source(sourceId)?.cyclingTelemetry?.current() }
    }

    private fun currentExecutionTelemetry(
        controlTelemetry: CyclingTelemetry?,
        at: Instant,
    ): CyclingTelemetry? {
        observeCyclingTelemetry(controlTelemetry)
        val powerSourceId = state.equipment?.selection?.powerSourceId
        val cadenceSourceId = state.equipment?.selection?.cadenceSourceId
        val enforceSourceFreshness = state.equipment != null
        val powerTelemetry =
            powerSourceId
                ?.let { sourceId -> rideSourceCatalog.source(sourceId)?.cyclingTelemetry?.current() }
                ?.also(::observeCyclingTelemetry)
                ?.takeIf { telemetry -> !enforceSourceFreshness || isFreshTelemetry(telemetry, at) }
        val cadenceTelemetry =
            cadenceSourceId
                ?.let { sourceId -> rideSourceCatalog.source(sourceId)?.cyclingTelemetry?.current() }
                ?.also(::observeCyclingTelemetry)
                ?.takeIf { telemetry -> !enforceSourceFreshness || isFreshTelemetry(telemetry, at) }
        val freshControlTelemetry =
            controlTelemetry?.takeIf { telemetry -> !enforceSourceFreshness || isFreshTelemetry(telemetry, at) }
        val receivedAt =
            listOfNotNull(
                freshControlTelemetry?.receivedAt,
                powerTelemetry?.receivedAt,
                cadenceTelemetry?.receivedAt,
            ).maxOrNull()
                ?: return null
        return CyclingTelemetry(
            powerWatts = powerTelemetry?.powerWatts,
            cadenceRpm = cadenceTelemetry?.cadenceRpm,
            speedKph = freshControlTelemetry?.speedKph,
            distanceMeters = freshControlTelemetry?.distanceMeters,
            receivedAt = receivedAt,
        )
    }

    private fun isFreshTelemetry(
        telemetry: CyclingTelemetry,
        at: Instant,
    ): Boolean =
        isTelemetryFresh(
            telemetry.receivedAt,
            at,
            telemetryProperties.freshness,
        )

    private fun ensureTrainerConnection(now: Instant): TrainerConnectionAvailability {
        val availability =
            trainerConnection.ensure(
                now = now,
                paused = state.phase == TrainingSessionPhase.PAUSED,
            )
        updateTrainerConnectionStatus(
            status = availability.status,
            at = now,
            retryAttempt = availability.retryAttempt,
            error = availability.error,
        )
        return availability
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

    private fun onTrainerConnectionInterrupted(
        at: Instant,
        error: String?,
        initialInterruption: Boolean,
    ) {
        if (initialInterruption) {
            activitySession.interruptTrainerRecording()
            updateTrainerConnectionStatus(
                status = TrainerConnectionStatus.INTERRUPTED,
                at = at,
                retryAttempt = null,
                error = error,
            )
            refreshTelemetry(at)
        } else if (error != null) {
            state = state.copy(changedAt = at, trainerConnectionError = error)
        }
    }

    private fun onTrainerConnectionRecovered(at: Instant) {
        val sessionId = state.sessionId
        if (sessionId != null) {
            activitySession.rebindAfterTrainerRecovery(
                sessionId = sessionId,
                active = state.phase == TrainingSessionPhase.ACTIVE,
            )
        }
        state =
            state.copy(
                changedAt = at,
                trainerConnection = TrainerConnectionStatus.CONNECTED,
                trainerConnectionRetryAttempt = null,
                trainerConnectionError = null,
            )
        refreshTelemetry(at)
    }

    private fun recordActivityEvent(
        sessionId: UUID,
        event: TrainingActivityEvent,
    ) {
        activitySession.recordEvent(sessionId, event)
    }

    private fun synchronizeCurrentControl(at: Instant) {
        if (
            trainerConnection.hasPendingZeroPowerCommand() ||
            state.phase == TrainingSessionPhase.PAUSED ||
            state.ergProtection.status == ErgProtectionStatus.UNAVAILABLE
        ) {
            val commandApplied = trainerConnection.trySetTargetForExecution(0, "reconnected 0 W hold", at)
            if (commandApplied) {
                trainerConnection.markZeroPowerCommandPending(false)
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
            val commandApplied = trainerConnection.trySetFreeRideForExecution("reconnected Free Ride", at)
            state =
                state.copy(
                    changedAt = at,
                    ergRequestedTargetPowerWatts = null,
                    ergTargetPowerWatts = if (commandApplied) null else state.ergTargetPowerWatts,
                )
            return
        }

        val targetPowerWatts =
            if (state.ergProtection.status == ErgProtectionStatus.UNAVAILABLE || ergProtection.isActive(state)) {
                0
            } else {
                state.ergRequestedTargetPowerWatts ?: state.ergTargetPowerWatts ?: 0
            }
        val commandApplied =
            trainerConnection.trySetTargetForExecution(targetPowerWatts, "reconnected ERG target", at)
        state =
            state.copy(
                changedAt = at,
                ergTargetPowerWatts = if (commandApplied) targetPowerWatts else state.ergTargetPowerWatts,
            )
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
                activitySession.selectedHeartRateSourceId() != sourceId
            ) {
                return
            }
            if (!activitySession.recordHeartRate(sessionId, sourceId, telemetry)) {
                return
            }
            observeHeartRateTelemetry(telemetry)
            val currentTelemetry = state.telemetry ?: TrainingSessionTelemetry()
            state =
                state.copy(
                    changedAt = clock.instant(),
                    telemetry = currentTelemetry.copy(heartRate = heartRateProjection(clock.instant())),
                )
        }
    }

    private fun refreshTelemetry(at: Instant = clock.instant()) {
        val latest =
            when (state.phase) {
                TrainingSessionPhase.ACTIVE -> {
                    TrainingSessionTelemetry(
                        cycling = cyclingProjection(currentExecutionTelemetry(currentControlTelemetry(), at), at),
                        heartRate = heartRateProjection(at),
                    )
                }

                TrainingSessionPhase.STOPPED -> {
                    state.telemetry?.let { telemetry ->
                        TrainingSessionTelemetry(
                            cycling = reprojectCyclingTelemetry(telemetry.cycling, at),
                            heartRate = reprojectHeartRate(telemetry.heartRate, at),
                        )
                    } ?: return
                }

                else -> {
                    return
                }
            }
        if (latest != state.telemetry) {
            state = state.copy(telemetry = latest)
        }
    }

    private fun updateCyclingTelemetry(
        telemetry: CyclingTelemetry?,
        at: Instant = clock.instant(),
    ) {
        observeCyclingTelemetry(telemetry)
        val current = state.telemetry ?: TrainingSessionTelemetry()
        val projection = cyclingProjection(telemetry, at)
        if (current.cycling != projection) {
            state = state.copy(telemetry = current.copy(cycling = projection))
        }
    }

    private fun observeCyclingTelemetry(telemetry: CyclingTelemetry?) {
        val receivedAt = telemetry?.receivedAt ?: return
        if (lastCyclingReceivedAt == null || receivedAt.isAfter(requireNotNull(lastCyclingReceivedAt))) {
            lastCyclingReceivedAt = receivedAt
        }
    }

    private fun observeHeartRateTelemetry(telemetry: HeartRateTelemetry?) {
        val receivedAt = telemetry?.receivedAt ?: return
        if (lastHeartRateReceivedAt == null || receivedAt.isAfter(requireNotNull(lastHeartRateReceivedAt))) {
            lastHeartRateReceivedAt = receivedAt
        }
    }

    private fun cyclingProjection(
        telemetry: CyclingTelemetry?,
        at: Instant,
    ): TelemetryProjection<CyclingTelemetry> {
        observeCyclingTelemetry(telemetry)
        val lastReceivedAt = lastCyclingReceivedAt ?: state.telemetry?.cycling?.lastReceivedAt
        return if (telemetry != null && isFreshTelemetry(telemetry, at)) {
            TelemetryProjection.current(telemetry, telemetry.receivedAt)
        } else if (lastReceivedAt != null) {
            TelemetryProjection.interrupted(lastReceivedAt)
        } else {
            TelemetryProjection.unavailable()
        }
    }

    private fun heartRateProjection(at: Instant): TelemetryProjection<HeartRateTelemetry> {
        val telemetry = activitySession.currentHeartRate()
        observeHeartRateTelemetry(telemetry)
        val lastReceivedAt = lastHeartRateReceivedAt ?: state.telemetry?.heartRate?.lastReceivedAt
        return if (telemetry != null && isTelemetryFresh(telemetry.receivedAt, at, telemetryProperties.freshness)) {
            TelemetryProjection.current(telemetry, telemetry.receivedAt)
        } else if (lastReceivedAt != null) {
            TelemetryProjection.interrupted(lastReceivedAt)
        } else {
            TelemetryProjection.unavailable()
        }
    }

    private fun reprojectCyclingTelemetry(
        projection: TelemetryProjection<CyclingTelemetry>,
        at: Instant,
    ): TelemetryProjection<CyclingTelemetry> {
        val sample = projection.sample
        return if (sample != null && isFreshTelemetry(sample, at)) {
            TelemetryProjection.current(sample, sample.receivedAt)
        } else if (projection.lastReceivedAt != null) {
            TelemetryProjection.interrupted(projection.lastReceivedAt)
        } else {
            TelemetryProjection.unavailable()
        }
    }

    private fun reprojectHeartRate(
        projection: TelemetryProjection<HeartRateTelemetry>,
        at: Instant,
    ): TelemetryProjection<HeartRateTelemetry> {
        val sample = projection.sample
        return if (sample != null && isTelemetryFresh(sample.receivedAt, at, telemetryProperties.freshness)) {
            TelemetryProjection.current(sample, sample.receivedAt)
        } else if (projection.lastReceivedAt != null) {
            TelemetryProjection.interrupted(projection.lastReceivedAt)
        } else {
            TelemetryProjection.unavailable()
        }
    }

    private fun interruptTelemetry(telemetry: TrainingSessionTelemetry?): TrainingSessionTelemetry? =
        telemetry?.let {
            TrainingSessionTelemetry(
                cycling = interruptedOrUnavailable(it.cycling),
                heartRate = interruptedOrUnavailable(it.heartRate),
            )
        }

    private fun <T> interruptedOrUnavailable(projection: TelemetryProjection<T>): TelemetryProjection<T> =
        projection.lastReceivedAt?.let(TelemetryProjection.Companion::interrupted)
            ?: TelemetryProjection.unavailable()

    private companion object {
        const val DEFAULT_WORKOUT_POWER_TARGET_PERCENT = 100L
    }
}
