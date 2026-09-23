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
    private val releaseResistance: (String, Instant) -> Boolean = { description, at ->
        setTarget(0, description, at)
    },
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
                    releaseResistanceOrThrow("ERG protection resistance release", now)
                } catch (exception: TrainingSessionUnavailableException) {
                    logger.warn(
                        "ERG protection resistance release failed: sessionId={} cadenceRpm={} " +
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
                    "ERG protection resistance released: sessionId={} cadenceRpm={} " +
                        "requestedTargetPowerWatts={}",
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
                    ergTargetPowerWatts = null,
                    ergProtection = ErgProtectionState.bailedOut(now, decision.cadenceRpm),
                )
            }

            is ErgProtectionDecision.Recover -> {
                val target = requestedTarget ?: return state
                logger.info(
                    "ERG spiral recovery detected; requesting target restore: sessionId={} cadenceRpm={} " +
                        "targetPowerWatts={} recoveryCadenceThresholdRpm={} recoveryDuration={}",
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
                    workoutActive = workoutActive,
                )
            }

            null -> {
                state
            }
        }
    }

    private fun attemptRecovery(
        state: TrainingSessionState,
        now: Instant,
        cadenceRpm: Double,
        targetPowerWatts: Int,
        workoutActive: Boolean,
    ): TrainingSessionState {
        logger.info(
            "ERG recovery command started: sessionId={} targetPowerWatts={} cadenceRpm={}",
            state.sessionId,
            targetPowerWatts,
            cadenceRpm,
        )
        try {
            setTargetOrThrow(targetPowerWatts, "ERG recovery", now)
        } catch (exception: TrainingSessionUnavailableException) {
            recordErgProtectionEvent(
                state = state,
                type = TrainingActivityEventType.ERG_PROTECTION_FAILED,
                occurredAt = now,
                cadenceRpm = cadenceRpm,
            )
            val nextProtection =
                if (workoutActive) {
                    ErgProtectionState.recoveryFailed(
                        changedAt = now,
                        cadenceRpm = cadenceRpm,
                        error = exception.message,
                    )
                } else {
                    ErgProtectionState.unavailable(
                        changedAt = now,
                        cadenceRpm = cadenceRpm,
                        error = exception.message,
                    )
                }
            logger.warn(
                "ERG recovery command rejected; resistance remains released: sessionId={} " +
                    "targetPowerWatts={} cadenceRpm={} status={} error={}",
                state.sessionId,
                targetPowerWatts,
                cadenceRpm,
                nextProtection.status,
                exception.message,
                exception,
            )
            return state.copy(
                changedAt = now,
                controlMode = TrainingControlMode.ERG,
                ergTargetPowerWatts = null,
                ergProtection = nextProtection,
            )
        }

        logger.info(
            "ERG protection recovered: sessionId={} targetPowerWatts={} cadenceRpm={}",
            state.sessionId,
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

    private fun releaseResistanceOrThrow(
        description: String,
        at: Instant,
    ) {
        if (!releaseResistance(description, at)) {
            throw TrainingSessionUnavailableException(
                "The active training session has no connected resistance-control device",
            )
        }
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
