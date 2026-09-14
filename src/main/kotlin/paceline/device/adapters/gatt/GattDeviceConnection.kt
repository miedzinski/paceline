package paceline.device.adapters.gatt

import org.slf4j.LoggerFactory
import paceline.device.domain.DeviceAdvertisement
import paceline.device.ports.DeviceCapability
import paceline.device.ports.DeviceConnection
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

class GattDeviceConnection(
    override val device: DeviceAdvertisement,
    private val gattClient: GattClient,
    capabilityFactories: List<GattCapabilityFactory>,
    clock: Clock = Clock.systemUTC(),
) : DeviceConnection {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean(false)
    private val capabilities: List<DeviceCapability> = initializeCapabilities(capabilityFactories, clock)

    fun capabilities(): List<DeviceCapability> = capabilities

    override fun isOpen(): Boolean = !closed.get() && gattClient.isOpen()

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        capabilities.asReversed().filterIsInstance<AutoCloseable>().forEach(::closeQuietly)
        closeQuietly(gattClient)
    }

    private fun initializeCapabilities(
        capabilityFactories: List<GattCapabilityFactory>,
        clock: Clock,
    ): List<DeviceCapability> {
        var initializedCapabilities = emptyList<DeviceCapability>()
        try {
            val services = gattClient.discoverServices()
            logger.info(
                "Discovered GATT services for {}: {}",
                device.name,
                describeServices(services),
            )
            val failures = mutableListOf<Exception>()
            val discoveredCapabilities =
                buildList {
                    capabilityFactories.forEach { factory ->
                        try {
                            val created = factory.create(gattClient, services, clock)
                            addAll(created)
                            logger.info(
                                "GATT capability factory {} for {} initialized: {}",
                                factoryName(factory),
                                device.name,
                                created.map(::capabilityName).ifEmpty { listOf("none") },
                            )
                        } catch (exception: Exception) {
                            failures += exception
                            logger.warn(
                                "GATT capability factory {} for {} failed: {}",
                                factoryName(factory),
                                device.name,
                                exception.message,
                                exception,
                            )
                        }
                    }
                }
            if (discoveredCapabilities.isEmpty()) {
                logger.warn(
                    "No supported GATT capabilities initialized for {}. Services: {}",
                    device.name,
                    describeServices(services),
                )
                throw failures.firstOrNull()
                    ?: GattDeviceConnectionException(
                        "Device does not expose a supported GATT capability",
                    )
            }
            initializedCapabilities = discoveredCapabilities
            return discoveredCapabilities
        } catch (exception: Exception) {
            initializedCapabilities.asReversed().filterIsInstance<AutoCloseable>().forEach(::closeQuietly)
            closeQuietly(gattClient)
            throw exception
        }
    }

    private fun factoryName(factory: GattCapabilityFactory): String = factory::class.simpleName ?: factory.javaClass.name

    private fun capabilityName(capability: DeviceCapability): String = capability::class.simpleName ?: capability.javaClass.name

    private fun describeServices(services: List<GattService>): String =
        services.ifEmpty { return "none" }.joinToString(separator = "; ") { service ->
            val characteristics =
                service.characteristics
                    .ifEmpty { return@joinToString "${service.uuid} (no characteristics)" }
                    .joinToString(separator = ", ") { characteristic ->
                        val properties =
                            characteristic.properties
                                .map(Enum<*>::name)
                                .sorted()
                                .joinToString("|")
                        "${characteristic.uuid}[$properties]"
                    }
            "${service.uuid}: $characteristics"
        }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (_: Exception) {
            // Preserve the original connection failure or close operation.
        }
    }
}

class GattDeviceConnectionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
