package paceline.training.domain

import org.slf4j.LoggerFactory
import paceline.telemetry.domain.CyclingTelemetry
import paceline.training.config.ErgProtectionProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ErgProtectionCoordinator(
    private val properties: ErgProtectionProperties,
    private val telemetryFreshness: Duration,
    private val setTarget: (Int, String, Instant) -> Boolean,
    private val recordActivityEvent: (UUID, TrainingActivityEvent) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val detector = ErgSpiralDetector(properties, telemetryFreshness)

    fun reset(at: Instant) {
        detector.reset(at)
    }

    fun isActive(state: TrainingSessionState): Boolean =
        state.ergProtection.status in
            setOf(
                ErgProtectionStatus.BAILED_OUT,
                ErgProtectionStatus.RECOVERY_RETRYING,
                ErgProtectionStatus.RECOVERY_FAILED,
            )

    fun evaluate(
        state: TrainingSessionState,
        now: Instant,
        telemetry: CyclingTelemetry?,
        workoutActive: Boolean,
    ): TrainingSessionState {
        when (state.ergProtection.status) {
            ErgProtectionStatus.UNAVAILABLE,
            ErgProtectionStatus.RECOVERY_FAILED,
            -> {
                return state
            }

            ErgProtectionStatus.RECOVERY_RETRYING -> {
                return retryRecovery(state, now, telemetry, workoutActive)
            }

            ErgProtectionStatus.INACTIVE,
            ErgProtectionStatus.BAILED_OUT,
            -> {
                Unit
            }
        }

        val requestedTarget = state.ergRequestedTargetPowerWatts
        val protectionActive = isActive(state)
        return when (
            val decision =
                detector.evaluate(
                    now = now,
                    requestedTargetPowerWatts = requestedTarget,
                    telemetry = telemetry,
                    protectionActive = protectionActive,
                )
        ) {
            is ErgProtectionDecision.BailOut -> {
                if (protectionActive) {
                    return state
                }
                logger.info(
                    "ERG spiral detected; requesting bailout: sessionId={} cadenceRpm={} " +
                        "requestedTargetPowerWatts={} lowCadenceThresholdRpm={} lowCadenceDuration={}",
                    state.sessionId,
                    decision.cadenceRpm,
                    requestedTarget,
                    properties.lowCadenceRpm,
                    properties.lowCadenceDuration,
                )
                try {
                    setTargetOrThrow(0, "ERG protection", now)
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
                        state = state,
                        type = TrainingActivityEventType.ERG_PROTECTION_FAILED,
                        occurredAt = now,
                        cadenceRpm = decision.cadenceRpm,
                    )
                    return state.copy(
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
                }

                logger.info(
                    "ERG protection bailout applied: sessionId={} cadenceRpm={} " +
                        "requestedTargetPowerWatts={} appliedTargetPowerWatts=0",
                    state.sessionId,
                    decision.cadenceRpm,
                    requestedTarget,
                )
                recordErgProtectionEvent(
                    state = state,
                    type = TrainingActivityEventType.ERG_PROTECTION_STARTED,
                    occurredAt = now,
                    cadenceRpm = decision.cadenceRpm,
                )
                state.copy(
                    changedAt = now,
                    controlMode = TrainingControlMode.ERG,
                    ergTargetPowerWatts = 0,
                    ergProtection = ErgProtectionState.bailedOut(now, decision.cadenceRpm),
                )
            }

            is ErgProtectionDecision.Recover -> {
                val target = requestedTarget ?: return state
                logger.info(
                    "ERG spiral recovery detected; requesting target restore: sessionId={} cadenceRpm={} " +
                        "targetPowerWatts={} recoveryCadenceThresholdRpm={} recoveryDuration={} attempt=1",
                    state.sessionId,
                    decision.cadenceRpm,
                    target,
                    properties.recoveryCadenceRpm,
                    properties.recoveryDuration,
                )
                attemptRecovery(
                    state = state,
                    now = now,
                    cadenceRpm = decision.cadenceRpm,
                    targetPowerWatts = target,
                    attempt = 1,
                    workoutActive = workoutActive,
                )
            }

            null -> {
                state
            }
        }
    }

    private fun retryRecovery(
        state: TrainingSessionState,
        now: Instant,
        telemetry: CyclingTelemetry?,
        workoutActive: Boolean,
    ): TrainingSessionState {
        val protection = state.ergProtection
        val cadence = detector.freshRecoveryCadence(now, telemetry)
        if (cadence == null) {
            logger.info(
                "ERG recovery retry cancelled; cadence is no longer fresh and high enough: " +
                    "sessionId={} retryAttempt={} observedCadenceRpm={} recoveryCadenceThresholdRpm={} " +
                    "telemetryReceivedAt={}",
                state.sessionId,
                protection.retryAttempt,
                telemetry?.cadenceRpm,
                properties.recoveryCadenceRpm,
                telemetry?.receivedAt,
            )
            return state.copy(
                changedAt = now,
                ergProtection =
                    ErgProtectionState.bailedOut(
                        changedAt = now,
                        cadenceRpm = protection.cadenceRpm,
                    ),
            )
        }

        val nextRetryAt = protection.nextRetryAt ?: return state
        if (now.isBefore(nextRetryAt)) {
            return state
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
            detector.reset(now)
            return state.copy(
                changedAt = now,
                controlMode = TrainingControlMode.ERG,
                ergTargetPowerWatts = 0,
                ergProtection = ErgProtectionState.inactive(),
            )
        }

        return attemptRecovery(
            state = state,
            now = now,
            cadenceRpm = cadence,
            targetPowerWatts = target,
            attempt = (protection.retryAttempt ?: 0) + 1,
            workoutActive = workoutActive,
        )
    }

    private fun attemptRecovery(
        state: TrainingSessionState,
        now: Instant,
        cadenceRpm: Double,
        targetPowerWatts: Int,
        attempt: Int,
        workoutActive: Boolean,
    ): TrainingSessionState {
        logger.info(
            "ERG recovery command attempt: sessionId={} attempt={} targetPowerWatts={} cadenceRpm={} " +
                "maxAttempts={}",
            state.sessionId,
            attempt,
            targetPowerWatts,
            cadenceRpm,
            properties.recoveryRetryMaxAttempts,
        )
        try {
            setTargetOrThrow(targetPowerWatts, "ERG recovery", now)
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
                state = state,
                type = TrainingActivityEventType.ERG_PROTECTION_FAILED,
                occurredAt = now,
                cadenceRpm = cadenceRpm,
            )
            val nextProtection =
                if (workoutActive && attempt < properties.recoveryRetryMaxAttempts) {
                    ErgProtectionState.recoveryRetrying(
                        changedAt = now,
                        cadenceRpm = cadenceRpm,
                        retryAttempt = attempt,
                        nextRetryAt = now.plus(recoveryRetryDelay(attempt)),
                        error = exception.message,
                    )
                } else if (workoutActive) {
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
            return state.copy(
                changedAt = now,
                controlMode = TrainingControlMode.ERG,
                ergTargetPowerWatts = if (workoutActive) 0 else null,
                ergProtection = nextProtection,
            )
        }

        logger.info(
            "ERG protection recovered: sessionId={} attempt={} targetPowerWatts={} cadenceRpm={}",
            state.sessionId,
            attempt,
            targetPowerWatts,
            cadenceRpm,
        )
        recordErgProtectionEvent(
            state = state,
            type = TrainingActivityEventType.ERG_PROTECTION_ENDED,
            occurredAt = now,
            cadenceRpm = cadenceRpm,
        )
        return state.copy(
            changedAt = now,
            controlMode = TrainingControlMode.ERG,
            ergTargetPowerWatts = targetPowerWatts,
            ergProtection = ErgProtectionState.inactive(),
        )
    }

    private fun setTargetOrThrow(
        targetPowerWatts: Int,
        description: String,
        at: Instant,
    ) {
        if (!setTarget(targetPowerWatts, description, at)) {
            throw TrainingSessionUnavailableException(
                "The active training session has no connected ERG power-control device",
            )
        }
    }

    private fun recoveryRetryDelay(attempt: Int): Duration {
        var delay = properties.recoveryRetryInitialDelay
        repeat((attempt - 1).coerceAtLeast(0)) {
            val doubled = delay.multipliedBy(2)
            delay =
                if (doubled.compareTo(properties.recoveryRetryMaxDelay) > 0) {
                    properties.recoveryRetryMaxDelay
                } else {
                    doubled
                }
        }
        return delay
    }

    private fun recordErgProtectionEvent(
        state: TrainingSessionState,
        type: TrainingActivityEventType,
        occurredAt: Instant,
        cadenceRpm: Double?,
    ) {
        val sessionId = state.sessionId ?: return
        recordActivityEvent(
            sessionId,
            TrainingActivityEvent(
                type = type,
                occurredAt = occurredAt,
                cadenceRpm = cadenceRpm,
            ),
        )
    }
}
