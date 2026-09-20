package paceline.device.adapters.bluetooth

import paceline.device.adapters.gatt.GattClient
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

    fun discover(
        serviceUuids: Set<UUID> = emptySet(),
        onCandidate: (BluetoothDeviceCandidate) -> Unit,
    ): List<BluetoothDeviceCandidate> =
        discover(serviceUuids).also { candidates ->
            candidates.forEach(onCandidate)
        }

    fun connect(endpoint: DeviceEndpoint.Bluetooth): GattClient

    override fun close()
}
