package paceline.profile.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PowerZoneCalculatorTest {
    @Test
    fun `converts provider percentage boundaries into inclusive watt ranges`() {
        // given Intervals.icu provides the configured cycling boundaries and an FTP of 200 watts:
        val result =
            PowerZoneCalculator.calculate(
                ftpWatts = 200,
                upperBoundsPercent = listOf(55, 75, 90, 105, 120, 150, 999),
                names = listOf("Active Recovery", "Endurance", "Tempo", "Threshold", "VO2 Max", "Anaerobic", "Neuromuscular"),
            )

        // when the backend builds the app-facing power ranges:
        val ranges = result.map { it.minWatts to it.maxWatts }

        // then integer watt values are partitioned without gaps or overlaps and the last zone is open-ended:
        assertEquals(
            listOf(
                0 to 110,
                111 to 150,
                151 to 180,
                181 to 210,
                211 to 240,
                241 to 300,
                301 to null,
            ),
            ranges,
        )
        assertEquals("Neuromuscular", result.last().name)
    }

    @Test
    fun `uses standard intervals zones when the provider omits boundaries`() {
        // given a cycling FTP with no provider-supplied power-zone boundaries:
        val result = PowerZoneCalculator.calculate(200, null, null)

        // when the backend prepares profile data for the app:
        val ranges = result.map { it.name to (it.minWatts to it.maxWatts) }

        // then the standard seven-zone percentage scheme still provides usable watt ranges:
        assertEquals("Active Recovery" to (0 to 110), ranges.first())
        assertEquals("Neuromuscular" to (301 to null), ranges.last())
        assertEquals(7, ranges.size)
    }

    @Test
    fun `does not invent power ranges when no usable FTP is configured`() {
        // given Intervals.icu has no positive cycling FTP:
        val result = PowerZoneCalculator.calculate(null, listOf(55, 75, 90), null)

        // when the profile is mapped:
        // then the backend leaves power ranges empty rather than fabricating watt values:
        assertEquals(emptyList(), result)
    }
}
