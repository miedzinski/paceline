package paceline.device.adapters.gatt.cyclingpower

import java.util.UUID

object CyclingPowerUuid {
    val CYCLING_POWER_SERVICE: UUID = uuid16(0x1818)
    val CYCLING_POWER_MEASUREMENT: UUID = uuid16(0x2a63)

    private fun uuid16(value: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))
}
