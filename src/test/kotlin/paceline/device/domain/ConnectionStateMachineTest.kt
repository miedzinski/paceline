package paceline.device.domain

import paceline.testsupport.kickrCore2Device
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConnectionStateMachineTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val device = kickrCore2Device()

    private fun connectingMachine(): ConnectionStateMachine =
        ConnectionStateMachine(
            clock = clock,
            initialState = ConnectionState.connecting(device, now),
        )

    @Test
    fun `successful connection reaches connected after one initialization step`() {
        // given a selected device whose connection is being initialized:
        val machine = connectingMachine()

        // when connection setup and capability initialization complete together:
        val connected = machine.transition(ConnectionEvent.ConnectionEstablished)

        // then the device is reported as connected without a transport-only state:
        assertEquals(ConnectionPhase.CONNECTED, connected.phase)
        assertEquals(device, connected.device)
        assertEquals(now, connected.changedAt)
    }

    @Test
    fun `connection failure retains the selected device`() {
        // given a device that is being connected:
        val machine = connectingMachine()

        // when connection initialization fails:
        val failed = machine.transition(ConnectionEvent.ConnectionFailed("Connection refused"))

        // then the failure retains the device that was selected:
        assertEquals(ConnectionPhase.FAILED, failed.phase)
        assertEquals(device, failed.device)
        assertEquals(ConnectionFailureCode.CONNECTION_FAILED, failed.failure?.code)
        assertEquals("Connection refused", failed.failure?.message)
    }

    @Test
    fun `connection loss is visible and the device can be retried`() {
        // given a connected device:
        val machine = connectingMachine()
        machine.transition(ConnectionEvent.ConnectionEstablished)

        // when the device connection is lost:
        val disconnected = machine.transition(ConnectionEvent.ConnectionLost("Connection closed"))

        // then the loss is visible and a later connection attempt can start:
        assertEquals(ConnectionPhase.DISCONNECTED, disconnected.phase)
        assertEquals(ConnectionFailureCode.CONNECTION_LOST, disconnected.failure?.code)
        assertEquals(
            ConnectionPhase.CONNECTING,
            machine.transition(ConnectionEvent.BeginConnection(device)).phase,
        )
    }

    @Test
    fun `connection events cannot complete an already failed attempt`() {
        // given a connection attempt that has already failed:
        val machine =
            ConnectionStateMachine(
                clock = clock,
                initialState =
                    ConnectionState(
                        phase = ConnectionPhase.FAILED,
                        device = device,
                        changedAt = now,
                    ),
            )

        // when connection completion is reported without a new attempt:
        // then the invalid transition is rejected:
        assertFailsWith<InvalidConnectionTransition> {
            machine.transition(ConnectionEvent.ConnectionEstablished)
        }
    }
}
