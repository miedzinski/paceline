package paceline.profile.domain

import kotlin.math.floor

object PowerZoneCalculator {
    fun calculate(
        ftpWatts: Int?,
        upperBoundsPercent: List<Int>?,
        names: List<String>?,
    ): List<PowerZone> {
        val validFtpWatts = ftpWatts?.takeIf { it > 0 } ?: return emptyList()
        val upperBounds = normalizedUpperBounds(upperBoundsPercent)

        return upperBounds.mapIndexed { index, maxPercent ->
            val minPercent = upperBounds.getOrNull(index - 1) ?: 0
            val minWatts = if (index == 0) 0 else wattsAt(validFtpWatts, minPercent) + 1
            val maxWatts = maxPercent?.let { wattsAt(validFtpWatts, it) }

            PowerZone(
                number = index + 1,
                name =
                    names?.getOrNull(index)?.takeIf(String::isNotBlank)
                        ?: DEFAULT_ZONE_NAMES.getOrElse(index) { "Zone ${index + 1}" },
                minPercent = minPercent,
                maxPercent = maxPercent,
                minWatts = minWatts,
                maxWatts = maxWatts,
            )
        }
    }

    private fun normalizedUpperBounds(candidate: List<Int>?): List<Int?> {
        val finiteBounds = candidate.orEmpty().takeWhile { it < UNBOUNDED_BOUNDARY_PERCENT }
        val valid =
            finiteBounds.isNotEmpty() &&
                finiteBounds.all { it > 0 } &&
                finiteBounds.zipWithNext().all { (current, next) -> next > current }

        return if (valid) {
            finiteBounds + listOf<Int?>(null)
        } else {
            DEFAULT_UPPER_BOUNDS_PERCENT.map { it } + listOf<Int?>(null)
        }
    }

    private fun wattsAt(
        ftpWatts: Int,
        percent: Int,
    ): Int = floor(ftpWatts * percent / PERCENT_SCALE).toInt()

    private const val PERCENT_SCALE = 100.0
    private const val UNBOUNDED_BOUNDARY_PERCENT = 999

    private val DEFAULT_UPPER_BOUNDS_PERCENT = listOf(55, 75, 90, 105, 120, 150)
    private val DEFAULT_ZONE_NAMES =
        listOf(
            "Active Recovery",
            "Endurance",
            "Tempo",
            "Threshold",
            "VO2 Max",
            "Anaerobic",
            "Neuromuscular",
        )
}
