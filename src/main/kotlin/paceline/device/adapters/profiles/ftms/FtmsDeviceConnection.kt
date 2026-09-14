package paceline.device.adapters.profiles.ftms

import org.slf4j.LoggerFactory
import paceline.device.adapters.GattCharacteristicProperty
import paceline.device.adapters.GattClient
import paceline.device.adapters.GattNotification
import paceline.device.adapters.GattService
import paceline.device.domain.DeviceAdvertisement
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.DeviceConnection
import paceline.device.ports.IndoorBikePowerControl
import paceline.device.ports.IndoorBikeTelemetryListener
import paceline.device.ports.IndoorBikeTelemetrySource
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FtmsDeviceConnection(
    override val device: DeviceAdvertisement,
    private val gattClient: GattClient,
    private val clock: Clock = Clock.systemUTC(),
) : DeviceConnection,
    IndoorBikeTelemetrySource {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<IndoorBikeTelemetry?>(null)
    private val telemetryListeners = CopyOnWriteArrayList<IndoorBikeTelemetryListener>()
    private val decoder = FtmsIndoorBikeDataDecoder()
    private val telemetryCharacteristic: UUID
    private val notificationRegistration: AutoCloseable
    val powerControl: IndoorBikePowerControl?

    init {
        try {
            val services = gattClient.discoverServices()
            telemetryCharacteristic = findTelemetryCharacteristic(services)
            val telemetryRegistration = gattClient.addNotificationListener(::handleNotification)
            try {
                gattClient.enableNotifications(telemetryCharacteristic)
            } catch (exception: Exception) {
                telemetryRegistration.close()
                throw exception
            }
            notificationRegistration = telemetryRegistration
            powerControl =
                findPowerControlCharacteristic(services)?.let { characteristic ->
                    try {
                        FtmsErgControl(
                            gattClient = gattClient,
                            controlPointCharacteristic = characteristic,
                        )
                    } catch (exception: Exception) {
                        telemetryRegistration.close()
                        throw exception
                    }
                }
        } catch (exception: Exception) {
            gattClient.close()
            throw exception
        }
    }

    override fun isOpen(): Boolean = !closed.get() && gattClient.isOpen()

    override fun addTelemetryListener(listener: IndoorBikeTelemetryListener): AutoCloseable {
        check(!closed.get()) { "Device connection is closed" }
        telemetryListeners += listener
        return AutoCloseable { telemetryListeners -= listener }
    }

    override fun latestTelemetry(): IndoorBikeTelemetry? = latest.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            powerControl?.close()
            notificationRegistration.close()
            gattClient.close()
        }
    }

    private fun handleNotification(notification: GattNotification) {
        if (notification.characteristic != telemetryCharacteristic || closed.get()) {
            return
        }

        try {
            val telemetry = decoder.decode(notification.value).toIndoorBikeTelemetry(clock.instant())
            latest.set(telemetry)
            telemetryListeners.forEach { listener ->
                try {
                    listener.onTelemetry(telemetry)
                } catch (exception: Exception) {
                    logger.warn("Indoor bike telemetry listener failed", exception)
                }
            }
        } catch (exception: Exception) {
            logger.warn("Ignoring malformed FTMS Indoor Bike Data notification", exception)
        }
    }

    private fun findTelemetryCharacteristic(services: List<GattService>): UUID {
        val ftmsService =
            services.firstOrNull { it.uuid == FtmsUuid.FITNESS_MACHINE_SERVICE }
                ?: throw FtmsProtocolException("Device does not expose the FTMS service")
        return ftmsService.characteristics
            .firstOrNull {
                it.uuid == FtmsUuid.INDOOR_BIKE_DATA &&
                    (
                        it.supports(GattCharacteristicProperty.NOTIFY) ||
                            it.supports(GattCharacteristicProperty.INDICATE)
                    )
            }?.uuid
            ?: throw FtmsProtocolException(
                "Device does not expose a notifiable FTMS Indoor Bike Data characteristic",
            )
    }

    private fun findPowerControlCharacteristic(services: List<GattService>): UUID? {
        val ftmsService = services.firstOrNull { it.uuid == FtmsUuid.FITNESS_MACHINE_SERVICE } ?: return null
        return ftmsService.characteristics
            .firstOrNull {
                it.uuid == FtmsUuid.FITNESS_MACHINE_CONTROL_POINT &&
                    it.supports(GattCharacteristicProperty.WRITE) &&
                    (
                        it.supports(GattCharacteristicProperty.NOTIFY) ||
                            it.supports(GattCharacteristicProperty.INDICATE)
                    )
            }?.uuid
    }
}

class FtmsProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
