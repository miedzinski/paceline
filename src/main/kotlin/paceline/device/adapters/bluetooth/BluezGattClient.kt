package paceline.device.adapters.bluetooth

import com.github.hypfvieh.bluetooth.DeviceManager
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattCharacteristic
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattService
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler
import org.freedesktop.dbus.interfaces.Properties.PropertiesChanged
import org.slf4j.LoggerFactory
import paceline.device.adapters.gatt.GattCharacteristic
import paceline.device.adapters.gatt.GattCharacteristicProperty
import paceline.device.adapters.gatt.GattClient
import paceline.device.adapters.gatt.GattNotification
import paceline.device.adapters.gatt.GattService
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class BluezGattClient(
    private val manager: DeviceManager,
    private val device: BluetoothDevice,
    private val serviceDiscoveryTimeout: Duration,
) : GattClient {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(GattNotification) -> Unit>()
    private val characteristics = linkedMapOf<UUID, BluetoothGattCharacteristic>()
    private val enabledCharacteristics = CopyOnWriteArrayList<BluetoothGattCharacteristic>()
    private val propertyHandler =
        object : AbstractPropertiesChangedHandler() {
            override fun handle(signal: PropertiesChanged) {
                val signalPath = signal.path ?: return
                if (signalPath !in characteristics.values.map(BluetoothGattCharacteristic::getDbusPath)) {
                    return
                }
                if (signal.interfaceName != "org.bluez.GattCharacteristic1") {
                    return
                }
                val value = signal.propertiesChanged[VALUE_PROPERTY]?.value.toByteArrayOrNull() ?: return
                val characteristic =
                    characteristics.entries
                        .firstOrNull { (_, wrapper) ->
                            wrapper.dbusPath == signalPath
                        }?.key ?: return
                listeners.forEach { listener ->
                    try {
                        listener(GattNotification(characteristic, value.copyOf()))
                    } catch (exception: Exception) {
                        logger.warn("Bluetooth GATT notification listener failed", exception)
                    }
                }
            }
        }

    init {
        manager.registerPropertyHandler(propertyHandler)
    }

    override fun discoverServices(): List<GattService> {
        checkOpen()
        val deadline = System.nanoTime() + serviceDiscoveryTimeout.toNanos().coerceAtLeast(1L)
        var services = device.gattServices
        while (services.isEmpty() && System.nanoTime() < deadline) {
            sleepForServiceDiscovery()
            services = device.gattServices
        }

        logger.info(
            "BlueZ GATT discovery for {} returned {} service(s), servicesResolved={}: {}",
            device.address,
            services.size,
            device.isServicesResolved,
            describeServices(services),
        )
        characteristics.clear()
        return services.mapNotNull { service ->
            val serviceUuid = parseUuid(service.uuid) ?: return@mapNotNull null
            val gattCharacteristics =
                service.gattCharacteristics.mapNotNull { characteristic ->
                    val characteristicUuid = parseUuid(characteristic.uuid) ?: return@mapNotNull null
                    val properties =
                        buildSet {
                            val flags = characteristic.flags.orEmpty()
                            if ("notify" in flags) add(GattCharacteristicProperty.NOTIFY)
                            if ("indicate" in flags) add(GattCharacteristicProperty.INDICATE)
                            if ("write" in flags || "write-without-response" in flags) {
                                add(GattCharacteristicProperty.WRITE)
                            }
                        }
                    characteristics[characteristicUuid] = characteristic
                    GattCharacteristic(characteristicUuid, properties)
                }
            GattService(serviceUuid, gattCharacteristics)
        }
    }

    override fun addNotificationListener(listener: (GattNotification) -> Unit): AutoCloseable {
        checkOpen()
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun enableNotifications(characteristic: UUID) {
        checkOpen()
        val wrapper =
            characteristics[characteristic]
                ?: throw IllegalArgumentException("Bluetooth GATT characteristic $characteristic was not discovered")
        wrapper.startNotify()
        enabledCharacteristics += wrapper
    }

    override fun writeCharacteristic(
        characteristic: UUID,
        value: ByteArray,
    ) {
        checkOpen()
        val wrapper =
            characteristics[characteristic]
                ?: throw IllegalArgumentException("Bluetooth GATT characteristic $characteristic was not discovered")
        wrapper.writeValue(value, emptyMap())
    }

    override fun isOpen(): Boolean = !closed.get() && device.isConnected == true

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        enabledCharacteristics.reversed().forEach { characteristic ->
            try {
                characteristic.stopNotify()
            } catch (exception: Exception) {
                logger.debug("Unable to stop Bluetooth GATT notification", exception)
            }
        }
        try {
            manager.unRegisterPropertyHandler(propertyHandler)
        } catch (exception: Exception) {
            logger.debug("Unable to unregister Bluetooth GATT property handler", exception)
        }
        try {
            device.disconnect()
        } catch (exception: Exception) {
            logger.debug("Unable to disconnect Bluetooth device", exception)
        }
        listeners.clear()
        characteristics.clear()
    }

    private fun checkOpen() {
        check(isOpen()) { "Bluetooth GATT connection is closed" }
    }

    private fun sleepForServiceDiscovery() {
        try {
            Thread.sleep(SERVICE_DISCOVERY_POLL_MILLIS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while discovering Bluetooth GATT services", exception)
        }
    }

    private fun Any?.toByteArrayOrNull(): ByteArray? =
        when (this) {
            is ByteArray -> this
            is List<*> -> if (all { it is Number }) map { (it as Number).toByte() }.toByteArray() else null
            else -> null
        }

    private fun parseUuid(value: String?): UUID? {
        if (value == null) {
            return null
        }
        val normalized = value.trim().removePrefix("0x").removePrefix("0X")
        return when (normalized.length) {
            4 -> UUID.fromString("0000$normalized-0000-1000-8000-00805f9b34fb")
            8 -> UUID.fromString("$normalized-0000-1000-8000-00805f9b34fb")
            else -> runCatching { UUID.fromString(normalized) }.getOrNull()
        }
    }

    private fun describeServices(services: List<BluetoothGattService>): String =
        services.ifEmpty { return "none" }.joinToString(separator = "; ") { service ->
            val characteristics =
                service.gattCharacteristics
                    .joinToString(separator = ", ") { characteristic ->
                        "${characteristic.uuid}${characteristic.flags.orEmpty()}"
                    }.ifEmpty { "no characteristics" }
            "${service.uuid}[$characteristics]"
        }

    private companion object {
        const val VALUE_PROPERTY = "Value"
        const val SERVICE_DISCOVERY_POLL_MILLIS = 50L
    }
}
