package paceline.testsupport

import paceline.device.adapters.GattClient
import paceline.device.adapters.GattNotification
import paceline.device.adapters.GattService
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class FakeGattClient(
    private val services: List<GattService>,
) : GattClient {
    private val open = AtomicBoolean(true)
    private val listeners = CopyOnWriteArrayList<(GattNotification) -> Unit>()
    val enabledNotifications = mutableListOf<UUID>()

    override fun discoverServices(): List<GattService> = services

    override fun addNotificationListener(listener: (GattNotification) -> Unit): AutoCloseable {
        check(open.get()) { "Fake GATT client is closed" }
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun enableNotifications(characteristic: UUID) {
        check(open.get()) { "Fake GATT client is closed" }
        enabledNotifications += characteristic
    }

    override fun isOpen(): Boolean = open.get()

    override fun close() {
        open.set(false)
        listeners.clear()
    }

    fun emit(
        characteristic: UUID,
        value: ByteArray,
    ) {
        listeners.forEach { it(GattNotification(characteristic, value)) }
    }
}
