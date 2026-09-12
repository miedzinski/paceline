package paceline.device.adapters.bluetooth

import paceline.device.domain.DeviceEndpoint
import paceline.device.ports.DeviceDiscoveryResult
import paceline.testsupport.FakeBluetoothAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BluetoothDeviceDiscoveryTest {
    @Test
    fun `maps BlueZ candidates to the shared device discovery model`() {
        // given a Bluetooth access adapter that found an FTMS device:
        val bluetooth =
            FakeBluetoothAccess(
                candidates =
                    listOf(
                        BluetoothDeviceCandidate(
                            name = "KICKR CORE BLE",
                            address = "AA:BB:CC:DD:EE:FF",
                            adapterAddress = "11:22:33:44:55:66",
                            rssi = -48,
                        ),
                    ),
            )

        // when Bluetooth discovery is requested:
        val result = BluetoothDeviceDiscovery(bluetooth).discover()
        val found = assertIs<DeviceDiscoveryResult.Found>(result)
        val endpoint = assertIs<DeviceEndpoint.Bluetooth>(found.candidates.single().endpoint)

        // then the endpoint and transport metadata are retained for the shared endpoint:
        assertEquals("KICKR CORE BLE", found.candidates.single().name)
        assertEquals("AA:BB:CC:DD:EE:FF", endpoint.address)
        assertEquals("11:22:33:44:55:66", endpoint.adapterAddress)
        assertEquals("bluetooth", found.candidates.single().metadata["transport"])
        assertEquals("-48", found.candidates.single().metadata["rssi"])
        assertEquals(
            setOf(paceline.device.adapters.profiles.ftms.FtmsUuid.FITNESS_MACHINE_SERVICE),
            bluetooth.requestedServiceUuids,
        )
    }

    @Test
    fun `returns not found when Bluetooth access finds no candidates`() {
        // given Bluetooth access with no matching advertisements:
        val bluetooth = FakeBluetoothAccess()

        // when discovery is requested:
        val result = BluetoothDeviceDiscovery(bluetooth).discover()

        // then the adapter reports no matching advertisement:
        assertIs<DeviceDiscoveryResult.NotFound>(result)
        assertEquals(1, bluetooth.discovered)
    }
}
