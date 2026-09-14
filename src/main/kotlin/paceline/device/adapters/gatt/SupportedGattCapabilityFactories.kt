package paceline.device.adapters.gatt

import paceline.device.adapters.gatt.ftms.FtmsGattCapabilityFactory
import paceline.device.adapters.gatt.heartrate.HeartRateGattCapabilityFactory

object SupportedGattCapabilityFactories {
    val all: List<GattCapabilityFactory> =
        listOf(
            FtmsGattCapabilityFactory,
            HeartRateGattCapabilityFactory,
        )
}
