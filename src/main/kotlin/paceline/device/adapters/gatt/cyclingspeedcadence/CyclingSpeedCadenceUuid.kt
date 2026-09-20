package paceline.device.adapters.gatt.cyclingspeedcadence

import java.util.UUID

object CyclingSpeedCadenceUuid {
    val CYCLING_SPEED_CADENCE_SERVICE: UUID = uuid16(0x1816)
    val CSC_MEASUREMENT: UUID = uuid16(0x2a5b)

    private fun uuid16(value: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))
}
