package paceline.testsupport

import paceline.device.adapters.gatt.GattClient
import paceline.device.adapters.gatt.GattNotification
import paceline.device.adapters.gatt.GattService
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class FakeGattClient(
    private val services: List<GattService>,
) : GattClient {
    private val open = AtomicBoolean(true)
    private val listeners = CopyOnWriteArrayList<(GattNotification) -> Unit>()
    val enabledNotifications = mutableListOf<UUID>()
    val writes = mutableListOf<Pair<UUID, ByteArray>>()
    var discoverServicesCalls = 0
        private set
    var onWrite: ((UUID, ByteArray) -> Unit)? = null

    override fun discoverServices(): List<GattService> {
        discoverServicesCalls += 1
        return services
    }

    override fun addNotificationListener(listener: (GattNotification) -> Unit): AutoCloseable {
        check(open.get()) { "Fake GATT client is closed" }
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun enableNotifications(characteristic: UUID) {
        check(open.get()) { "Fake GATT client is closed" }
        enabledNotifications += characteristic
    }

    override fun writeCharacteristic(
        characteristic: UUID,
        value: ByteArray,
    ) {
        check(open.get()) { "Fake GATT client is closed" }
        writes += characteristic to value.copyOf()
        onWrite?.invoke(characteristic, value.copyOf())
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
