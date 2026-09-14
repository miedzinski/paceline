package paceline.device.adapters.gatt

import paceline.device.ports.DeviceCapability
import java.time.Clock

fun interface GattCapabilityFactory {
    fun create(
        gattClient: GattClient,
        services: List<GattService>,
        clock: Clock,
    ): List<DeviceCapability>
}
