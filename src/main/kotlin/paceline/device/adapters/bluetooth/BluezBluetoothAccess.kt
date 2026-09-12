package paceline.device.adapters.bluetooth

import com.github.hypfvieh.bluetooth.DeviceManager
import com.github.hypfvieh.bluetooth.DiscoveryFilter
import com.github.hypfvieh.bluetooth.DiscoveryTransport
import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.adapters.GattClient
import paceline.device.adapters.profiles.ftms.FtmsUuid
import paceline.device.config.DeviceProperties
import paceline.device.domain.DeviceEndpoint
import java.util.Locale
import java.util.UUID

@Component
class BluezBluetoothAccess(
    private val properties: DeviceProperties,
) : BluetoothAccess {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val deviceManager =
        lazy {
            DeviceManager.createInstance(false)
        }

    override fun discover(serviceUuids: Set<UUID>): List<BluetoothDeviceCandidate> {
        val manager = deviceManager.value
        val adapter =
            selectAdapter(manager)
                ?: return emptyList()

        manager.setDefaultAdapter(adapter)
        manager.setScanFilter(
            buildMap {
                put(DiscoveryFilter.Transport, DiscoveryTransport.LE)
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
        return manager
            .scanForBluetoothDevices(adapter.address, timeoutMillis)
            .asSequence()
            .filter { device -> advertisesAny(device, serviceUuids) }
            .mapNotNull(::toCandidate)
            .toList()
            .also { candidates ->
                candidates.forEach { candidate ->
                    logger.info(
                        "Found device advertisement {} at {} via Bluetooth LE",
                        candidate.name,
                        candidate.address,
                    )
                }
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
                closeTimeout = properties.connectTimeout,
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
        if (deviceManager.isInitialized()) {
            deviceManager.value.closeConnection()
        }
    }

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
    }
}
