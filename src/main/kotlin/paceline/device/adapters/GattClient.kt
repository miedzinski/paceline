package paceline.device.adapters

import java.util.UUID

enum class GattCharacteristicProperty {
    NOTIFY,
    INDICATE,
}

data class GattCharacteristic(
    val uuid: UUID,
    val properties: Set<GattCharacteristicProperty>,
) {
    fun supports(property: GattCharacteristicProperty): Boolean = property in properties
}

data class GattService(
    val uuid: UUID,
    val characteristics: List<GattCharacteristic>,
)

data class GattNotification(
    val characteristic: UUID,
    val value: ByteArray,
)

interface GattClient : AutoCloseable {
    fun discoverServices(): List<GattService>

    fun addNotificationListener(listener: (GattNotification) -> Unit): AutoCloseable

    fun enableNotifications(characteristic: UUID)

    fun isOpen(): Boolean

    override fun close()
}
