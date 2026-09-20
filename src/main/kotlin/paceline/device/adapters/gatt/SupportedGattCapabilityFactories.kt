package paceline.device.adapters.gatt

import paceline.device.adapters.gatt.cyclingpower.CyclingPowerGattCapabilityFactory
import paceline.device.adapters.gatt.cyclingspeedcadence.CyclingSpeedCadenceGattCapabilityFactory
import paceline.device.adapters.gatt.ftms.FtmsGattCapabilityFactory
import paceline.device.adapters.gatt.heartrate.HeartRateGattCapabilityFactory

object SupportedGattCapabilityFactories {
    val all: List<GattCapabilityFactory> =
        listOf(
            FtmsGattCapabilityFactory,
            CyclingPowerGattCapabilityFactory,
            CyclingSpeedCadenceGattCapabilityFactory,
            HeartRateGattCapabilityFactory,
        )
}
