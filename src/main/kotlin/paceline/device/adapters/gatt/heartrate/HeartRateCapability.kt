package paceline.device.adapters.gatt.heartrate

import org.slf4j.LoggerFactory
import paceline.device.adapters.gatt.GattCapabilityFactory
import paceline.device.adapters.gatt.GattCharacteristicProperty
import paceline.device.adapters.gatt.GattClient
import paceline.device.adapters.gatt.GattNotification
import paceline.device.adapters.gatt.GattService
import paceline.device.ports.DeviceCapability
import paceline.device.ports.HeartRateTelemetryListener
import paceline.device.ports.HeartRateTelemetrySource
import paceline.telemetry.domain.HeartRateTelemetry
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HeartRateCapability private constructor(
    private val gattClient: GattClient,
    private val measurementCharacteristic: UUID,
    private val clock: Clock,
) : HeartRateTelemetrySource,
    AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<HeartRateTelemetry?>(null)
    private val listeners = CopyOnWriteArrayList<HeartRateTelemetryListener>()
    private val decoder = HeartRateMeasurementDecoder()
    private val notificationRegistration: AutoCloseable

    init {
        val registration = gattClient.addNotificationListener(::handleNotification)
        try {
            gattClient.enableNotifications(measurementCharacteristic)
            notificationRegistration = registration
        } catch (exception: Exception) {
            registration.close()
            throw exception
        }
    }

    override fun addHeartRateListener(listener: HeartRateTelemetryListener): AutoCloseable {
        check(!closed.get()) { "Bluetooth heart-rate capability is closed" }
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun latestHeartRate(): HeartRateTelemetry? = latest.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        notificationRegistration.close()
        listeners.clear()
    }

    private fun handleNotification(notification: GattNotification) {
        if (closed.get() || notification.characteristic != measurementCharacteristic) {
            return
        }

        try {
            val heartRate = decoder.decode(notification.value).toHeartRateTelemetry(clock.instant())
            latest.set(heartRate)
            listeners.forEach { listener ->
                try {
                    listener.onHeartRate(heartRate)
                } catch (exception: Exception) {
                    logger.warn("Heart-rate telemetry listener failed", exception)
                }
            }
        } catch (exception: Exception) {
            logger.warn("Ignoring malformed Bluetooth Heart Rate Measurement notification", exception)
        }
    }

    companion object {
        fun create(
            gattClient: GattClient,
            services: List<GattService>,
            clock: Clock,
        ): HeartRateCapability? {
            val heartRateService = services.firstOrNull { it.uuid == HeartRateUuid.HEART_RATE_SERVICE } ?: return null
            val measurementCharacteristic =
                heartRateService.characteristics
                    .firstOrNull {
                        it.uuid == HeartRateUuid.HEART_RATE_MEASUREMENT &&
                            (
                                it.supports(GattCharacteristicProperty.NOTIFY) ||
                                    it.supports(GattCharacteristicProperty.INDICATE)
                            )
                    }?.uuid
                    ?: throw HeartRateProtocolException(
                        "Device does not expose a notifiable Bluetooth Heart Rate Measurement characteristic",
                    )
            return HeartRateCapability(
                gattClient = gattClient,
                measurementCharacteristic = measurementCharacteristic,
                clock = clock,
            )
        }
    }
}

object HeartRateGattCapabilityFactory : GattCapabilityFactory {
    override fun create(
        gattClient: GattClient,
        services: List<GattService>,
        clock: Clock,
    ): List<DeviceCapability> = HeartRateCapability.create(gattClient, services, clock)?.let(::listOf) ?: emptyList()
}

class HeartRateProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
