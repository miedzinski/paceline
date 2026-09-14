package paceline.training.domain

import paceline.testsupport.FakeIndoorBikePowerControl
import paceline.testsupport.FakeTrainingDevice
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrainingSessionCoordinatorTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `cannot start a training session before a controlled device is connected`() {
        // given a session coordinator whose device connection is still ready:
        val session = TrainingSessionCoordinator(FakeTrainingDevice(), clock)

        // when a training session is started:
        // then the session remains not started because device control is unavailable:
        assertFailsWith<TrainingSessionUnavailableException> { session.start() }
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
    }

    @Test
    fun `starting a session acquires control and gates target changes`() {
        // given a connected device with an ERG power-control capability:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)

        // when the session is started and a target is changed:
        val started = session.start()
        val updated = session.setTargetPower(requireNotNull(started.sessionId), 300)

        // then control is requested once and the active session owns the target update:
        assertEquals(TrainingSessionPhase.ACTIVE, started.phase)
        assertEquals(1, powerControl.requestControlCalls)
        assertEquals(listOf(300), powerControl.targetPowers)
        assertEquals(300, updated.ergTargetPowerWatts)
    }

    @Test
    fun `target changes are rejected without an active session`() {
        // given a connected device with ERG control but no started session:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val sessionId = java.util.UUID.randomUUID()

        // when a target is submitted before starting:
        // then no command is sent to the trainer:
        assertFailsWith<TrainingSessionNotActiveException> {
            session.setTargetPower(sessionId, 300)
        }
        assertEquals(emptyList(), powerControl.targetPowers)
    }

    @Test
    fun `a failed control request does not create a session`() {
        // given a connected device that rejects the control request:
        val powerControl =
            FakeIndoorBikePowerControl().also {
                it.requestControlFailure = IllegalStateException("control denied")
            }
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)

        // when the session is started:
        // then the failure is visible and the session remains not started:
        assertFailsWith<TrainingSessionUnavailableException> { session.start() }
        assertEquals(TrainingSessionPhase.NOT_STARTED, session.current().phase)
        assertEquals(1, powerControl.requestControlCalls)
    }

    @Test
    fun `a started session remains bound to the control it acquired`() {
        // given a session that acquired control from one device:
        val firstPowerControl = FakeIndoorBikePowerControl()
        val trainingDevice = FakeTrainingDevice(firstPowerControl)
        val session = TrainingSessionCoordinator(trainingDevice, clock)
        val started = session.start()
        val secondPowerControl = FakeIndoorBikePowerControl()
        trainingDevice.powerControl = secondPowerControl

        // when the target is changed after the provider reports another device:
        session.setTargetPower(requireNotNull(started.sessionId), 300)

        // then the command stays bound to the capability that started the session:
        assertEquals(listOf(300), firstPowerControl.targetPowers)
        assertEquals(emptyList(), secondPowerControl.targetPowers)
    }

    @Test
    fun `stopping a session sends zero watts and marks it stopped`() {
        // given an active session with a previously selected ERG target:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val started = session.start()
        val sessionId = requireNotNull(started.sessionId)
        session.setTargetPower(sessionId, 300)

        // when the active session is stopped:
        val stopped = session.stop(sessionId)

        // then zero watts is sent before the session becomes a terminal stopped state:
        assertEquals(listOf(300, 0), powerControl.targetPowers)
        assertEquals(TrainingSessionPhase.STOPPED, stopped.phase)
        assertEquals(sessionId, stopped.sessionId)
        assertEquals(started.startedAt, stopped.startedAt)
        assertEquals(0, stopped.ergTargetPowerWatts)
        assertEquals(stopped, session.current())
    }

    @Test
    fun `stopped sessions reject further target changes`() {
        // given a session that has sent its zero-watt stop target:
        val powerControl = FakeIndoorBikePowerControl()
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val sessionId = requireNotNull(session.start().sessionId)
        session.stop(sessionId)

        // when a target is submitted for the stopped session:
        // then no new target is sent to the trainer:
        assertFailsWith<TrainingSessionNotActiveException> {
            session.setTargetPower(sessionId, 300)
        }
        assertEquals(listOf(0), powerControl.targetPowers)
    }

    @Test
    fun `a failed zero-watt stop target leaves the session active`() {
        // given an active session whose device rejects its stop target:
        val powerControl =
            FakeIndoorBikePowerControl().also {
                it.targetPowerFailure = IllegalStateException("trainer unavailable")
            }
        val session = TrainingSessionCoordinator(FakeTrainingDevice(powerControl), clock)
        val sessionId = requireNotNull(session.start().sessionId)

        // when the active session is stopped:
        // then the failure is visible and the session remains active for retry:
        assertFailsWith<TrainingSessionUnavailableException> {
            session.stop(sessionId)
        }
        assertEquals(TrainingSessionPhase.ACTIVE, session.current().phase)
        assertEquals(emptyList(), powerControl.targetPowers)
    }
}
