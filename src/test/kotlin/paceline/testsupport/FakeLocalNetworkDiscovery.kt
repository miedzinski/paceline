package paceline.testsupport

import paceline.device.ports.DeviceDiscoveryResult
import paceline.device.ports.LocalNetworkDiscovery

class FakeLocalNetworkDiscovery(
    var result: DeviceDiscoveryResult,
) : LocalNetworkDiscovery {
    var calls: Int = 0
        private set

    override fun discover(): DeviceDiscoveryResult {
        calls += 1
        return result
    }
}
