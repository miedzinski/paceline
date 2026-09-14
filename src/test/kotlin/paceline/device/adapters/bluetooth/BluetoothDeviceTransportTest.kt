package paceline.device.adapters.bluetooth

import paceline.device.adapters.gatt.GattCharacteristic
import paceline.device.adapters.gatt.GattCharacteristicProperty
import paceline.device.adapters.gatt.GattService
import paceline.device.adapters.gatt.ftms.FtmsUuid
import paceline.device.domain.DeviceEndpoint
import paceline.device.ports.IndoorBikeTelemetrySource
import paceline.testsupport.FakeBluetoothAccess
import paceline.testsupport.FakeGattClient
import paceline.testsupport.kickrCore2Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BluetoothDeviceTransportTest {
    @Test
    fun `opens the common FTMS session through the Bluetooth access port`() {
        // given a Bluetooth access port with a notifiable FTMS GATT client:
        val gattClient =
            FakeGattClient(
                listOf(
                    GattService(
                        FtmsUuid.FITNESS_MACHINE_SERVICE,
                        listOf(
                            GattCharacteristic(
                                FtmsUuid.INDOOR_BIKE_DATA,
                                setOf(GattCharacteristicProperty.NOTIFY),
                            ),
                        ),
                    ),
                ),
            )
        val bluetooth = FakeBluetoothAccess(gattClient = gattClient)
        val endpoint = DeviceEndpoint.Bluetooth("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66")
        val device = kickrCore2Device().copy(endpoint = endpoint)

        // when the selected Bluetooth device is connected:
        val connection = BluetoothDeviceTransport(bluetooth).connect(device)

        // then the device session exposes the indoor-bike capability and preserves the endpoint:
        assertEquals(endpoint, bluetooth.connectedEndpoint)
        assertEquals(true, connection.connection.isOpen())
        assertNotNull(connection.capability<IndoorBikeTelemetrySource>())
        assertEquals(listOf(FtmsUuid.INDOOR_BIKE_DATA), gattClient.enabledNotifications)
        connection.close()
    }
}
