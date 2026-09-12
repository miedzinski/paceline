package paceline.testsupport

import paceline.device.ports.DeviceDiscoveryResult
import paceline.device.ports.WifiDiscovery

class FakeWifiDiscovery(
    var result: DeviceDiscoveryResult,
) : WifiDiscovery {
    var calls: Int = 0
        private set

    override fun discover(): DeviceDiscoveryResult {
        calls += 1
        return result
    }
}
