package paceline.device.adapters.gatt.ftms

import org.slf4j.LoggerFactory
import paceline.device.adapters.gatt.GattCapabilityFactory
import paceline.device.adapters.gatt.GattCharacteristicProperty
import paceline.device.adapters.gatt.GattClient
import paceline.device.adapters.gatt.GattNotification
import paceline.device.adapters.gatt.GattService
import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.DeviceCapability
import paceline.device.ports.IndoorBikeTelemetryListener
import paceline.device.ports.IndoorBikeTelemetrySource
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FtmsIndoorBikeCapability private constructor(
    private val gattClient: GattClient,
    private val telemetryCharacteristic: UUID,
    private val clock: Clock,
) : IndoorBikeTelemetrySource,
    AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<IndoorBikeTelemetry?>(null)
    private val telemetryListeners = CopyOnWriteArrayList<IndoorBikeTelemetryListener>()
    private val decoder = FtmsIndoorBikeDataDecoder()
    private val notificationRegistration: AutoCloseable

    init {
        val registration = gattClient.addNotificationListener(::handleNotification)
        try {
            gattClient.enableNotifications(telemetryCharacteristic)
            notificationRegistration = registration
        } catch (exception: Exception) {
            registration.close()
            throw exception
        }
    }

    override fun addTelemetryListener(listener: IndoorBikeTelemetryListener): AutoCloseable {
        check(!closed.get()) { "FTMS Indoor Bike capability is closed" }
        telemetryListeners += listener
        return AutoCloseable { telemetryListeners -= listener }
    }

    override fun latestTelemetry(): IndoorBikeTelemetry? = latest.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        notificationRegistration.close()
        telemetryListeners.clear()
    }

    private fun handleNotification(notification: GattNotification) {
        if (closed.get() || notification.characteristic != telemetryCharacteristic) {
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

    companion object {
        fun create(
            gattClient: GattClient,
            services: List<GattService>,
            clock: Clock,
        ): FtmsIndoorBikeCapability? {
            val ftmsService = services.firstOrNull { it.uuid == FtmsUuid.FITNESS_MACHINE_SERVICE } ?: return null
            return create(gattClient, ftmsService, clock)
        }

        internal fun create(
            gattClient: GattClient,
            ftmsService: GattService,
            clock: Clock,
        ): FtmsIndoorBikeCapability {
            val telemetryCharacteristic =
                ftmsService.characteristics
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
            return FtmsIndoorBikeCapability(
                gattClient = gattClient,
                telemetryCharacteristic = telemetryCharacteristic,
                clock = clock,
            )
        }
    }
}

object FtmsGattCapabilityFactory : GattCapabilityFactory {
    override fun create(
        gattClient: GattClient,
        services: List<GattService>,
        clock: Clock,
    ): List<DeviceCapability> {
        val ftmsService = services.firstOrNull { it.uuid == FtmsUuid.FITNESS_MACHINE_SERVICE } ?: return emptyList()
        val telemetry = FtmsIndoorBikeCapability.create(gattClient, ftmsService, clock)
        return try {
            buildList {
                add(telemetry)
                findPowerControlCharacteristic(ftmsService)?.let { characteristic ->
                    add(
                        FtmsErgControl(
                            gattClient = gattClient,
                            controlPointCharacteristic = characteristic,
                        ),
                    )
                }
            }
        } catch (exception: Exception) {
            telemetry.close()
            throw exception
        }
    }

    private fun findPowerControlCharacteristic(service: GattService): UUID? =
        service.characteristics
            .firstOrNull {
                it.uuid == FtmsUuid.FITNESS_MACHINE_CONTROL_POINT &&
                    it.supports(GattCharacteristicProperty.WRITE) &&
                    (
                        it.supports(GattCharacteristicProperty.NOTIFY) ||
                            it.supports(GattCharacteristicProperty.INDICATE)
                    )
            }?.uuid
}

class FtmsProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
