package paceline.device.domain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.ports.BluetoothDiscovery
import paceline.device.ports.DeviceCommunication
import paceline.device.ports.DeviceConnectionSession
import paceline.device.ports.DeviceDiscoveryResult
import paceline.device.ports.HeartRateTelemetryListener
import paceline.device.ports.HeartRateTelemetrySource
import paceline.device.ports.IndoorBikePowerControl
import paceline.device.ports.IndoorBikeTelemetryListener
import paceline.device.ports.IndoorBikeTelemetrySource
import paceline.device.ports.WifiDiscovery
import java.time.Clock
import java.util.UUID

class NotDiscoveredException(
    val deviceId: String,
) : IllegalArgumentException("Device $deviceId is not in the latest discovery result")

class ConnectionNotFoundException(
    val connectionId: String,
) : IllegalArgumentException("Connection $connectionId is not active")

@Component
class ConnectionCoordinator(
    private val wifiDiscovery: WifiDiscovery,
    private val bluetoothDiscovery: BluetoothDiscovery,
    private val communication: DeviceCommunication,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private data class ManagedConnection(
        val id: String,
        val session: DeviceConnectionSession,
        val stateMachine: ConnectionStateMachine,
    )

    private val logger = LoggerFactory.getLogger(javaClass)
    private val discoveryStateMachine = ConnectionStateMachine(clock = clock)
    private val discoveredDevices = linkedMapOf<String, DeviceAdvertisement>()
    private val connections = linkedMapOf<String, ManagedConnection>()
    private var primaryTrainingConnectionId: String? = null
    private var lastConnectionState = discoveryStateMachine.current()

    @Synchronized
    fun current(): ConnectionState {
        refreshConnectionStates()
        return primaryTrainingConnection()?.stateMachine?.current()
            ?: connections.values
                .firstOrNull()
                ?.stateMachine
                ?.current()
            ?: lastConnectionState
    }

    @Synchronized
    fun currentTelemetry(): IndoorBikeTelemetry? {
        refreshConnectionStates()
        val managed = primaryTrainingConnection() ?: return null
        if (!isConnected(managed)) {
            return null
        }
        return managed.session.capability<IndoorBikeTelemetrySource>()?.latestTelemetry()
    }

    @Synchronized
    fun currentPowerControl(): IndoorBikePowerControl? {
        refreshConnectionStates()
        val managed = primaryTrainingConnection() ?: return null
        if (!isConnected(managed)) {
            return null
        }
        return managed.session.capability<IndoorBikePowerControl>()
    }

    @Synchronized
    fun addTelemetryListener(listener: IndoorBikeTelemetryListener): AutoCloseable {
        refreshConnectionStates()
        val managed = primaryTrainingConnection()
        return if (managed != null && isConnected(managed)) {
            managed.session
                .capability<IndoorBikeTelemetrySource>()
                ?.addTelemetryListener(listener)
                ?: AutoCloseable { }
        } else {
            AutoCloseable { }
        }
    }

    @Synchronized
    fun heartRateSources(): List<HeartRateSourceDescriptor> {
        refreshConnectionStates()
        return connections.values.mapNotNull { managed ->
            if (managed.session.capability<HeartRateTelemetrySource>() == null) {
                null
            } else {
                HeartRateSourceDescriptor(
                    id = managed.id,
                    device = managed.session.connection.device,
                    state = managed.stateMachine.current().phase,
                )
            }
        }
    }

    @Synchronized
    fun currentHeartRate(sourceId: String): HeartRateTelemetry? {
        refreshConnectionStates()
        val managed = connections[sourceId] ?: return null
        if (!isConnected(managed)) {
            return null
        }
        return managed.session.capability<HeartRateTelemetrySource>()?.latestHeartRate()
    }

    @Synchronized
    fun addHeartRateListener(
        sourceId: String,
        listener: HeartRateTelemetryListener,
    ): AutoCloseable {
        refreshConnectionStates()
        val managed = connections[sourceId]
        return if (managed != null && isConnected(managed)) {
            managed.session
                .capability<HeartRateTelemetrySource>()
                ?.addHeartRateListener(listener)
                ?: AutoCloseable { }
        } else {
            AutoCloseable { }
        }
    }

    @Synchronized
    fun connectedDevices(): List<ConnectedDeviceSnapshot> {
        refreshConnectionStates()
        return connections.values.map { managed ->
            val indoorBike = managed.session.capability<IndoorBikeTelemetrySource>()
            val powerControl = managed.session.capability<IndoorBikePowerControl>()
            val heartRate = managed.session.capability<HeartRateTelemetrySource>()
            val capabilities =
                buildSet {
                    if (indoorBike != null) add(DeviceCapabilityType.INDOOR_BIKE_TELEMETRY)
                    if (powerControl != null) add(DeviceCapabilityType.ERG_POWER_CONTROL)
                    if (heartRate != null) add(DeviceCapabilityType.HEART_RATE)
                }
            ConnectedDeviceSnapshot(
                id = managed.id,
                state = managed.stateMachine.current(),
                capabilities = capabilities,
                telemetry = if (isConnected(managed)) indoorBike?.latestTelemetry() else null,
                heartRate = if (isConnected(managed)) heartRate?.latestHeartRate() else null,
            )
        }
    }

    @Synchronized
    fun discover(): DiscoverySnapshot {
        refreshConnectionStates()
        val connectionRemainsActive = hasActiveConnection()
        if (!connectionRemainsActive) {
            discoveryStateMachine.transitionIfPossible(ConnectionEvent.BeginDiscovery)
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
                            publishDiscovery(ConnectionEvent.DiscoveryUnavailable(message)),
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
                            publishDiscovery(ConnectionEvent.DeviceDiscovered(discovered.first()))
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
                        publishDiscovery(ConnectionEvent.DiscoveryUnavailable(message)),
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
                        publishDiscovery(ConnectionEvent.DiscoveryFailed(result.code, result.message)),
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

        refreshConnectionStates()
        val existing =
            connections.values.firstOrNull {
                it.session.connection.device == device && isConnected(it)
            }
        if (existing != null) {
            return existing.stateMachine.current()
        }

        connections
            .filterValues { it.session.connection.device == device }
            .values
            .toList()
            .forEach { managed ->
                closeConnection(managed.session)
                connections.remove(managed.id)
                if (primaryTrainingConnectionId == managed.id) {
                    primaryTrainingConnectionId = null
                }
            }

        val attemptStateMachine =
            ConnectionStateMachine(
                initialState =
                    ConnectionState(
                        phase = ConnectionPhase.DISCOVERED,
                        device = device,
                        changedAt = clock.instant(),
                    ),
                clock = clock,
            )
        attemptStateMachine.transition(ConnectionEvent.BeginConnection(device))
        return try {
            val session = communication.connect(device)
            val managed =
                ManagedConnection(
                    id = UUID.randomUUID().toString(),
                    session = session,
                    stateMachine = attemptStateMachine,
                )
            val connectedState = attemptStateMachine.transition(ConnectionEvent.ConnectionEstablished)
            connections[managed.id] = managed
            if (primaryTrainingConnectionId == null && session.capability<IndoorBikePowerControl>() != null) {
                primaryTrainingConnectionId = managed.id
            }
            lastConnectionState = connectedState
            connectedState
        } catch (exception: Exception) {
            attemptStateMachine
                .transition(
                    ConnectionEvent.ConnectionFailed(
                        exception.message ?: "Device connection failed",
                    ),
                ).also { lastConnectionState = it }
        }
    }

    @Synchronized
    fun disconnect(connectionId: String) {
        val managed =
            connections[connectionId]
                ?: throw ConnectionNotFoundException(connectionId)
        val disconnectedState =
            if (managed.stateMachine.current().phase == ConnectionPhase.CONNECTED) {
                managed.stateMachine.transition(ConnectionEvent.ConnectionLost("The device connection was closed"))
            } else {
                managed.stateMachine.current()
            }
        closeConnection(managed.session)
        connections.remove(connectionId)
        if (primaryTrainingConnectionId == connectionId) {
            primaryTrainingConnectionId = null
        }
        lastConnectionState = disconnectedState
    }

    @Synchronized
    override fun close() {
        connections.values.forEach { managed -> closeConnection(managed.session) }
        connections.clear()
        primaryTrainingConnectionId = null
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

    private fun publishDiscovery(event: ConnectionEvent): ConnectionState =
        discoveryStateMachine.transition(event).also { lastConnectionState = it }

    private fun refreshConnectionStates() {
        connections.values.forEach { managed ->
            if (
                managed.stateMachine.current().phase == ConnectionPhase.CONNECTED &&
                !managed.session.connection.isOpen()
            ) {
                managed.stateMachine
                    .transition(ConnectionEvent.ConnectionLost("The device connection is no longer open"))
                    .also { state ->
                        if (managed.id == primaryTrainingConnectionId) {
                            lastConnectionState = state
                        }
                    }
            }
        }
    }

    private fun hasActiveConnection(): Boolean = connections.values.any(::isConnected)

    private fun primaryTrainingConnection(): ManagedConnection? = primaryTrainingConnectionId?.let(connections::get)

    private fun isConnected(managed: ManagedConnection): Boolean =
        managed.stateMachine.current().phase == ConnectionPhase.CONNECTED &&
            managed.session.connection.isOpen()

    private fun closeConnection(deviceConnection: DeviceConnectionSession) {
        try {
            deviceConnection.close()
        } catch (exception: Exception) {
            logger.warn("Failed to close device connection", exception)
        }
    }
}

private fun ConnectionStateMachine.transitionIfPossible(event: ConnectionEvent) {
    runCatching { transition(event) }
}
