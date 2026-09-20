package paceline.device.ports

import paceline.device.domain.DeviceAdvertisement

interface DeviceConnection : AutoCloseable {
    val device: DeviceAdvertisement

    fun isOpen(): Boolean

    override fun close()
}

data class DeviceConnectionSession(
    val connection: DeviceConnection,
    val capabilities: List<DeviceCapability> = emptyList(),
) : AutoCloseable {
    inline fun <reified T : DeviceCapability> capability(): T? = capabilities.filterIsInstance<T>().firstOrNull()

    inline fun <reified T : DeviceCapability> capabilities(): List<T> = capabilities.filterIsInstance<T>()

    override fun close() {
        connection.close()
    }
}
