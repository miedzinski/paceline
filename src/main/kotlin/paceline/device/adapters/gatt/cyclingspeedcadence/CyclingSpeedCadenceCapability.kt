package paceline.device.adapters.gatt.cyclingspeedcadence

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

class CyclingSpeedCadenceCapability private constructor(
    private val gattClient: GattClient,
    private val measurementCharacteristic: UUID,
    private val clock: Clock,
) : CyclingTelemetrySource,
    AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<CyclingTelemetry?>(null)
    private val listeners = CopyOnWriteArrayList<CyclingTelemetryListener>()
    private val decoder = CyclingSpeedCadenceMeasurementDecoder()
    private val notificationRegistration: AutoCloseable
    private val measurementsReference = AtomicReference<Set<CyclingMeasurement>>(emptySet())
    private var previousCrankRevolutions: Int? = null
    private var previousCrankEventTime: Int? = null

    // CSC service discovery does not distinguish wheel-only sensors from cadence sensors.
    override val measurements: Set<CyclingMeasurement>
        get() = measurementsReference.get()

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
        check(!closed.get()) { "Cycling Speed and Cadence capability is closed" }
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
            if (measurement.cumulativeCrankRevolutions == null || measurement.lastCrankEventTime == null) {
                logger.debug("Ignoring Cycling Speed and Cadence notification without crank revolution data")
                return
            }
            measurementsReference.set(setOf(CyclingMeasurement.CADENCE))
            val telemetry =
                CyclingTelemetry(
                    cadenceRpm = measurement.cadenceRpm(),
                    receivedAt = clock.instant(),
                )
            latest.set(telemetry)
            listeners.forEach { listener ->
                try {
                    listener.onTelemetry(telemetry)
                } catch (exception: Exception) {
                    logger.warn("Cycling Speed and Cadence telemetry listener failed", exception)
                }
            }
        } catch (exception: Exception) {
            logger.warn("Ignoring malformed Cycling Speed and Cadence Measurement notification", exception)
        }
    }

    @Synchronized
    private fun CyclingSpeedCadenceMeasurement.cadenceRpm(): Double? {
        val currentRevolutions = cumulativeCrankRevolutions ?: return null
        val currentEventTime = lastCrankEventTime ?: return null
        val previousRevolutions = previousCrankRevolutions
        val previousEventTime = previousCrankEventTime
        previousCrankRevolutions = currentRevolutions
        previousCrankEventTime = currentEventTime
        if (previousRevolutions == null || previousEventTime == null) {
            return null
        }

        val revolutionDelta = (currentRevolutions - previousRevolutions) and 0xffff
        val eventTimeDelta = (currentEventTime - previousEventTime) and 0xffff
        if (eventTimeDelta == 0) {
            return null
        }
        return revolutionDelta * EVENTS_PER_MINUTE / eventTimeDelta.toDouble()
    }

    companion object {
        fun create(
            gattClient: GattClient,
            services: List<GattService>,
            clock: Clock,
        ): CyclingSpeedCadenceCapability? {
            val service =
                services.firstOrNull { it.uuid == CyclingSpeedCadenceUuid.CYCLING_SPEED_CADENCE_SERVICE }
                    ?: return null
            val measurementCharacteristic =
                service.characteristics
                    .firstOrNull {
                        it.uuid == CyclingSpeedCadenceUuid.CSC_MEASUREMENT &&
                            (
                                it.supports(GattCharacteristicProperty.NOTIFY) ||
                                    it.supports(GattCharacteristicProperty.INDICATE)
                            )
                    }?.uuid
                    ?: throw CyclingSpeedCadenceProtocolException(
                        "Device does not expose a notifiable Cycling Speed and Cadence Measurement characteristic",
                    )
            return CyclingSpeedCadenceCapability(
                gattClient = gattClient,
                measurementCharacteristic = measurementCharacteristic,
                clock = clock,
            )
        }

        private const val EVENTS_PER_MINUTE = 60.0 * 1024.0
    }
}

object CyclingSpeedCadenceGattCapabilityFactory : GattCapabilityFactory {
    override fun create(
        gattClient: GattClient,
        services: List<GattService>,
        clock: Clock,
    ): List<DeviceCapability> = CyclingSpeedCadenceCapability.create(gattClient, services, clock)?.let(::listOf) ?: emptyList()
}

class CyclingSpeedCadenceProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
