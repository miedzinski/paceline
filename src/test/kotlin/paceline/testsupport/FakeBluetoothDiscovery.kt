package paceline.testsupport

import paceline.device.ports.BluetoothDiscovery
import paceline.device.ports.DeviceDiscoveryResult

class FakeBluetoothDiscovery(
    var result: DeviceDiscoveryResult,
) : BluetoothDiscovery {
    var calls: Int = 0
        private set

    override fun discover(): DeviceDiscoveryResult {
        calls += 1
        return result
    }
}
