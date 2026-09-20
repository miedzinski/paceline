package paceline.device.adapters.gatt.ftms

import org.slf4j.LoggerFactory
import paceline.device.adapters.gatt.GattCapabilityFactory
import paceline.device.adapters.gatt.GattCharacteristicProperty
import paceline.device.adapters.gatt.GattClient
import paceline.device.adapters.gatt.GattNotification
import paceline.device.adapters.gatt.GattService
import paceline.device.domain.CyclingMeasurement
import paceline.device.domain.CyclingTelemetry
import paceline.device.ports.CyclingTelemetryListener
import paceline.device.ports.CyclingTelemetrySource
import paceline.device.ports.DeviceCapability
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FtmsIndoorBikeCapability private constructor(
    private val gattClient: GattClient,
    private val telemetryCharacteristic: UUID,
    private val clock: Clock,
) : CyclingTelemetrySource,
    AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<CyclingTelemetry?>(null)
    private val telemetryListeners = CopyOnWriteArrayList<CyclingTelemetryListener>()
    private val decoder = FtmsIndoorBikeDataDecoder()
    private val notificationRegistration: AutoCloseable

    override val measurements: Set<CyclingMeasurement> = CyclingMeasurement.entries.toSet()

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

    override fun addTelemetryListener(listener: CyclingTelemetryListener): AutoCloseable {
        check(!closed.get()) { "FTMS Indoor Bike capability is closed" }
        telemetryListeners += listener
        return AutoCloseable { telemetryListeners -= listener }
    }

    override fun latestTelemetry(): CyclingTelemetry? = latest.get()

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
            val telemetry = decoder.decode(notification.value).toCyclingTelemetry(clock.instant())
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
        ): FtmsIndoorBikeCapability? {
            val telemetryCharacteristic =
                ftmsService.characteristics
                    .firstOrNull {
                        it.uuid == FtmsUuid.INDOOR_BIKE_DATA &&
                            (
                                it.supports(GattCharacteristicProperty.NOTIFY) ||
                                    it.supports(GattCharacteristicProperty.INDICATE)
                            )
                    }?.uuid ?: return null
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
        val controlPoint = findPowerControlCharacteristic(ftmsService)
        if (telemetry == null && controlPoint == null) {
            throw FtmsProtocolException(
                "Device does not expose a usable FTMS Indoor Bike Data or Control Point characteristic",
            )
        }
        return try {
            buildList {
                telemetry?.let { add(it) }
                controlPoint?.let { characteristic ->
                    add(
                        FtmsErgControl(
                            gattClient = gattClient,
                            controlPointCharacteristic = characteristic,
                        ),
                    )
                }
            }
        } catch (exception: Exception) {
            telemetry?.close()
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
