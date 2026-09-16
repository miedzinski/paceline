package paceline.workout.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutTargetNormalizationTest {
    @Test
    fun `resolves supported FTP-relative unit spellings into rounded watts`() {
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

        // then the relative values become rounded watts and metadata is retained:
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
    fun `does not resolve a relative target without a usable FTP`() {
        // given a relative target and a missing FTP:
        val target = WorkoutTargetSummary(value = 90.0, units = "percentftp")

        // when the domain normalizer attempts resolution:
        val result = WorkoutTargetNormalizer.resolveFtpPower(target, ftpWatts = null)

        // then the target remains unresolved for the execution boundary to reject:
        assertNull(result)
    }
}
