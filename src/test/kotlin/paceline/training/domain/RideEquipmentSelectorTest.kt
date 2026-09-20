package paceline.training.domain

import paceline.device.domain.ConnectionPhase
import paceline.testsupport.rideSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RideEquipmentSelectorTest {
    private val trainerCapabilities =
        setOf(
            RideSourceCapability.RESISTANCE_CONTROL,
            RideSourceCapability.POWER,
            RideSourceCapability.CADENCE,
        )

    @Test
    fun `one compatible trainer fills every compatible role without extra selection`() {
        // given one connected trainer that exposes control, power, and cadence:
        val selector = RideEquipmentSelector()
        val sources = listOf(rideSource("trainer-a", trainerCapabilities))

        // when the ride equipment state is first resolved:
        val state = selector.current(sources)

        // then the trainer is selected for every compatible role and the ride is ready:
        assertEquals(
            RideEquipmentSelection(
                controlSourceId = "trainer-a",
                powerSourceId = "trainer-a",
                cadenceSourceId = "trainer-a",
            ),
            state.selection,
        )
        assertEquals(RideRoleStatus.SELECTED, state.control.status)
        assertEquals(RideRoleStatus.SELECTED, state.power.status)
        assertEquals(RideRoleStatus.SELECTED, state.cadence.status)
        assertEquals(RideRoleStatus.OPTIONAL, state.heartRate.status)
        assertEquals(RideReadiness.READY, state.readiness)
    }

    @Test
    fun `control-only source is ready without power or cadence`() {
        // given one connected FTMS source that exposes only resistance control:
        val selector = RideEquipmentSelector()
        val state =
            selector.current(
                listOf(
                    rideSource(
                        "ftms-control",
                        setOf(RideSourceCapability.RESISTANCE_CONTROL),
                    ),
                ),
            )

        // when the ride equipment state is resolved:
        // then control is selected while power and cadence remain unassigned:
        assertEquals("ftms-control", state.selection.controlSourceId)
        assertNull(state.selection.powerSourceId)
        assertNull(state.selection.cadenceSourceId)
        assertEquals(RideRoleStatus.SELECTED, state.control.status)
        assertEquals(RideRoleStatus.OPTIONAL, state.power.status)
        assertEquals(RideRoleStatus.OPTIONAL, state.cadence.status)
        assertEquals(RideReadiness.READY, state.readiness)
        assertEquals(emptyList(), state.readinessReasons)
    }

    @Test
    fun `multiple compatible trainers require explicit choices until a source is selected`() {
        // given two connected trainers that can provide all cycling roles:
        val selector = RideEquipmentSelector()
        val sources =
            listOf(
                rideSource("trainer-a", trainerCapabilities),
                rideSource("trainer-b", trainerCapabilities),
            )

        // when the ride is resolved before the rider chooses a trainer:
        val ambiguous = selector.current(sources)

        // then the ambiguous control role remains unassigned and blocks readiness:
        assertNull(ambiguous.selection.controlSourceId)
        assertNull(ambiguous.selection.powerSourceId)
        assertNull(ambiguous.selection.cadenceSourceId)
        assertEquals(RideRoleStatus.AMBIGUOUS, ambiguous.control.status)
        assertEquals(RideRoleStatus.AMBIGUOUS, ambiguous.power.status)
        assertEquals(RideRoleStatus.AMBIGUOUS, ambiguous.cadence.status)
        assertEquals(RideReadiness.SELECTION_REQUIRED, ambiguous.readiness)

        // when the rider explicitly chooses one trainer for control:
        val selected = selector.select(RideRole.RESISTANCE_CONTROL, "trainer-b", sources)

        // then that trainer is preferred for the still-unassigned power and cadence roles:
        assertEquals("trainer-b", selected.selection.controlSourceId)
        assertEquals("trainer-b", selected.selection.powerSourceId)
        assertEquals("trainer-b", selected.selection.cadenceSourceId)
        assertEquals(RideReadiness.READY, selected.readiness)

        // when the rider explicitly splits power to the other trainer:
        val split = selector.select(RideRole.POWER, "trainer-a", sources)

        // then the independent power choice is retained without changing control or cadence:
        assertEquals("trainer-b", split.selection.controlSourceId)
        assertEquals("trainer-a", split.selection.powerSourceId)
        assertEquals("trainer-b", split.selection.cadenceSourceId)
        assertEquals(RideReadiness.READY, split.readiness)
    }

    @Test
    fun `power and cadence can resolve from independent sources`() {
        // given one controllable power source and a separate cadence source:
        val selector = RideEquipmentSelector()
        val sources =
            listOf(
                rideSource(
                    "trainer-a",
                    setOf(RideSourceCapability.RESISTANCE_CONTROL, RideSourceCapability.POWER),
                ),
                rideSource("cadence-sensor", setOf(RideSourceCapability.CADENCE)),
            )

        // when the ride equipment state is resolved:
        val state = selector.current(sources)

        // then control and power stay on the trainer while cadence uses the independent source:
        assertEquals("trainer-a", state.selection.controlSourceId)
        assertEquals("trainer-a", state.selection.powerSourceId)
        assertEquals("cadence-sensor", state.selection.cadenceSourceId)
        assertEquals(RideReadiness.READY, state.readiness)
    }

    @Test
    fun `a new connection never replaces existing role selections`() {
        // given a single trainer whose automatic assignments have already been persisted:
        val selector = RideEquipmentSelector()
        val firstSource = rideSource("trainer-a", trainerCapabilities)
        val initial = selector.current(listOf(firstSource))

        // when a second compatible trainer connects:
        val afterConnection = selector.current(listOf(firstSource, rideSource("trainer-b", trainerCapabilities)))

        // then every existing source ID remains sticky and the new source is not selected silently:
        assertEquals(initial.selection, afterConnection.selection)
        assertEquals("trainer-a", afterConnection.selection.controlSourceId)
        assertEquals("trainer-a", afterConnection.selection.powerSourceId)
        assertEquals("trainer-a", afterConnection.selection.cadenceSourceId)
        assertEquals(RideReadiness.READY, afterConnection.readiness)
    }

    @Test
    fun `an explicit clear keeps a role unassigned until it is selected again`() {
        // given a ride whose control, power, and cadence roles have resolved:
        val selector = RideEquipmentSelector()
        val firstSource = rideSource("trainer-a", trainerCapabilities)
        val sources = listOf(firstSource, rideSource("trainer-b", trainerCapabilities))
        selector.current(listOf(firstSource))
        selector.current(sources)

        // when the rider explicitly clears the required control role:
        val clearedControl = selector.clear(RideRole.RESISTANCE_CONTROL, sources)

        // then control becomes unassigned and the ride requires a new control choice:
        assertNull(clearedControl.selection.controlSourceId)
        assertEquals(RideRoleStatus.AMBIGUOUS, clearedControl.control.status)
        assertEquals(RideReadiness.SELECTION_REQUIRED, clearedControl.readiness)

        // when the rider selects the control source again:
        val selectedControl = selector.select(RideRole.RESISTANCE_CONTROL, "trainer-a", sources)

        // then the required role is usable again:
        assertEquals("trainer-a", selectedControl.selection.controlSourceId)
        assertEquals(RideRoleStatus.SELECTED, selectedControl.control.status)
        assertEquals(RideReadiness.READY, selectedControl.readiness)

        // when the rider assigns power to a different source:
        selector.select(RideRole.POWER, "trainer-b", sources)

        // and explicitly clears the independent power role:
        val cleared = selector.clear(RideRole.POWER, sources)

        // then automatic preference does not silently assign it again, without blocking on optional telemetry:
        assertNull(cleared.selection.powerSourceId)
        assertEquals(RideRoleStatus.AMBIGUOUS, cleared.power.status)
        assertEquals(RideReadiness.READY, cleared.readiness)

        // when the rider explicitly selects a new power source:
        val selected = selector.select(RideRole.POWER, "trainer-a", sources)

        // then that role becomes usable again:
        assertEquals("trainer-a", selected.selection.powerSourceId)
        assertEquals(RideRoleStatus.SELECTED, selected.power.status)
        assertEquals(RideReadiness.READY, selected.readiness)

        // when the only compatible required source is explicitly cleared:
        val singleSourceSelector = RideEquipmentSelector()
        val singleSource = rideSource("single-trainer", trainerCapabilities)
        singleSourceSelector.current(listOf(singleSource))
        val singleSourceCleared = singleSourceSelector.clear(RideRole.POWER, listOf(singleSource))

        // then the unassigned role remains without blocking the ride:
        assertEquals(RideRoleStatus.AMBIGUOUS, singleSourceCleared.power.status)
        assertEquals(RideReadiness.READY, singleSourceCleared.readiness)
    }

    @Test
    fun `an assigned source becoming unavailable remains assigned without fallback`() {
        // given a ride whose roles are assigned to one trainer:
        val selector = RideEquipmentSelector()
        selector.current(listOf(rideSource("trainer-a", trainerCapabilities)))

        // when that trainer disconnects while another compatible trainer remains connected:
        val state =
            selector.current(
                listOf(
                    rideSource("trainer-a", trainerCapabilities, state = ConnectionPhase.DISCONNECTED),
                    rideSource("trainer-b", trainerCapabilities),
                ),
            )

        // then the assigned IDs remain visible as unavailable and no role falls back to trainer-b:
        assertEquals("trainer-a", state.selection.controlSourceId)
        assertEquals("trainer-a", state.selection.powerSourceId)
        assertEquals("trainer-a", state.selection.cadenceSourceId)
        assertEquals(RideRoleStatus.UNAVAILABLE, state.control.status)
        assertEquals(RideRoleStatus.UNAVAILABLE, state.power.status)
        assertEquals(RideRoleStatus.UNAVAILABLE, state.cadence.status)
        assertEquals(listOf("trainer-b"), state.control.compatibleSourceIds)
        assertEquals(RideReadiness.UNAVAILABLE, state.readiness)
    }

    @Test
    fun `heart rate does not block readiness with zero one or multiple sources`() {
        // given a connected trainer that supplies control with no heart rate:
        val trainer = rideSource("trainer-a", trainerCapabilities)
        val selectorWithoutHeartRate = RideEquipmentSelector()

        // when no heart-rate source is connected:
        val withoutHeartRate = selectorWithoutHeartRate.current(listOf(trainer))

        // then the optional role is unselected without blocking the ride:
        assertEquals(RideRoleStatus.OPTIONAL, withoutHeartRate.heartRate.status)
        assertNull(withoutHeartRate.selection.heartRateSourceId)
        assertEquals(RideReadiness.READY, withoutHeartRate.readiness)

        // when exactly one heart-rate source is connected:
        val oneHeartRate =
            selectorWithoutHeartRate.current(
                listOf(trainer, rideSource("strap-a", setOf(RideSourceCapability.HEART_RATE))),
            )

        // then it is selected automatically without changing readiness:
        assertEquals("strap-a", oneHeartRate.selection.heartRateSourceId)
        assertEquals(RideRoleStatus.SELECTED, oneHeartRate.heartRate.status)
        assertEquals(RideReadiness.READY, oneHeartRate.readiness)

        // when the rider explicitly clears that heart-rate role:
        val clearedHeartRate =
            selectorWithoutHeartRate.clear(
                RideRole.HEART_RATE,
                listOf(trainer, rideSource("strap-a", setOf(RideSourceCapability.HEART_RATE))),
            )

        // then it remains unassigned instead of being immediately auto-selected again:
        assertNull(clearedHeartRate.selection.heartRateSourceId)
        assertEquals(RideRoleStatus.AMBIGUOUS, clearedHeartRate.heartRate.status)
        assertEquals(RideReadiness.READY, clearedHeartRate.readiness)

        // when a fresh ride sees multiple heart-rate sources without a prior choice:
        val multipleHeartRate =
            RideEquipmentSelector().current(
                listOf(
                    trainer,
                    rideSource("strap-a", setOf(RideSourceCapability.HEART_RATE)),
                    rideSource("strap-b", setOf(RideSourceCapability.HEART_RATE)),
                ),
            )

        // then heart rate is ambiguous but does not block the control-ready ride:
        assertNull(multipleHeartRate.selection.heartRateSourceId)
        assertEquals(RideRoleStatus.AMBIGUOUS, multipleHeartRate.heartRate.status)
        assertEquals(RideReadiness.READY, multipleHeartRate.readiness)
        assertEquals(emptyList(), multipleHeartRate.readinessReasons)
    }
}
