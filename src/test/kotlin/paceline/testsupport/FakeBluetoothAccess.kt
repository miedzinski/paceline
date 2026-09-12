package paceline.testsupport

import paceline.device.adapters.GattClient
import paceline.device.adapters.bluetooth.BluetoothAccess
import paceline.device.adapters.bluetooth.BluetoothDeviceCandidate
import paceline.device.domain.DeviceEndpoint
import java.util.UUID

class FakeBluetoothAccess(
    private val candidates: List<BluetoothDeviceCandidate> = emptyList(),
    private val gattClient: GattClient? = null,
) : BluetoothAccess {
    var discovered = 0
        private set
    var connectedEndpoint: DeviceEndpoint.Bluetooth? = null
        private set

    var requestedServiceUuids: Set<UUID> = emptySet()
        private set

    override fun discover(serviceUuids: Set<UUID>): List<BluetoothDeviceCandidate> {
        discovered += 1
        requestedServiceUuids = serviceUuids
        return candidates
    }

    override fun connect(endpoint: DeviceEndpoint.Bluetooth): GattClient {
        connectedEndpoint = endpoint
        return requireNotNull(gattClient) { "No fake GATT client configured" }
    }

    override fun close() = Unit
}
