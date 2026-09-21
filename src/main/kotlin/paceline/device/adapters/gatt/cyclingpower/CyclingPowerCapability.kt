package paceline.device.adapters.gatt.cyclingpower

import org.slf4j.LoggerFactory
import paceline.device.adapters.gatt.GattCapabilityFactory
import paceline.device.adapters.gatt.GattCharacteristicProperty
import paceline.device.adapters.gatt.GattClient
import paceline.device.adapters.gatt.GattNotification
import paceline.device.adapters.gatt.GattService
import paceline.device.domain.CyclingMeasurement
import paceline.device.ports.CyclingTelemetryListener
import paceline.device.ports.CyclingTelemetrySource
import paceline.device.ports.DeviceCapability
import paceline.telemetry.domain.CyclingTelemetry
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CyclingPowerCapability private constructor(
    private val gattClient: GattClient,
    private val measurementCharacteristic: UUID,
    private val clock: Clock,
) : CyclingTelemetrySource,
    AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<CyclingTelemetry?>(null)
    private val listeners = CopyOnWriteArrayList<CyclingTelemetryListener>()
    private val decoder = CyclingPowerMeasurementDecoder()
    private val notificationRegistration: AutoCloseable

    override val measurements: Set<CyclingMeasurement> = setOf(CyclingMeasurement.POWER)

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

    override fun addTelemetryListener(listener: CyclingTelemetryListener): AutoCloseable {
        check(!closed.get()) { "Cycling Power capability is closed" }
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun latestTelemetry(): CyclingTelemetry? = latest.get()

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
            val measurement = decoder.decode(notification.value)
            val telemetry =
                CyclingTelemetry(
                    powerWatts = measurement.instantaneousPowerWatts,
                    receivedAt = clock.instant(),
                )
            latest.set(telemetry)
            listeners.forEach { listener ->
                try {
                    listener.onTelemetry(telemetry)
                } catch (exception: Exception) {
                    logger.warn("Cycling Power telemetry listener failed", exception)
                }
            }
        } catch (exception: Exception) {
            logger.warn("Ignoring malformed Cycling Power Measurement notification", exception)
        }
    }

    companion object {
        fun create(
            gattClient: GattClient,
            services: List<GattService>,
            clock: Clock,
        ): CyclingPowerCapability? {
            val service = services.firstOrNull { it.uuid == CyclingPowerUuid.CYCLING_POWER_SERVICE } ?: return null
            val measurementCharacteristic =
                service.characteristics
                    .firstOrNull {
                        it.uuid == CyclingPowerUuid.CYCLING_POWER_MEASUREMENT &&
                            (
                                it.supports(GattCharacteristicProperty.NOTIFY) ||
                                    it.supports(GattCharacteristicProperty.INDICATE)
                            )
                    }?.uuid
                    ?: throw CyclingPowerProtocolException(
                        "Device does not expose a notifiable Cycling Power Measurement characteristic",
                    )
            return CyclingPowerCapability(
                gattClient = gattClient,
                measurementCharacteristic = measurementCharacteristic,
                clock = clock,
            )
        }
    }
}

object CyclingPowerGattCapabilityFactory : GattCapabilityFactory {
    override fun create(
        gattClient: GattClient,
        services: List<GattService>,
        clock: Clock,
    ): List<DeviceCapability> = CyclingPowerCapability.create(gattClient, services, clock)?.let(::listOf) ?: emptyList()
}

class CyclingPowerProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
