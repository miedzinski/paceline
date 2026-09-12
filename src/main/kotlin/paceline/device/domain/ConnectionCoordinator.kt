package paceline.device.domain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.ports.BluetoothDiscovery
import paceline.device.ports.DeviceCommunication
import paceline.device.ports.DeviceConnectionSession
import paceline.device.ports.DeviceDiscoveryResult
import paceline.device.ports.IndoorBikeTelemetrySource
import paceline.device.ports.WifiDiscovery
import java.util.UUID

class NotDiscoveredException(
    val deviceId: String,
) : IllegalArgumentException("Device $deviceId is not in the latest discovery result")

class AlreadyConnectedException(
    device: DeviceAdvertisement,
) : IllegalStateException("Device ${device.name} cannot open a connection while another device is active")

@Component
class ConnectionCoordinator(
    private val wifiDiscovery: WifiDiscovery,
    private val bluetoothDiscovery: BluetoothDiscovery,
    private val communication: DeviceCommunication,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val stateMachine = ConnectionStateMachine()
    private val discoveredDevices = linkedMapOf<String, DeviceAdvertisement>()
    private var connection: DeviceConnectionSession? = null

    @Synchronized
    fun current(): ConnectionState = stateMachine.current()

    @Synchronized
    fun currentTelemetry(): IndoorBikeTelemetry? =
        connection
            ?.takeIf { it.connection.isOpen() && current().phase == ConnectionPhase.CONNECTED }
            ?.capability<IndoorBikeTelemetrySource>()
            ?.latestTelemetry()

    @Synchronized
    fun discover(): DiscoverySnapshot {
        val connectionRemainsActive = hasActiveConnection()
        if (!connectionRemainsActive) {
            markClosedConnectionAsDisconnected()
            publish(ConnectionEvent.BeginDiscovery)
        }

        val result =
            try {
                discoverFromSources()
            } catch (exception: Exception) {
                DeviceDiscoveryResult.Failed(
                    code = ConnectionFailureCode.DISCOVERY_ERROR,
                    message = exception.message ?: "Device discovery failed",
                )
            }

        return when (result) {
            is DeviceDiscoveryResult.Found -> {
                val discovered = result.candidates.map(DeviceDiscoveryCandidate::toDeviceAdvertisement)
                if (discovered.isEmpty()) {
                    discoveredDevices.clear()
                    val message = "No device was found on the configured network or Bluetooth transports"
                    if (connectionRemainsActive) {
                        discoverySnapshot(
                            state = current(),
                            failure = ConnectionFailure(ConnectionFailureCode.NO_DEVICE_FOUND, message),
                        )
                    } else {
                        discoverySnapshot(
                            publish(ConnectionEvent.DiscoveryUnavailable(message)),
                        )
                    }
                } else {
                    discoveredDevices.clear()
                    discovered.forEach { device ->
                        discoveredDevices[UUID.randomUUID().toString()] = device
                    }
                    discoverySnapshot(
                        if (connectionRemainsActive) {
                            current()
                        } else {
                            publish(ConnectionEvent.DeviceDiscovered(discovered.first()))
                        },
                    )
                }
            }

            DeviceDiscoveryResult.NotFound -> {
                discoveredDevices.clear()
                val message = "No device was found on the configured network or Bluetooth transports"
                if (connectionRemainsActive) {
                    discoverySnapshot(
                        state = current(),
                        failure = ConnectionFailure(ConnectionFailureCode.NO_DEVICE_FOUND, message),
                    )
                } else {
                    discoverySnapshot(
                        publish(ConnectionEvent.DiscoveryUnavailable(message)),
                    )
                }
            }

            is DeviceDiscoveryResult.Failed -> {
                discoveredDevices.clear()
                if (connectionRemainsActive) {
                    discoverySnapshot(
                        state = current(),
                        failure = ConnectionFailure(result.code, result.message),
                    )
                } else {
                    discoverySnapshot(
                        publish(ConnectionEvent.DiscoveryFailed(result.code, result.message)),
                    )
                }
            }
        }
    }

    @Synchronized
    fun connect(deviceId: String): ConnectionState {
        val device =
            discoveredDevices[deviceId]
                ?: throw NotDiscoveredException(deviceId)

        val currentConnection = connection
        if (currentConnection?.connection?.isOpen() == true) {
            if (
                currentConnection.connection.device == device &&
                current().phase == ConnectionPhase.CONNECTED
            ) {
                return current()
            }
            throw AlreadyConnectedException(device)
        }

        if (currentConnection != null) {
            closeConnection(currentConnection)
            connection = null
        }
        markClosedConnectionAsDisconnected()

        publish(ConnectionEvent.BeginConnection(device))
        return try {
            connection = communication.connect(device)
            publish(ConnectionEvent.ConnectionEstablished)
        } catch (exception: Exception) {
            publish(
                ConnectionEvent.ConnectionFailed(
                    exception.message ?: "Device connection failed",
                ),
            )
        }
    }

    @Synchronized
    override fun close() {
        connection?.let(::closeConnection)
        connection = null
    }

    private fun discoverFromSources(): DeviceDiscoveryResult {
        val results = listOf(wifiDiscovery.discover(), bluetoothDiscovery.discover())
        val candidates =
            results
                .filterIsInstance<DeviceDiscoveryResult.Found>()
                .flatMap { it.candidates }
        if (candidates.isNotEmpty()) {
            results.filterIsInstance<DeviceDiscoveryResult.Failed>().forEach { failure ->
                logger.warn("Device discovery source failed while another source found devices: {}", failure.message)
            }
            return DeviceDiscoveryResult.Found(candidates)
        }

        return results.filterIsInstance<DeviceDiscoveryResult.Failed>().firstOrNull()
            ?: DeviceDiscoveryResult.NotFound
    }

    private fun discoverySnapshot(
        state: ConnectionState,
        failure: ConnectionFailure? = null,
    ): DiscoverySnapshot =
        DiscoverySnapshot(
            state = state,
            devices = discoveredDevices.toOptions(),
            failure = failure,
        )

    private fun Map<String, DeviceAdvertisement>.toOptions(): List<AdvertisementOption> =
        entries.map { (id, device) ->
            AdvertisementOption(id = id, device = device)
        }

    private fun markClosedConnectionAsDisconnected() {
        if (
            current().phase == ConnectionPhase.CONNECTED &&
            connection?.connection?.isOpen() != true
        ) {
            publish(ConnectionEvent.ConnectionLost("The device connection is no longer open"))
        }
    }

    private fun hasActiveConnection(): Boolean =
        current().phase == ConnectionPhase.CONNECTED &&
            connection?.connection?.isOpen() == true

    private fun closeConnection(deviceConnection: DeviceConnectionSession) {
        try {
            deviceConnection.close()
        } catch (exception: Exception) {
            logger.warn("Failed to close device connection", exception)
        }
    }

    private fun publish(event: ConnectionEvent): ConnectionState {
        val next = stateMachine.transition(event)

        logger.info(
            "Device lifecycle phase={} device={} endpoint={} failureCode={} failureMessage={}",
            next.phase,
            next.device?.name,
            next.device?.endpoint,
            next.failure?.code,
            next.failure?.message,
        )
        return next
    }
}
