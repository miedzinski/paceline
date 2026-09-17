package paceline.workout.domain

import paceline.profile.domain.PowerZone
import kotlin.math.roundToInt

object WorkoutTargetNormalizer {
    fun isFtpRelativePower(target: WorkoutTargetSummary): Boolean = target.units.normalizedPowerUnits() in FTP_PERCENT_UNITS

    fun isPowerZone(target: WorkoutTargetSummary): Boolean = target.units.normalizedPowerUnits() in POWER_ZONE_UNITS

    fun resolveFtpPower(
        target: WorkoutTargetSummary,
        ftpWatts: Int?,
    ): WorkoutTargetSummary? {
        if (!isFtpRelativePower(target)) {
            return null
        }

        val validFtpWatts = ftpWatts?.takeIf { it > 0 } ?: return null
        if (target.value == null && target.start == null && target.end == null) {
            return null
        }

        fun resolve(percent: Double?): Double? =
            percent
                ?.takeIf(Double::isFinite)
                ?.let { ((it / 100.0) * validFtpWatts).roundToInt().toDouble() }

        return target.copy(
            value = resolve(target.value),
            start = resolve(target.start),
            end = resolve(target.end),
            units = "W",
        )
    }

    fun resolvePowerZone(
        target: WorkoutTargetSummary,
        powerZones: List<PowerZone>?,
    ): WorkoutTargetSummary? {
        if (!isPowerZone(target)) {
            return null
        }

        val startZoneNumber = zoneNumber(target.start ?: target.value ?: target.end) ?: return null
        val endZoneNumber = zoneNumber(target.end ?: target.value ?: target.start) ?: return null
        val firstZoneNumber = minOf(startZoneNumber, endZoneNumber)
        val lastZoneNumber = maxOf(startZoneNumber, endZoneNumber)
        val firstZone = powerZones.orEmpty().find { it.number == firstZoneNumber } ?: return null
        val lastZone = powerZones.orEmpty().find { it.number == lastZoneNumber } ?: return null
        val highWatts = lastZone.maxWatts ?: return null

        return target.copy(
            value = null,
            start = firstZone.minWatts.toDouble(),
            end = highWatts.toDouble(),
            units = "W",
        )
    }

    private fun zoneNumber(value: Double?): Int? {
        if (value == null || !value.isFinite()) {
            return null
        }

        return value.roundToInt().takeIf { it > 0 && it.toDouble() == value }
    }

    private val FTP_PERCENT_UNITS = setOf("%ftp", "%offtp", "percentftp")
    private val POWER_ZONE_UNITS = setOf("power_zone", "powerzone")
}

private fun String?.normalizedPowerUnits(): String? =
    this
        ?.trim()
        ?.lowercase()
        ?.replace(" ", "")
