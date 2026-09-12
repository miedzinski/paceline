package paceline.device.adapters.bluetooth

import paceline.device.adapters.GattClient
import paceline.device.domain.DeviceEndpoint
import java.util.UUID

data class BluetoothDeviceCandidate(
    val name: String,
    val address: String,
    val adapterAddress: String,
    val rssi: Short? = null,
)

interface BluetoothAccess : AutoCloseable {
    fun discover(serviceUuids: Set<UUID> = emptySet()): List<BluetoothDeviceCandidate>

    fun connect(endpoint: DeviceEndpoint.Bluetooth): GattClient

    override fun close()
}
