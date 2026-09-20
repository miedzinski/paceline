package paceline.device.domain

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryStateMachineTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `discovery reports found devices independently from connection state`() {
        // given a discovery state machine that has started searching:
        val machine = DiscoveryStateMachine(clock)
        machine.transition(DiscoveryEvent.Begin)

        // when the discovery adapters return candidates:
        val found = machine.transition(DiscoveryEvent.DevicesFound)

        // then discovery reaches its own terminal phase:
        assertEquals(DiscoveryPhase.DISCOVERED, found.phase)
        assertEquals(now, found.changedAt)
    }

    @Test
    fun `discovery reports an unavailable result without creating a connection state`() {
        // given a discovery state machine that has started searching:
        val machine = DiscoveryStateMachine(clock)
        machine.transition(DiscoveryEvent.Begin)

        // when no matching device is returned:
        val unavailable = machine.transition(DiscoveryEvent.Unavailable("No device"))

        // then the discovery failure is retained in the discovery state:
        assertEquals(DiscoveryPhase.UNAVAILABLE, unavailable.phase)
        assertEquals(DiscoveryFailureCode.NO_DEVICE_FOUND, unavailable.failure?.code)
        assertEquals("No device", unavailable.failure?.message)
    }
}
