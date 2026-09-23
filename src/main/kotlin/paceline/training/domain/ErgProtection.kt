package paceline.training.domain

import paceline.telemetry.domain.CyclingTelemetry
import paceline.telemetry.domain.isTelemetryFresh
import paceline.training.config.ErgProtectionProperties
import java.time.Duration
import java.time.Instant

enum class ErgProtectionStatus {
    INACTIVE,
    BAILED_OUT,
    RECOVERY_FAILED,
    UNAVAILABLE,
}

data class ErgProtectionState(
    val status: ErgProtectionStatus,
    val changedAt: Instant? = null,
    val cadenceRpm: Double? = null,
    val error: String? = null,
) {
    companion object {
        fun inactive(): ErgProtectionState = ErgProtectionState(status = ErgProtectionStatus.INACTIVE)

        fun bailedOut(
            changedAt: Instant,
            cadenceRpm: Double?,
        ): ErgProtectionState =
            ErgProtectionState(
                status = ErgProtectionStatus.BAILED_OUT,
                changedAt = changedAt,
                cadenceRpm = cadenceRpm,
            )

        fun recoveryFailed(
            changedAt: Instant,
            cadenceRpm: Double,
            error: String?,
        ): ErgProtectionState =
            ErgProtectionState(
                status = ErgProtectionStatus.RECOVERY_FAILED,
                changedAt = changedAt,
                cadenceRpm = cadenceRpm,
                error = error,
            )

        fun unavailable(
            changedAt: Instant,
            cadenceRpm: Double?,
            error: String?,
        ): ErgProtectionState =
            ErgProtectionState(
                status = ErgProtectionStatus.UNAVAILABLE,
                changedAt = changedAt,
                cadenceRpm = cadenceRpm,
                error = error,
            )
    }
}

sealed interface ErgProtectionDecision {
    data class BailOut(
        val cadenceRpm: Double,
    ) : ErgProtectionDecision

    data class Recover(
        val cadenceRpm: Double,
    ) : ErgProtectionDecision
}

class ErgSpiralDetector(
    private val properties: ErgProtectionProperties,
    private val telemetryFreshness: Duration,
) {
    private var lowCadenceSince: Instant? = null
    private var recoveryCadenceSince: Instant? = null
    private var graceUntil: Instant? = null

    fun reset(at: Instant) {
        lowCadenceSince = null
        recoveryCadenceSince = null
        graceUntil = at.plus(properties.targetChangeGracePeriod)
    }

    fun evaluate(
        now: Instant,
        requestedTargetPowerWatts: Int?,
        telemetry: CyclingTelemetry?,
        protectionActive: Boolean,
    ): ErgProtectionDecision? {
        if (!properties.enabled || requestedTargetPowerWatts == null || requestedTargetPowerWatts <= 0) {
            clearEvaluation()
            return null
        }

        val cadence =
            freshCadence(now, telemetry) ?: run {
                lowCadenceSince = null
                recoveryCadenceSince = null
                return null
            }

        if (graceUntil?.let(now::isBefore) == true) {
            lowCadenceSince = null
            recoveryCadenceSince = null
            return null
        }

        return if (protectionActive) {
            evaluateRecovery(now, cadence)
        } else {
            evaluateBailout(now, cadence)
        }
    }

    fun freshRecoveryCadence(
        now: Instant,
        telemetry: CyclingTelemetry?,
    ): Double? = freshCadence(now, telemetry)?.takeIf { cadence -> cadence >= properties.recoveryCadenceRpm }

    private fun evaluateBailout(
        now: Instant,
        cadence: Double,
    ): ErgProtectionDecision? {
        recoveryCadenceSince = null
        if (cadence >= properties.lowCadenceRpm) {
            lowCadenceSince = null
            return null
        }

        val lowSince = lowCadenceSince ?: now.also { lowCadenceSince = it }
        return if (elapsedAtLeast(lowSince, now, properties.lowCadenceDuration)) {
            lowCadenceSince = null
            ErgProtectionDecision.BailOut(cadence)
        } else {
            null
        }
    }

    private fun evaluateRecovery(
        now: Instant,
        cadence: Double,
    ): ErgProtectionDecision? {
        lowCadenceSince = null
        if (cadence < properties.recoveryCadenceRpm) {
            recoveryCadenceSince = null
            return null
        }

        val recoverySince = recoveryCadenceSince ?: now.also { recoveryCadenceSince = it }
        return if (elapsedAtLeast(recoverySince, now, properties.recoveryDuration)) {
            recoveryCadenceSince = null
            ErgProtectionDecision.Recover(cadence)
        } else {
            null
        }
    }

    private fun freshCadence(
        now: Instant,
        telemetry: CyclingTelemetry?,
    ): Double? {
        if (telemetry == null || telemetry.receivedAt.isAfter(now)) {
            return null
        }
        if (!isTelemetryFresh(telemetry.receivedAt, now, telemetryFreshness)) {
            return null
        }
        return telemetry.cadenceRpm?.takeIf { cadence -> cadence.isFinite() && cadence >= 0.0 }
    }

    private fun clearEvaluation() {
        lowCadenceSince = null
        recoveryCadenceSince = null
    }

    private fun elapsedAtLeast(
        startedAt: Instant,
        now: Instant,
        duration: Duration,
    ): Boolean = !now.isBefore(startedAt.plus(duration))
}
