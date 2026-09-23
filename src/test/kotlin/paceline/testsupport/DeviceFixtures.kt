package paceline.testsupport

import paceline.device.domain.DeviceAdvertisement
import paceline.device.domain.DeviceDiscoveryCandidate
import paceline.device.domain.DeviceEndpoint

fun kickrCore2Candidate(
    name: String = "KICKR CORE 77AB",
    host: String = "192.168.1.45",
    port: Int = 36866,
    metadata: Map<String, String> = mapOf("serial-number" to "253045635"),
): DeviceDiscoveryCandidate =
    DeviceDiscoveryCandidate(
        name = name,
        endpoint = DeviceEndpoint.LocalNetwork(host, port),
        metadata = metadata,
    )

fun kickrCore2Device(
    name: String = "KICKR CORE 77AB",
    host: String = "192.168.1.45",
    port: Int = 36866,
): DeviceAdvertisement = kickrCore2Candidate(name, host, port).toDeviceAdvertisement()
