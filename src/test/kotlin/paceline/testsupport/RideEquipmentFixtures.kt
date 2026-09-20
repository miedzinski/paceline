package paceline.testsupport

import paceline.device.domain.ConnectionPhase
import paceline.device.domain.DeviceAdvertisement
import paceline.device.domain.DeviceEndpoint
import paceline.training.domain.RideSourceCapability
import paceline.training.domain.RideSourceDescriptor

fun rideSource(
    id: String,
    capabilities: Set<RideSourceCapability>,
    state: ConnectionPhase = ConnectionPhase.CONNECTED,
    name: String = id,
): RideSourceDescriptor =
    RideSourceDescriptor(
        id = id,
        state = state,
        capabilities = capabilities,
        device =
            DeviceAdvertisement(
                name = name,
                endpoint = DeviceEndpoint.Wifi(host = "127.0.0.1", port = 1),
            ),
    )
