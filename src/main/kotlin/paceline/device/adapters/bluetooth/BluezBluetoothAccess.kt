package paceline.device.adapters.bluetooth

import com.github.hypfvieh.bluetooth.DeviceManager
import com.github.hypfvieh.bluetooth.DiscoveryFilter
import com.github.hypfvieh.bluetooth.DiscoveryTransport
import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler
import org.freedesktop.dbus.handlers.AbstractSignalHandlerBase
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.adapters.gatt.GattClient
import paceline.device.config.DeviceProperties
import paceline.device.domain.DeviceEndpoint
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Component
class BluezBluetoothAccess(
    private val properties: DeviceProperties,
) : BluetoothAccess {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val deviceManager =
        lazy {
            DeviceManager.createInstance(false)
        }
    private val signalExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "paceline-bluetooth-discovery-events").apply {
                isDaemon = true
            }
        }

    override fun discover(serviceUuids: Set<UUID>): List<BluetoothDeviceCandidate> = discover(serviceUuids) { }

    override fun discover(
        serviceUuids: Set<UUID>,
        onCandidate: (BluetoothDeviceCandidate) -> Unit,
    ): List<BluetoothDeviceCandidate> {
        val manager = deviceManager.value
        val adapter =
            selectAdapter(manager)
                ?: return emptyList()

        manager.setDefaultAdapter(adapter)
        manager.setScanFilter(
            buildMap {
                put(DiscoveryFilter.Transport, DiscoveryTransport.LE)
                put(DiscoveryFilter.DuplicateData, true)
                if (serviceUuids.isNotEmpty()) {
                    put(DiscoveryFilter.UUIDs, serviceUuids.map(UUID::toString).toTypedArray())
                }
            },
        )
        val timeoutMillis =
            properties.discoveryTimeout
                .toMillis()
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
        val emitted = ConcurrentHashMap<String, BluetoothDeviceCandidate>()
        val interfacesAddedHandler =
            object : AbstractSignalHandlerBase<ObjectManager.InterfacesAdded>() {
                override fun getImplementationClass(): Class<ObjectManager.InterfacesAdded> = ObjectManager.InterfacesAdded::class.java

                override fun handle(signal: ObjectManager.InterfacesAdded) {
                    if (isDevicePath(signal.objectPath, adapter)) {
                        queueRefresh(manager, adapter, serviceUuids, signal.objectPath, emitted, onCandidate)
                    }
                }
            }
        val propertiesChangedHandler =
            object : AbstractPropertiesChangedHandler() {
                override fun handle(signal: Properties.PropertiesChanged) {
                    if (signal.interfaceName == BLUEZ_DEVICE_INTERFACE &&
                        isDevicePath(signal.path, adapter)
                    ) {
                        queueRefresh(manager, adapter, serviceUuids, signal.path, emitted, onCandidate)
                    }
                }
            }

        return try {
            manager.registerSignalHandler(interfacesAddedHandler)
            manager.registerSignalHandler(propertiesChangedHandler)
            if (!adapter.startDiscovery()) {
                throw IllegalStateException(
                    "Bluetooth discovery could not be started on adapter ${adapter.address}",
                )
            } else {
                try {
                    Thread.sleep(timeoutMillis.toLong())
                } finally {
                    adapter.stopDiscovery()
                }
                signalExecutor
                    .submit {
                        Unit
                    }.get(EVENT_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                emitted.values.toList()
            }
        } finally {
            runCatching { manager.unRegisterSignalHandler(interfacesAddedHandler) }
            runCatching { manager.unRegisterSignalHandler(propertiesChangedHandler) }
        }
    }

    override fun connect(endpoint: DeviceEndpoint.Bluetooth): GattClient {
        val manager = deviceManager.value
        val device =
            manager
                .getDevices(endpoint.adapterAddress, true)
                .firstOrNull { it.address?.equals(endpoint.address, ignoreCase = true) == true }
                ?: throw IllegalStateException(
                    "Bluetooth device ${endpoint.address} is no longer available on adapter " +
                        endpoint.adapterAddress,
                )

        connectDevice(device)
        return try {
            BluezGattClient(
                manager = manager,
                device = device,
                serviceDiscoveryTimeout = properties.bluetooth.gattServiceDiscoveryTimeout,
            )
        } catch (exception: Exception) {
            try {
                device.disconnect()
            } catch (_: Exception) {
                // Preserve the GATT setup failure as the useful diagnostic.
            }
            throw exception
        }
    }

    override fun close() {
        signalExecutor.shutdownNow()
        if (deviceManager.isInitialized()) {
            deviceManager.value.closeConnection()
        }
    }

    private fun queueRefresh(
        manager: DeviceManager,
        adapter: BluetoothAdapter,
        serviceUuids: Set<UUID>,
        devicePath: String,
        emitted: MutableMap<String, BluetoothDeviceCandidate>,
        onCandidate: (BluetoothDeviceCandidate) -> Unit,
    ) {
        runCatching {
            signalExecutor.execute {
                refreshCandidate(manager, adapter, serviceUuids, devicePath, emitted, onCandidate)
            }
        }.onFailure { exception ->
            logger.debug("Could not queue Bluetooth discovery update", exception)
        }
    }

    private fun refreshCandidate(
        manager: DeviceManager,
        adapter: BluetoothAdapter,
        serviceUuids: Set<UUID>,
        devicePath: String,
        emitted: MutableMap<String, BluetoothDeviceCandidate>,
        onCandidate: (BluetoothDeviceCandidate) -> Unit,
    ) {
        runCatching {
            currentCandidates(manager, adapter, serviceUuids, devicePath).forEach { candidate ->
                val key = candidate.address.lowercase(Locale.ROOT)
                val previous = emitted.put(key, candidate)
                if (previous != candidate) {
                    logger.info(
                        "Found device advertisement {} at {} via Bluetooth LE",
                        candidate.name,
                        candidate.address,
                    )
                    onCandidate(candidate)
                }
            }
        }.onFailure { exception ->
            logger.debug("Could not refresh Bluetooth discovery candidates", exception)
        }
    }

    private fun currentCandidates(
        manager: DeviceManager,
        adapter: BluetoothAdapter,
        serviceUuids: Set<UUID>,
        devicePath: String? = null,
    ): List<BluetoothDeviceCandidate> {
        manager.findBtDevicesByIntrospection(adapter)
        return manager
            .getDevices(adapter.address, false)
            .asSequence()
            .filter { device -> devicePath == null || device.dbusPath == devicePath }
            .onEach { device ->
                logger.debug(
                    "Bluetooth advertisement {} at {} reports service UUIDs={} service-data UUIDs={}",
                    device.name ?: device.address,
                    device.address,
                    device.uuids.orEmpty(),
                    device.serviceData?.keys.orEmpty(),
                )
            }.filter { device -> advertisesAny(device, serviceUuids) }
            .mapNotNull(::toCandidate)
            .toList()
    }

    private fun isDevicePath(
        path: String?,
        adapter: BluetoothAdapter,
    ): Boolean = path?.startsWith("${adapter.dbusPath}/dev_") == true

    private fun selectAdapter(manager: DeviceManager): BluetoothAdapter? {
        val adapters = manager.scanForBluetoothAdapters()
        val configured = properties.bluetoothAdapter?.trim()?.takeIf(String::isNotEmpty)
        return if (configured == null) {
            adapters.firstOrNull()
        } else {
            adapters.firstOrNull {
                it.address?.equals(configured, ignoreCase = true) == true ||
                    it.deviceName.equals(configured, ignoreCase = true)
            } ?: throw IllegalStateException("Configured Bluetooth adapter $configured was not found")
        }
    }

    private fun connectDevice(device: BluetoothDevice) {
        if (device.isConnected == true) {
            return
        }

        val deadline = System.nanoTime() + properties.connectTimeout.toNanos().coerceAtLeast(1L)
        if (!device.connect() || device.isConnected != true) {
            waitUntilConnected(device, deadline)
        }
        if (device.isConnected != true) {
            throw IllegalStateException("Bluetooth device ${device.address} did not connect")
        }
    }

    private fun waitUntilConnected(
        device: BluetoothDevice,
        deadline: Long,
    ) {
        while (device.isConnected != true && System.nanoTime() < deadline) {
            try {
                Thread.sleep(CONNECTION_POLL_MILLIS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while connecting Bluetooth device", exception)
            }
        }
    }

    private fun advertisesAny(
        device: BluetoothDevice,
        serviceUuids: Set<UUID>,
    ): Boolean {
        if (serviceUuids.isEmpty()) {
            return true
        }

        val advertisedUuids = device.uuids.orEmpty().mapNotNull(::parseUuid)
        val serviceDataUuids =
            device.serviceData
                ?.keys
                .orEmpty()
                .mapNotNull(::parseUuid)
        return (advertisedUuids.isEmpty() && serviceDataUuids.isEmpty()) ||
            serviceUuids.any { it in advertisedUuids || it in serviceDataUuids }
    }

    private fun toCandidate(device: BluetoothDevice): BluetoothDeviceCandidate? {
        val address = device.address?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val adapterAddress =
            device.adapter.address
                ?.trim()
                ?.takeIf(String::isNotEmpty) ?: return null
        val name = device.name?.trim()?.takeIf(String::isNotEmpty) ?: address
        return BluetoothDeviceCandidate(
            name = name,
            address = address,
            adapterAddress = adapterAddress,
            rssi = device.rssi,
        )
    }

    private fun parseUuid(value: String): UUID? {
        val normalized = value.trim().removePrefix("0x").removePrefix("0X")
        return when (normalized.length) {
            4 -> UUID.fromString("0000$normalized-0000-1000-8000-00805f9b34fb")
            8 -> UUID.fromString("$normalized-0000-1000-8000-00805f9b34fb")
            else -> runCatching { UUID.fromString(normalized.lowercase(Locale.ROOT)) }.getOrNull()
        }
    }

    private companion object {
        const val CONNECTION_POLL_MILLIS = 50L
        const val EVENT_FLUSH_TIMEOUT_SECONDS = 1L
        const val BLUEZ_DEVICE_INTERFACE = "org.bluez.Device1"
    }
}
