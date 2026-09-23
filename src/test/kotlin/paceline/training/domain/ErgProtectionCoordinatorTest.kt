package paceline.training.domain

import paceline.telemetry.domain.CyclingTelemetry
import paceline.training.config.ErgProtectionProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ErgProtectionCoordinatorTest {
    private val start = Instant.parse("2026-09-16T12:00:00Z")

    @Test
    fun `coordinates bailout and recovery commands as session state transitions`() {
        // given an ERG session and a protection policy with one-second dwell periods:
        val targetCommands = mutableListOf<Int>()
        var resistanceReleaseCalls = 0
        val events = mutableListOf<TrainingActivityEvent>()
        val coordinator =
            ErgProtectionCoordinator(
                properties =
                    ErgProtectionProperties(
                        lowCadenceDuration = Duration.ofSeconds(1),
                        recoveryDuration = Duration.ofSeconds(1),
                        targetChangeGracePeriod = Duration.ZERO,
                    ),
                telemetryFreshness = Duration.ofSeconds(5),
                setTarget = { target, _, _ ->
                    targetCommands += target
                    true
                },
                recordActivityEvent = { _, event -> events += event },
                releaseResistance = { _, _ ->
                    resistanceReleaseCalls += 1
                    true
                },
            )
        var state =
            TrainingSessionState
                .active(UUID.randomUUID(), start)
                .copy(
                    controlMode = TrainingControlMode.ERG,
                    ergRequestedTargetPowerWatts = 300,
                    ergTargetPowerWatts = 300,
                )
        coordinator.reset(start)

        // when cadence remains low and then remains high for the recovery dwell:
        state = coordinator.evaluate(state, start, telemetry(start, 40.0), workoutActive = true)
        val bailedOut =
            coordinator.evaluate(
                state,
                start.plusSeconds(1),
                telemetry(start.plusSeconds(1), 40.0),
                workoutActive = true,
            )
        state =
            coordinator.evaluate(
                bailedOut,
                start.plusSeconds(2),
                telemetry(start.plusSeconds(2), 70.0),
                workoutActive = true,
            )
        state =
            coordinator.evaluate(
                state,
                start.plusSeconds(3),
                telemetry(start.plusSeconds(3), 70.0),
                workoutActive = true,
            )

        // then protection releases resistance without sending a zero-watt target:
        assertEquals(listOf(300), targetCommands)
        assertEquals(1, resistanceReleaseCalls)
        assertEquals(null, bailedOut.ergTargetPowerWatts)
        assertEquals(ErgProtectionStatus.INACTIVE, state.ergProtection.status)
        assertEquals(TrainingControlMode.ERG, state.controlMode)
        assertEquals(
            listOf(
                TrainingActivityEventType.ERG_PROTECTION_STARTED,
                TrainingActivityEventType.ERG_PROTECTION_ENDED,
            ),
            events.map { it.type },
        )
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
