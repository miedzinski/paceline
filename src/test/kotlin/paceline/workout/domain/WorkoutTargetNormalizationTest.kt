package paceline.workout.domain

import paceline.profile.domain.PowerZoneCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutTargetNormalizationTest {
    @Test
    fun `resolves supported FTP-relative unit spellings into floored watts`() {
        // given a target with provider unit spelling and both fixed and ramp values:
        val target =
            WorkoutTargetSummary(
                value = null,
                start = 75.0,
                end = 60.0,
                units = "% of FTP",
                target = "POWER",
            )

        // when the domain normalizer resolves it using the workout FTP:
        val result = WorkoutTargetNormalizer.resolveFtpPower(target, ftpWatts = 200)

        // then the relative values become integer watts and metadata is retained:
        assertEquals(
            WorkoutTargetSummary(
                value = null,
                start = 150.0,
                end = 120.0,
                units = "W",
                target = "POWER",
            ),
            result,
        )
    }

    @Test
    fun `floors fractional FTP-relative watts instead of rounding up`() {
        // given a target whose FTP percentages produce fractional watt values:
        val target =
            WorkoutTargetSummary(
                start = 83.0,
                end = 96.0,
                units = "%ftp",
                target = "POWER",
            )

        // when the domain normalizer resolves it using the workout FTP:
        val result = WorkoutTargetNormalizer.resolveFtpPower(target, ftpWatts = 208)

        // then each percentage is rounded down to match the provider's workout conversion:
        assertEquals(
            WorkoutTargetSummary(
                start = 172.0,
                end = 199.0,
                units = "W",
                target = "POWER",
            ),
            result,
        )
    }

    @Test
    fun `does not resolve a relative target without a usable FTP`() {
        // given a relative target and a missing FTP:
        val target = WorkoutTargetSummary(value = 90.0, units = "percentftp")

        // when the domain normalizer attempts resolution:
        val result = WorkoutTargetNormalizer.resolveFtpPower(target, ftpWatts = null)

        // then the target remains unresolved for the execution boundary to reject:
        assertNull(result)
    }

    @Test
    fun `resolves a power zone target into the configured watt range`() {
        // given a power zone target and the athlete's configured cycling zones:
        val target = WorkoutTargetSummary(value = 2.0, units = "power_zone")
        val powerZones = PowerZoneCalculator.calculate(250, listOf(55, 75, 90), null)

        // when the backend resolves the target for trainer execution:
        val result = WorkoutTargetNormalizer.resolvePowerZone(target, powerZones)

        // then the zone becomes its absolute lower and upper watt boundaries:
        assertEquals(
            WorkoutTargetSummary(
                value = null,
                start = 138.0,
                end = 187.0,
                units = "W",
            ),
            result,
        )
    }
}
