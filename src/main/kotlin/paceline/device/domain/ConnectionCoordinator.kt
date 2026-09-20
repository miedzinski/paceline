package paceline.device.domain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.config.TelemetryProperties
import paceline.device.ports.BluetoothDiscovery
import paceline.device.ports.CyclingTelemetryListener
import paceline.device.ports.CyclingTelemetrySource
import paceline.device.ports.DeviceCommunication
import paceline.device.ports.DeviceConnectionSession
import paceline.device.ports.DeviceDiscoveryResult
import paceline.device.ports.HeartRateTelemetryListener
import paceline.device.ports.HeartRateTelemetrySource
import paceline.device.ports.TrainerControl
import paceline.device.ports.WifiDiscovery
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private val telemetryProperties: TelemetryProperties = TelemetryProperties(),
) : AutoCloseable {
    private data class ManagedConnection(
        val id: String,
        val stateMachine: ConnectionStateMachine,
        var session: DeviceConnectionSession? = null,
        var capabilities: Set<DeviceCapabilityType> = emptySet(),
        var lastCyclingTelemetry: CyclingTelemetry? = null,
        var lastHeartRate: HeartRateTelemetry? = null,
    ) {
        val device: DeviceAdvertisement
            get() = stateMachine.current().device
    }

    private data class ConnectionRecovery(
        val connectionId: String,
        val device: DeviceAdvertisement,
        val generation: Long,
        val future: CompletableFuture<DeviceConnectionSession?>,
    )

    private val logger = LoggerFactory.getLogger(javaClass)
    private val discoveryStateMachine = DiscoveryStateMachine(clock)
    private val discoveredDevices = linkedMapOf<String, DeviceAdvertisement>()
    private val currentDiscoveryDevices = linkedMapOf<String, DeviceAdvertisement>()
    private val connections = linkedMapOf<String, ManagedConnection>()
    private val discoveryExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "paceline-device-discovery").apply {
                isDaemon = true
            }
        }
    private val discoverySourceExecutor: ExecutorService =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "paceline-device-discovery-source").apply {
                isDaemon = true
            }
        }
    private val recoveryExecutor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "paceline-trainer-recovery").apply {
                isDaemon = true
            }
        }
    private var connectionRecoveryGeneration = 0L
    private val connectionRecoveries = linkedMapOf<String, ConnectionRecovery>()
    private val discoveryListeners = CopyOnWriteArrayList<(DiscoverySnapshot) -> Unit>()
    private var lastScanAt: Instant? = null
    private var discoveryGeneration = 0L
    private var closed = false

    @Synchronized
    fun discoveryState(): DiscoveryState = discoveryStateMachine.current()

    @Synchronized
    fun discoverySnapshot(): DiscoverySnapshot = discoverySnapshot(discoveryStateMachine.current())

    @Synchronized
    fun addDiscoveryListener(listener: (DiscoverySnapshot) -> Unit): AutoCloseable {
        listener(discoverySnapshot(discoveryStateMachine.current()))
        discoveryListeners += listener
        return AutoCloseable { discoveryListeners -= listener }
    }

    @Synchronized
    fun startDiscovery(): DiscoverySnapshot {
        refreshConnectionStates()
        val current = discoveryStateMachine.current()
        if (current.phase == DiscoveryPhase.DISCOVERING) {
            return discoverySnapshot(current)
        }

        val generation = beginDiscovery()
        val started = discoverySnapshot(discoveryStateMachine.current())
        publishDiscoverySnapshot(started)
        return try {
            discoveryExecutor.execute {
                val result = discoverResult(generation)
                synchronized(this) {
                    if (!closed && discoveryGeneration == generation) {
                        publishDiscoverySnapshot(completeDiscovery(result))
                    }
                }
            }
            started
        } catch (exception: Exception) {
            val completed =
                completeDiscovery(
                    DeviceDiscoveryResult.Failed(
                        code = DiscoveryFailureCode.DISCOVERY_ERROR,
                        message = exception.message ?: "Device discovery could not be started",
                    ),
                )
            publishDiscoverySnapshot(completed)
            completed
        }
    }

    @Synchronized
    fun connectionSnapshots(): List<ConnectionSnapshot> {
        refreshConnectionStates()
        return connections.values.map(::snapshot)
    }

    @Synchronized
    fun currentCyclingTelemetry(connectionId: String): CyclingTelemetry? {
        refreshConnectionStates()
        return connectedConnection(connectionId)?.let(::latestCyclingTelemetry)
    }

    @Synchronized
    fun currentTrainerControl(connectionId: String): TrainerControl? {
        refreshConnectionStates()
        return connectedConnection(connectionId)?.session?.capability<TrainerControl>()
    }

    @Synchronized
    fun hasCyclingTelemetry(connectionId: String): Boolean {
        refreshConnectionStates()
        return connections[connectionId]
            ?.let { managed -> isConnected(managed) && cyclingTelemetrySources(managed).isNotEmpty() }
            ?: false
    }

    @Synchronized
    fun addCyclingTelemetryListener(
        connectionId: String,
        listener: CyclingTelemetryListener,
    ): AutoCloseable {
        refreshConnectionStates()
        val sources = connectedConnection(connectionId)?.let(::cyclingTelemetrySources).orEmpty()
        if (sources.isEmpty()) {
            return AutoCloseable { }
        }

        val registrations = mutableListOf<AutoCloseable>()
        try {
            sources.forEach { source ->
                registrations += source.addTelemetryListener(listener)
            }
        } catch (exception: Exception) {
            registrations.asReversed().forEach(::closeQuietly)
            throw exception
        }
        return AutoCloseable {
            registrations.asReversed().forEach(::closeQuietly)
        }
    }

    @Synchronized
    fun currentHeartRate(sourceId: String): HeartRateTelemetry? {
        refreshConnectionStates()
        val managed = connections[sourceId] ?: return null
        if (!isConnected(managed)) {
            return null
        }
        return managed.session
            ?.capability<HeartRateTelemetrySource>()
            ?.latestHeartRate()
            ?.also { managed.lastHeartRate = it }
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
                ?.capability<HeartRateTelemetrySource>()
                ?.addHeartRateListener(listener)
                ?: AutoCloseable { }
        } else {
            AutoCloseable { }
        }
    }

    fun discover(): DiscoverySnapshot {
        val scan =
            synchronized(this) {
                refreshConnectionStates()
                val current = discoveryStateMachine.current()
                if (current.phase == DiscoveryPhase.DISCOVERING) {
                    return discoverySnapshot(current)
                }
                val generation = beginDiscovery()
                generation to discoverySnapshot(discoveryStateMachine.current())
            }
        publishDiscoverySnapshot(scan.second)
        val generation = scan.first
        val result = discoverResult(generation)
        return synchronized(this) {
            val completed = completeDiscovery(result)
            publishDiscoverySnapshot(completed)
            completed
        }
    }

    private fun beginDiscovery(): Long {
        currentDiscoveryDevices.clear()
        if (!hasFreshDiscovery()) {
            discoveredDevices.clear()
        }
        val generation = ++discoveryGeneration
        discoveryStateMachine.transition(DiscoveryEvent.Begin)
        return generation
    }

    private fun completeDiscovery(result: DeviceDiscoveryResult): DiscoverySnapshot =
        when (result) {
            is DeviceDiscoveryResult.Found -> {
                replaceDiscoveredDevices(result.candidates)
                lastScanAt = clock.instant()
                discoverySnapshot(discoveryStateMachine.transition(DiscoveryEvent.DevicesFound))
            }

            DeviceDiscoveryResult.NotFound -> {
                discoveredDevices.clear()
                currentDiscoveryDevices.clear()
                lastScanAt = clock.instant()
                discoverySnapshot(
                    discoveryStateMachine.transition(
                        DiscoveryEvent.Unavailable(
                            "No device was found on the configured network or Bluetooth transports",
                        ),
                    ),
                )
            }

            is DeviceDiscoveryResult.Failed -> {
                discoveredDevices.clear()
                currentDiscoveryDevices.clear()
                lastScanAt = null
                discoverySnapshot(
                    discoveryStateMachine.transition(DiscoveryEvent.Failed(result.code, result.message)),
                )
            }
        }

    private fun replaceDiscoveredDevices(candidates: List<DeviceDiscoveryCandidate>) {
        val currentScanDevices = currentDiscoveryDevices.entries.toList()
        discoveredDevices.clear()
        candidates
            .map(DeviceDiscoveryCandidate::toDeviceAdvertisement)
            .forEach { device ->
                val existingId =
                    currentScanDevices
                        .firstOrNull { (_, current) -> sameEndpoint(current, device) }
                        ?.key
                discoveredDevices[existingId ?: UUID.randomUUID().toString()] = device
            }
        currentDiscoveryDevices.clear()
    }

    private fun discoverResult(generation: Long): DeviceDiscoveryResult =
        try {
            discoverFromSources(generation)
        } catch (exception: Exception) {
            DeviceDiscoveryResult.Failed(
                code = DiscoveryFailureCode.DISCOVERY_ERROR,
                message = exception.message ?: "Device discovery failed",
            )
        }

    @Synchronized
    fun connect(deviceId: String): ConnectionSnapshot {
        val device =
            discoveredDevices[deviceId]
                ?: throw NotDiscoveredException(deviceId)

        invalidateRecoveryFor(device)
        refreshConnectionStates()
        val connectingConnections =
            connections.values.filter {
                sameDevice(it.device, device) &&
                    it.stateMachine.current().phase == ConnectionPhase.CONNECTING
            }
        connectingConnections.forEach(::removeConnection)
        val existing =
            connections.values.firstOrNull {
                sameDevice(it.device, device) && isConnected(it)
            }
        if (existing != null) {
            return snapshot(existing)
        }

        val managed =
            connections.values
                .lastOrNull {
                    sameDevice(it.device, device) &&
                        it.stateMachine.current().phase in
                        setOf(ConnectionPhase.FAILED, ConnectionPhase.DISCONNECTED)
                }
                ?: ManagedConnection(
                    id = UUID.randomUUID().toString(),
                    stateMachine =
                        ConnectionStateMachine(
                            clock = clock,
                            initialState = ConnectionState.connecting(device, clock.instant()),
                        ),
                ).also { connections[it.id] = it }

        if (managed.stateMachine.current().phase != ConnectionPhase.CONNECTING) {
            closeConnection(managed.session)
            managed.session = null
            managed.stateMachine.transition(ConnectionEvent.BeginConnection(device))
        }

        return try {
            val session = communication.connect(device)
            if (!session.connection.isOpen()) {
                closeConnection(session)
                throw IllegalStateException("Device communication returned a closed connection")
            }
            managed.session = session
            managed.capabilities = capabilitiesOf(session)
            managed.stateMachine.transition(ConnectionEvent.ConnectionEstablished)
            snapshot(managed)
        } catch (exception: Exception) {
            managed.session?.let(::closeConnection)
            managed.session = null
            managed.stateMachine.transition(
                ConnectionEvent.ConnectionFailed(
                    exception.message ?: "Device connection failed",
                ),
            )
            snapshot(managed)
        }
    }

    @Synchronized
    fun reconnectConnection(
        connectionId: String,
        force: Boolean = false,
    ): CompletionStage<DeviceConnectionSession?> {
        refreshConnectionStates()
        val inFlight = connectionRecoveries[connectionId]
        if (inFlight != null) {
            return inFlight.future
        }

        val managed = connections[connectionId] ?: return CompletableFuture.completedFuture(null)
        val currentState = managed.stateMachine.current()
        if (currentState.phase == ConnectionPhase.CONNECTED && managed.session?.connection?.isOpen() == true) {
            if (!force) {
                return CompletableFuture.completedFuture(managed.session)
            }
            managed.stateMachine.transition(
                ConnectionEvent.ConnectionLost(
                    "The trainer telemetry stream is stale",
                ),
            )
        }

        val device = currentState.device
        closeConnection(managed.session)
        managed.session = null
        managed.stateMachine.transition(ConnectionEvent.BeginConnection(device))

        val generation = ++connectionRecoveryGeneration
        val future = CompletableFuture<DeviceConnectionSession?>()
        connectionRecoveries[connectionId] =
            ConnectionRecovery(
                connectionId = connectionId,
                device = device,
                generation = generation,
                future = future,
            )
        try {
            recoveryExecutor.execute {
                val connectionResult = runCatching { communication.connect(device) }
                synchronized(this) {
                    val recovery = connectionRecoveries[connectionId]
                    val isCurrentAttempt =
                        recovery?.generation == generation &&
                            connections[connectionId] === managed
                    val session = connectionResult.getOrNull()
                    if (!isCurrentAttempt) {
                        session?.let(::closeConnection)
                        future.complete(null)
                    } else if (session == null) {
                        val failure =
                            connectionResult.exceptionOrNull()
                                ?: IllegalStateException("Device reconnection failed")
                        managed.stateMachine.transition(
                            ConnectionEvent.ConnectionFailed(
                                failure.message ?: "Device reconnection failed",
                            ),
                        )
                        future.completeExceptionally(failure)
                    } else if (!session.connection.isOpen()) {
                        closeConnection(session)
                        val failure = IllegalStateException("Device communication returned a closed connection")
                        managed.stateMachine.transition(
                            ConnectionEvent.ConnectionFailed(
                                failure.message ?: "Device reconnection failed",
                            ),
                        )
                        future.completeExceptionally(failure)
                    } else {
                        managed.session = session
                        managed.capabilities = capabilitiesOf(session)
                        managed.stateMachine.transition(ConnectionEvent.ConnectionEstablished)
                        future.complete(session)
                    }
                    if (connectionRecoveries[connectionId]?.generation == generation) {
                        connectionRecoveries.remove(connectionId)
                    }
                }
            }
        } catch (exception: Exception) {
            managed.stateMachine.transition(
                ConnectionEvent.ConnectionFailed(
                    exception.message ?: "Device reconnection could not be scheduled",
                ),
            )
            connectionRecoveries.remove(connectionId)
            future.completeExceptionally(exception)
        }
        return future
    }

    @Synchronized
    fun disconnect(connectionId: String) {
        val managed =
            connections[connectionId]
                ?: throw ConnectionNotFoundException(connectionId)
        invalidateRecoveryFor(managed.device, connectionId)
        closeConnection(managed.session)
        removeConnection(managed)
    }

    @Synchronized
    override fun close() {
        closed = true
        connectionRecoveries.values.forEach { recovery ->
            recovery.future.complete(null)
        }
        connectionRecoveries.clear()
        connections.values.forEach { managed -> closeConnection(managed.session) }
        connections.clear()
        discoveryExecutor.shutdownNow()
        discoverySourceExecutor.shutdownNow()
        recoveryExecutor.shutdownNow()
        discoveryListeners.clear()
    }

    private fun snapshot(managed: ManagedConnection): ConnectionSnapshot {
        refreshPublishedCapabilities(managed)
        val state = managed.stateMachine.current()
        val connected = isConnected(managed)
        val heartRate = managed.session?.capability<HeartRateTelemetrySource>()
        val cyclingTelemetry = if (connected) latestCyclingTelemetry(managed) else null
        val latestCyclingTelemetry = cyclingTelemetry ?: managed.lastCyclingTelemetry
        val latestHeartRate =
            if (connected) {
                heartRate?.latestHeartRate()?.also { managed.lastHeartRate = it }
            } else {
                null
            } ?: managed.lastHeartRate
        val now = clock.instant()
        if (cyclingTelemetry != null) {
            managed.lastCyclingTelemetry = cyclingTelemetry
        }
        return ConnectionSnapshot(
            id = managed.id,
            device = state.device,
            phase = state.phase,
            changedAt = state.changedAt,
            failure = state.failure,
            capabilities = managed.capabilities,
            telemetry =
                projectTelemetry(
                    sample = latestCyclingTelemetry,
                    connected = connected,
                    now = now,
                    freshness = telemetryProperties.freshness,
                    receivedAt = CyclingTelemetry::receivedAt,
                ),
            heartRate =
                projectTelemetry(
                    sample = latestHeartRate,
                    connected = connected,
                    now = now,
                    freshness = telemetryProperties.freshness,
                    receivedAt = HeartRateTelemetry::receivedAt,
                ),
        )
    }

    private fun connectedConnection(connectionId: String): ManagedConnection? = connections[connectionId]?.takeIf(::isConnected)

    private fun discoverFromSources(generation: Long): DeviceDiscoveryResult {
        val results =
            listOf(
                CompletableFuture.supplyAsync(
                    {
                        runDiscoverySource("Wi-Fi") { onCandidate ->
                            wifiDiscovery.discover { candidate ->
                                onCandidate(generation, candidate)
                            }
                        }
                    },
                    discoverySourceExecutor,
                ),
                CompletableFuture.supplyAsync(
                    {
                        runDiscoverySource("Bluetooth") { onCandidate ->
                            bluetoothDiscovery.discover { candidate ->
                                onCandidate(generation, candidate)
                            }
                        }
                    },
                    discoverySourceExecutor,
                ),
            ).map { future -> future.join() }
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

    private fun runDiscoverySource(
        source: String,
        operation: ((Long, DeviceDiscoveryCandidate) -> Unit) -> DeviceDiscoveryResult,
    ): DeviceDiscoveryResult =
        try {
            operation { generation, candidate -> reportDiscoveryCandidate(generation, candidate) }
        } catch (exception: Exception) {
            logger.warn("{} discovery source failed", source, exception)
            DeviceDiscoveryResult.Failed(
                code = DiscoveryFailureCode.DISCOVERY_ERROR,
                message = exception.message ?: "$source discovery failed",
            )
        }

    private fun reportDiscoveryCandidate(
        generation: Long,
        candidate: DeviceDiscoveryCandidate,
    ) {
        val snapshot =
            synchronized(this) {
                if (closed || discoveryGeneration != generation ||
                    discoveryStateMachine.current().phase != DiscoveryPhase.DISCOVERING
                ) {
                    null
                } else {
                    val device = candidate.toDeviceAdvertisement()
                    val existingId =
                        currentDiscoveryDevices.entries
                            .firstOrNull { (_, existing) -> sameEndpoint(existing, device) }
                            ?.key
                    val id = existingId ?: UUID.randomUUID().toString()
                    currentDiscoveryDevices[id] = device
                    discoveredDevices.entries.removeIf { (existingId, existing) ->
                        existingId != id && sameEndpoint(existing, device)
                    }
                    val previous = discoveredDevices.put(id, device)
                    if (previous == device) null else discoverySnapshot(discoveryStateMachine.current())
                }
            }
        snapshot?.let(::publishDiscoverySnapshot)
    }

    private fun publishDiscoverySnapshot(snapshot: DiscoverySnapshot) {
        discoveryListeners.forEach { listener ->
            runCatching { listener(snapshot) }
                .onFailure { exception ->
                    logger.debug("Discovery snapshot listener failed", exception)
                }
        }
    }

    private fun discoverySnapshot(state: DiscoveryState): DiscoverySnapshot =
        DiscoverySnapshot(
            state = state,
            devices = discoveredDevices.toOptions(),
            failure = state.failure,
            lastScanAt = lastScanAt,
        )

    private fun hasFreshDiscovery(): Boolean {
        val scanAt = lastScanAt ?: return false
        val age = Duration.between(scanAt, clock.instant())
        return !age.isNegative && age < DISCOVERY_FRESHNESS
    }

    private fun sameEndpoint(
        first: DeviceAdvertisement,
        second: DeviceAdvertisement,
    ): Boolean =
        when {
            first.endpoint is DeviceEndpoint.Wifi && second.endpoint is DeviceEndpoint.Wifi -> {
                first.endpoint.host.equals(second.endpoint.host, ignoreCase = true) &&
                    first.endpoint.port == second.endpoint.port
            }

            first.endpoint is DeviceEndpoint.Bluetooth && second.endpoint is DeviceEndpoint.Bluetooth -> {
                first.endpoint.address.equals(second.endpoint.address, ignoreCase = true) &&
                    first.endpoint.adapterAddress.equals(second.endpoint.adapterAddress, ignoreCase = true)
            }

            else -> {
                false
            }
        }

    private fun Map<String, DeviceAdvertisement>.toOptions(): List<AdvertisementOption> =
        entries.map { (id, device) ->
            AdvertisementOption(id = id, device = device)
        }

    private fun refreshConnectionStates() {
        connections.values.forEach { managed ->
            refreshPublishedCapabilities(managed)
            if (
                managed.stateMachine.current().phase == ConnectionPhase.CONNECTED &&
                managed.session?.connection?.isOpen() == false
            ) {
                managed.stateMachine.transition(
                    ConnectionEvent.ConnectionLost("The device connection is no longer open"),
                )
            }
        }
    }

    private fun refreshPublishedCapabilities(managed: ManagedConnection) {
        if (managed.stateMachine.current().phase != ConnectionPhase.CONNECTED) {
            return
        }
        managed.session?.let { session ->
            managed.capabilities = capabilitiesOf(session)
        }
    }

    private fun isConnected(managed: ManagedConnection): Boolean =
        managed.stateMachine.current().phase == ConnectionPhase.CONNECTED &&
            managed.session?.connection?.isOpen() == true

    private fun removeConnection(managed: ManagedConnection) {
        closeConnection(managed.session)
        connections.remove(managed.id)
    }

    private fun closeConnection(deviceConnection: DeviceConnectionSession?) {
        deviceConnection?.let(::closeQuietly)
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (exception: Exception) {
            logger.warn("Failed to close device connection", exception)
        }
    }

    private fun capabilitiesOf(session: DeviceConnectionSession): Set<DeviceCapabilityType> {
        val cyclingSources = session.capabilities<CyclingTelemetrySource>()
        val hasPower = cyclingSources.any { CyclingMeasurement.POWER in it.measurements }
        val hasCadence = cyclingSources.any { CyclingMeasurement.CADENCE in it.measurements }
        return buildSet {
            when {
                hasPower && hasCadence -> add(DeviceCapabilityType.CYCLING_TELEMETRY)
                hasPower -> add(DeviceCapabilityType.POWER_TELEMETRY)
                hasCadence -> add(DeviceCapabilityType.CADENCE_TELEMETRY)
            }
            if (session.capability<TrainerControl>() != null) add(DeviceCapabilityType.ERG_POWER_CONTROL)
            if (session.capability<HeartRateTelemetrySource>() != null) add(DeviceCapabilityType.HEART_RATE)
        }
    }

    private fun cyclingTelemetrySources(managed: ManagedConnection): List<CyclingTelemetrySource> =
        managed.session?.capabilities<CyclingTelemetrySource>().orEmpty()

    private fun latestCyclingTelemetry(managed: ManagedConnection): CyclingTelemetry? {
        val samples = cyclingTelemetrySources(managed).mapNotNull { it.latestTelemetry() }
        if (samples.isEmpty()) {
            return null
        }

        fun <T> latestValue(selector: (CyclingTelemetry) -> T?): T? =
            samples
                .filter { selector(it) != null }
                .maxByOrNull { it.receivedAt }
                ?.let(selector)

        return CyclingTelemetry(
            powerWatts = latestValue { it.powerWatts },
            cadenceRpm = latestValue { it.cadenceRpm },
            speedKph = latestValue { it.speedKph },
            distanceMeters = latestValue { it.distanceMeters },
            receivedAt = samples.maxOf { it.receivedAt },
        )
    }

    private fun invalidateRecoveryFor(
        device: DeviceAdvertisement,
        connectionId: String? = null,
    ) {
        val matchingRecoveries =
            connectionRecoveries.values.filter { recovery ->
                sameDevice(recovery.device, device) &&
                    (connectionId == null || recovery.connectionId == connectionId)
            }
        matchingRecoveries.forEach { recovery ->
            connectionRecoveries.remove(recovery.connectionId)
            recovery.future.complete(null)
        }
    }

    private fun sameDevice(
        first: DeviceAdvertisement,
        second: DeviceAdvertisement,
    ): Boolean {
        if (first == second) {
            return true
        }
        if (first.endpoint.transport != second.endpoint.transport) {
            return false
        }

        if (first.name.equals(second.name, ignoreCase = true)) {
            stableIdentityKeys.forEach { key ->
                val firstIdentity = first.metadata[key]?.trim()
                val secondIdentity = second.metadata[key]?.trim()
                if (!firstIdentity.isNullOrEmpty() && firstIdentity.equals(secondIdentity, ignoreCase = true)) {
                    return true
                }
            }
        }

        return when {
            first.endpoint is DeviceEndpoint.Wifi && second.endpoint is DeviceEndpoint.Wifi -> {
                first.endpoint.host.equals(second.endpoint.host, ignoreCase = true) &&
                    first.endpoint.port == second.endpoint.port
            }

            first.endpoint is DeviceEndpoint.Bluetooth && second.endpoint is DeviceEndpoint.Bluetooth -> {
                first.endpoint.address.equals(second.endpoint.address, ignoreCase = true) &&
                    first.endpoint.adapterAddress.equals(second.endpoint.adapterAddress, ignoreCase = true)
            }

            else -> {
                false
            }
        }
    }

    private companion object {
        val DISCOVERY_FRESHNESS: Duration = Duration.ofMinutes(2)
        val stableIdentityKeys =
            listOf(
                "serial-number",
                "serial_number",
                "mac-address",
                "mac_address",
                "bluetooth-address",
            )
    }
}
