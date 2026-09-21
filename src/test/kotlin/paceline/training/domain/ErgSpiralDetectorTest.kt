package paceline.training.domain

import paceline.telemetry.domain.CyclingTelemetry
import paceline.training.config.ErgProtectionProperties
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ErgSpiralDetectorTest {
    private val start = Instant.parse("2026-09-15T12:00:00Z")
    private val properties =
        ErgProtectionProperties(
            lowCadenceDuration = Duration.ofSeconds(2),
            recoveryDuration = Duration.ofSeconds(2),
            targetChangeGracePeriod = Duration.ZERO,
        )
    private val telemetryFreshness = Duration.ofSeconds(3)

    @Test
    fun `requires sustained low cadence before bailing out and sustained recovery before restoring`() {
        // given an ERG detector with separate low-cadence and recovery dwell periods:
        val detector = ErgSpiralDetector(properties, telemetryFreshness)

        // when cadence stays below the protection threshold and then rises above the recovery threshold:
        detector.reset(start)
        assertNull(detector.evaluate(start, 300, telemetry(start, 44.0), protectionActive = false))
        assertNull(detector.evaluate(start.plusSeconds(1), 300, telemetry(start.plusSeconds(1), 44.0), protectionActive = false))
        val bailout =
            detector.evaluate(
                start.plusSeconds(2),
                300,
                telemetry(start.plusSeconds(2), 44.0),
                protectionActive = false,
            )
        assertIs<ErgProtectionDecision.BailOut>(bailout)
        assertEquals(44.0, bailout.cadenceRpm)
        assertNull(
            detector.evaluate(
                start.plusSeconds(3),
                300,
                telemetry(start.plusSeconds(3), 60.0),
                protectionActive = true,
            ),
        )
        val recovery =
            detector.evaluate(
                start.plusSeconds(5),
                300,
                telemetry(start.plusSeconds(5), 60.0),
                protectionActive = true,
            )

        // then a brief cadence dip or brief recovery does not toggle ERG protection:
        assertIs<ErgProtectionDecision.Recover>(recovery)
        assertEquals(60.0, recovery.cadenceRpm)
    }

    @Test
    fun `does not trigger for a steady low cadence workout above the bailout threshold`() {
        // given an ERG detector configured below an intentional fifty-rpm low-cadence interval:
        val detector = ErgSpiralDetector(properties, telemetryFreshness)
        detector.reset(start)

        // when the rider holds fifty rpm for longer than the low-cadence dwell:
        val decision =
            detector.evaluate(
                start.plusSeconds(5),
                300,
                telemetry(start.plusSeconds(5), 50.0),
                protectionActive = false,
            )

        // then the intentional low-cadence work remains under ERG control:
        assertNull(decision)
    }

    @Test
    fun `ignores missing and stale cadence`() {
        // given an ERG detector that only accepts recent cadence observations:
        val detector = ErgSpiralDetector(properties, telemetryFreshness)
        detector.reset(start)

        // when cadence is missing or older than the configured freshness window:
        val missing =
            detector.evaluate(
                start.plusSeconds(5),
                300,
                CyclingTelemetry(receivedAt = start.plusSeconds(5)),
                protectionActive = false,
            )
        val stale =
            detector.evaluate(
                start.plusSeconds(5),
                300,
                telemetry(start, 30.0),
                protectionActive = false,
            )

        // then the detector does not infer a spiral from unavailable data:
        assertNull(missing)
        assertNull(stale)
    }

    @Test
    fun `resets the low cadence dwell when the target context is reset`() {
        // given a low cadence observation that has not yet reached the bailout dwell:
        val detector = ErgSpiralDetector(properties, telemetryFreshness)
        detector.reset(start)
        assertNull(
            detector.evaluate(
                start.plusSeconds(1),
                300,
                telemetry(start.plusSeconds(1), 40.0),
                protectionActive = false,
            ),
        )

        // when a workout step changes and the detector is reset:
        detector.reset(start.plusSeconds(1))
        val decision =
            detector.evaluate(
                start.plusSeconds(2),
                300,
                telemetry(start.plusSeconds(2), 40.0),
                protectionActive = false,
            )

        // then the new step receives a fresh dwell period:
        assertNull(decision)
    }

    private fun telemetry(
        receivedAt: Instant,
        cadenceRpm: Double,
    ): CyclingTelemetry =
        CyclingTelemetry(
            powerWatts = 200,
            cadenceRpm = cadenceRpm,
            speedKph = 25.0,
            distanceMeters = 1_000.0,
            receivedAt = receivedAt,
        )
}
