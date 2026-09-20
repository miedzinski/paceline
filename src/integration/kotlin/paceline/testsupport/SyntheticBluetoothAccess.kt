package paceline.testsupport

import paceline.device.adapters.bluetooth.BluetoothAccess
import paceline.device.adapters.bluetooth.BluetoothDeviceCandidate
import paceline.device.adapters.gatt.GattClient
import paceline.device.adapters.gatt.GattNotification
import paceline.device.adapters.gatt.GattService
import paceline.device.domain.DeviceEndpoint
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class NoopBluetoothAccess : BluetoothAccess {
    override fun discover(serviceUuids: Set<UUID>): List<BluetoothDeviceCandidate> = emptyList()

    override fun connect(endpoint: DeviceEndpoint.Bluetooth): GattClient =
        error("Bluetooth connection should not be attempted in the Wi-Fi integration suite")

    override fun close() = Unit
}

class SyntheticBluetoothAccess(
    var candidates: List<BluetoothDeviceCandidate>,
    var gattClient: SyntheticGattClient,
) : BluetoothAccess {
    var gattClientFactory: (DeviceEndpoint.Bluetooth) -> GattClient = { gattClient }

    var discoverCalls: Int = 0
        private set

    var connectCalls: Int = 0
        private set

    var requestedServiceUuids: Set<UUID> = emptySet()
        private set

    var connectedEndpoint: DeviceEndpoint.Bluetooth? = null
        private set

    override fun discover(serviceUuids: Set<UUID>): List<BluetoothDeviceCandidate> {
        discoverCalls += 1
        requestedServiceUuids = serviceUuids
        return candidates
    }

    override fun connect(endpoint: DeviceEndpoint.Bluetooth): GattClient {
        connectCalls += 1
        connectedEndpoint = endpoint
        return gattClientFactory(endpoint)
    }

    override fun close() {
        gattClient.close()
    }
}

class SyntheticGattClient(
    private val services: List<GattService>,
) : GattClient {
    private val open = AtomicBoolean(true)
    private val listeners = CopyOnWriteArrayList<(GattNotification) -> Unit>()

    val enabledNotifications = CopyOnWriteArrayList<UUID>()
    val writes = CopyOnWriteArrayList<Pair<UUID, ByteArray>>()
    var onWrite: ((UUID, ByteArray) -> Unit)? = null

    override fun discoverServices(): List<GattService> {
        check(open.get()) { "Synthetic GATT client is closed" }
        return services
    }

    override fun addNotificationListener(listener: (GattNotification) -> Unit): AutoCloseable {
        check(open.get()) { "Synthetic GATT client is closed" }
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun enableNotifications(characteristic: UUID) {
        check(open.get()) { "Synthetic GATT client is closed" }
        enabledNotifications += characteristic
    }

    override fun writeCharacteristic(
        characteristic: UUID,
        value: ByteArray,
    ) {
        check(open.get()) { "Synthetic GATT client is closed" }
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
        listeners.forEach { listener ->
            listener(GattNotification(characteristic, value.copyOf()))
        }
    }
}
