package paceline.workout.domain

import kotlin.math.roundToInt

object WorkoutTargetNormalizer {
    fun isFtpRelativePower(target: WorkoutTargetSummary): Boolean = target.units.normalizedPowerUnits() in FTP_PERCENT_UNITS

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

    private val FTP_PERCENT_UNITS = setOf("%ftp", "%offtp", "percentftp")
}

private fun String?.normalizedPowerUnits(): String? =
    this
        ?.trim()
        ?.lowercase()
        ?.replace(" ", "")
