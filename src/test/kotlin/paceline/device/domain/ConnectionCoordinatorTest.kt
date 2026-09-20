package paceline.device.domain

import org.awaitility.Awaitility
import paceline.config.TelemetryProperties
import paceline.device.ports.DeviceCapability
import paceline.device.ports.DeviceCommunicationException
import paceline.device.ports.DeviceConnectionSession
import paceline.device.ports.DeviceDiscoveryResult
import paceline.device.ports.TrainerControl
import paceline.testsupport.FakeBluetoothDiscovery
import paceline.testsupport.FakeCyclingTelemetrySource
import paceline.testsupport.FakeDeviceCommunication
import paceline.testsupport.FakeDeviceConnection
import paceline.testsupport.FakeHeartRateTelemetrySource
import paceline.testsupport.FakeTrainerControl
import paceline.testsupport.FakeWifiDiscovery
import paceline.testsupport.kickrCore2Candidate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionCoordinatorTest {
    private val candidate = kickrCore2Candidate()
    private val device = candidate.toDeviceAdvertisement()

    private fun coordinator(
        communication: FakeDeviceCommunication = FakeDeviceCommunication(),
        wifiResult: DeviceDiscoveryResult = DeviceDiscoveryResult.Found(listOf(candidate)),
        bluetoothResult: DeviceDiscoveryResult = DeviceDiscoveryResult.NotFound,
        clock: Clock = Clock.systemUTC(),
        telemetryProperties: TelemetryProperties = TelemetryProperties(),
    ): ConnectionCoordinator =
        ConnectionCoordinator(
            wifiDiscovery = FakeWifiDiscovery(wifiResult),
            bluetoothDiscovery = FakeBluetoothDiscovery(bluetoothResult),
            communication = communication,
            clock = clock,
            telemetryProperties = telemetryProperties,
        )

    @Test
    fun `does not discover or connect until requested`() {
        // given a coordinator with a discoverable device advertisement:
        val wifiDiscovery = FakeWifiDiscovery(DeviceDiscoveryResult.Found(listOf(candidate)))
        val bluetoothDiscovery = FakeBluetoothDiscovery(DeviceDiscoveryResult.NotFound)
        val communication = FakeDeviceCommunication()
        val coordinator =
            ConnectionCoordinator(
                wifiDiscovery = wifiDiscovery,
                bluetoothDiscovery = bluetoothDiscovery,
                communication = communication,
            )

        // when the application has started but no user action has happened:
        // then discovery and communication remain untouched:
        assertEquals(0, wifiDiscovery.calls)
        assertEquals(0, bluetoothDiscovery.calls)
        assertEquals(emptyList(), communication.connectedDevices)
        assertEquals(DiscoveryPhase.READY, coordinator.discoveryState().phase)
    }

    @Test
    fun `discovery lists device advertisements without connecting`() {
        // given a discoverable device advertisement and a fake communication port:
        val communication = FakeDeviceCommunication()
        val coordinator = coordinator(communication = communication)

        // when the user requests available devices:
        val result = coordinator.discover()

        // then discovery reports the advertisement while no connection is opened:
        assertEquals(DiscoveryPhase.DISCOVERED, result.state.phase)
        assertEquals(device, result.devices.single().device)
        assertEquals(emptyList(), communication.connectedDevices)
        assertEquals(DiscoveryPhase.DISCOVERED, coordinator.discoveryState().phase)
    }

    @Test
    fun `discovery combines wifi and Bluetooth source candidates`() {
        // given both discovery sources have candidates:
        val bluetoothCandidate =
            kickrCore2Candidate(name = "KICKR CORE BLE").copy(
                endpoint =
                    DeviceEndpoint.Bluetooth(
                        "AA:BB:CC:DD:EE:FF",
                        "11:22:33:44:55:66",
                    ),
            )
        val communication = FakeDeviceCommunication()
        val coordinator =
            ConnectionCoordinator(
                wifiDiscovery = FakeWifiDiscovery(DeviceDiscoveryResult.Found(listOf(candidate))),
                bluetoothDiscovery =
                    FakeBluetoothDiscovery(DeviceDiscoveryResult.Found(listOf(bluetoothCandidate))),
                communication = communication,
            )

        // when the user requests available devices:
        val result = coordinator.discover()

        // then both transport candidates are returned without opening communication:
        assertEquals(
            listOf(device, bluetoothCandidate.toDeviceAdvertisement()),
            result.devices.map { it.device },
        )
        assertEquals(emptyList(), communication.connectedDevices)
    }

    @Test
    fun `discovery keeps Bluetooth candidates when wifi discovery fails`() {
        // given a Wi-Fi discovery failure and a successful Bluetooth discovery:
        val bluetoothCandidate =
            kickrCore2Candidate(name = "KICKR CORE BLE").copy(
                endpoint =
                    DeviceEndpoint.Bluetooth(
                        "AA:BB:CC:DD:EE:FF",
                        "11:22:33:44:55:66",
                    ),
            )
        val communication = FakeDeviceCommunication()
        val coordinator =
            ConnectionCoordinator(
                wifiDiscovery =
                    FakeWifiDiscovery(
                        DeviceDiscoveryResult.Failed(
                            DiscoveryFailureCode.DISCOVERY_ERROR,
                            "multicast unavailable",
                        ),
                    ),
                bluetoothDiscovery =
                    FakeBluetoothDiscovery(DeviceDiscoveryResult.Found(listOf(bluetoothCandidate))),
                communication = communication,
            )

        // when the user requests available devices:
        val result = coordinator.discover()

        // then the available Bluetooth candidate is returned:
        assertEquals(DiscoveryPhase.DISCOVERED, result.state.phase)
        assertEquals(listOf(bluetoothCandidate.toDeviceAdvertisement()), result.devices.map { it.device })
        assertEquals(emptyList(), communication.connectedDevices)
    }

    @Test
    fun `connecting opens the selected device and reaches connected`() {
        // given a discovered device advertisement:
        val communication = FakeDeviceCommunication()
        val coordinator = coordinator(communication = communication)
        val discovery = coordinator.discover()

        // when the user opens the selected device connection:
        val result = coordinator.connect(discovery.devices.single().id)

        // then the selected device reaches the connected state:
        assertEquals(ConnectionPhase.CONNECTED, result.phase)
        assertEquals(device, result.device)
        assertEquals(listOf(device), communication.connectedDevices)
        assertEquals(ConnectionPhase.CONNECTED, coordinator.connectionSnapshots().single().phase)

        coordinator.close()
        assertEquals(
            false,
            communication.connections
                .single()
                .connection
                .isOpen(),
        )
    }

    @Test
    fun `connecting to the same open device is idempotent`() {
        // given a discovered device with an open connection:
        val communication = FakeDeviceCommunication()
        val coordinator = coordinator(communication = communication)
        val deviceId =
            coordinator
                .discover()
                .devices
                .single()
                .id
        val firstResult = coordinator.connect(deviceId)

        // when the same device is connected again:
        val secondResult = coordinator.connect(deviceId)

        // then the existing connection is returned without opening another connection:
        assertEquals(firstResult, secondResult)
        assertEquals(listOf(device), communication.connectedDevices)
    }

    @Test
    fun `reconnects the primary trainer connection while preserving its connection id`() {
        // given a trainer connection that can be opened again after the first transport closes:
        val firstControl = FakeTrainerControl()
        val secondControl = FakeTrainerControl()
        var connectionAttempt = 0
        var firstConnection: FakeDeviceConnection? = null
        val communication =
            FakeDeviceCommunication { advertisement ->
                connectionAttempt += 1
                val connection = FakeDeviceConnection(advertisement)
                if (connectionAttempt == 1) {
                    firstConnection = connection
                    DeviceConnectionSession(connection, listOf<DeviceCapability>(firstControl))
                } else {
                    DeviceConnectionSession(connection, listOf<DeviceCapability>(secondControl))
                }
            }
        val coordinator = coordinator(communication = communication)
        val deviceId =
            coordinator
                .discover()
                .devices
                .single()
                .id
        coordinator.connect(deviceId)
        val connectionId = coordinator.connectionSnapshots().single().id
        firstConnection?.loseConnection()

        // when the active trainer connection is recovered:
        assertEquals(ConnectionPhase.DISCONNECTED, coordinator.connectionSnapshots().single().phase)
        val recoveredControl =
            coordinator
                .reconnectConnection(connectionId)
                .toCompletableFuture()
                .join()
                ?.capability<TrainerControl>()

        // then the replacement exposes the same logical connection and its new control capability:
        assertEquals(secondControl, recoveredControl)
        assertEquals(ConnectionPhase.CONNECTED, coordinator.connectionSnapshots().single().phase)
        assertEquals(connectionId, coordinator.connectionSnapshots().single().id)
        assertEquals(2, communication.connectedDevices.size)
    }

    @Test
    fun `manual connection wins over an in-flight automatic recovery`() {
        // given an automatic recovery whose transport call is still blocked:
        val firstControl = FakeTrainerControl()
        val automaticControl = FakeTrainerControl()
        val manualControl = FakeTrainerControl()
        val recoveryStarted = CountDownLatch(1)
        val releaseRecovery = CountDownLatch(1)
        var connectionAttempt = 0
        val communication =
            FakeDeviceCommunication { advertisement ->
                connectionAttempt += 1
                when (connectionAttempt) {
                    1 -> {
                        DeviceConnectionSession(FakeDeviceConnection(advertisement), listOf<DeviceCapability>(firstControl))
                    }

                    2 -> {
                        recoveryStarted.countDown()
                        releaseRecovery.await(1, TimeUnit.SECONDS)
                        DeviceConnectionSession(FakeDeviceConnection(advertisement), listOf<DeviceCapability>(automaticControl))
                    }

                    else -> {
                        DeviceConnectionSession(FakeDeviceConnection(advertisement), listOf<DeviceCapability>(manualControl))
                    }
                }
            }
        val coordinator = coordinator(communication = communication)
        val deviceId =
            coordinator
                .discover()
                .devices
                .single()
                .id
        coordinator.connect(deviceId)
        val connectionId = coordinator.connectionSnapshots().single().id
        communication.connections.single().connection.let { connection ->
            (connection as FakeDeviceConnection).loseConnection()
        }
        val recovery = coordinator.reconnectConnection(connectionId)
        assertTrue(recoveryStarted.await(1, TimeUnit.SECONDS))

        // when the rider scans and connects manually before automatic recovery returns:
        val manualState = coordinator.connect(deviceId)
        releaseRecovery.countDown()
        recovery.toCompletableFuture().join()

        // then the manual connection remains primary and the stale automatic result is closed:
        assertEquals(ConnectionPhase.CONNECTED, manualState.phase)
        assertTrue(connectionId != coordinator.connectionSnapshots().single().id)
        val manualConnectionId = coordinator.connectionSnapshots().single().id
        assertEquals(manualControl, coordinator.currentTrainerControl(manualConnectionId))
        Awaitility.await().atMost(Duration.ofSeconds(1)).untilAsserted {
            assertEquals(
                false,
                communication.connections
                    .last()
                    .connection
                    .isOpen(),
            )
        }
    }

    @Test
    fun `refreshes discovery while preserving an active connection`() {
        // given a device with an active connection and discovery sources that can be queried again:
        val wifiDiscovery = FakeWifiDiscovery(DeviceDiscoveryResult.Found(listOf(candidate)))
        val bluetoothDiscovery = FakeBluetoothDiscovery(DeviceDiscoveryResult.NotFound)
        val communication = FakeDeviceCommunication()
        val coordinator =
            ConnectionCoordinator(
                wifiDiscovery = wifiDiscovery,
                bluetoothDiscovery = bluetoothDiscovery,
                communication = communication,
            )
        val deviceId =
            coordinator
                .discover()
                .devices
                .single()
                .id
        coordinator.connect(deviceId)

        // when available devices are requested again:
        val refreshed = coordinator.discover()

        // then discovery runs again without replacing the active connection:
        assertEquals(2, wifiDiscovery.calls)
        assertEquals(DiscoveryPhase.DISCOVERED, refreshed.state.phase)
        assertEquals(device, refreshed.devices.single().device)
        assertEquals(ConnectionPhase.CONNECTED, coordinator.connectionSnapshots().single().phase)
        assertEquals(listOf(device), communication.connectedDevices)
    }

    @Test
    fun `reports a failed refresh without losing the active connection state`() {
        // given a device with an active connection and a failed next discovery:
        val wifiDiscovery = FakeWifiDiscovery(DeviceDiscoveryResult.Found(listOf(candidate)))
        val bluetoothDiscovery = FakeBluetoothDiscovery(DeviceDiscoveryResult.NotFound)
        val communication = FakeDeviceCommunication()
        val coordinator =
            ConnectionCoordinator(
                wifiDiscovery = wifiDiscovery,
                bluetoothDiscovery = bluetoothDiscovery,
                communication = communication,
            )
        val deviceId =
            coordinator
                .discover()
                .devices
                .single()
                .id
        coordinator.connect(deviceId)
        wifiDiscovery.result = DeviceDiscoveryResult.NotFound

        // when discovery is refreshed:
        val refreshed = coordinator.discover()

        // then the discovery failure is visible without changing the live connection state:
        assertEquals(DiscoveryPhase.UNAVAILABLE, refreshed.state.phase)
        assertEquals(DiscoveryFailureCode.NO_DEVICE_FOUND, refreshed.failure?.code)
        assertEquals(ConnectionPhase.CONNECTED, coordinator.connectionSnapshots().single().phase)
        assertEquals(listOf(device), communication.connectedDevices)
    }

    @Test
    fun `opening a second device connection keeps the first one active`() {
        // given two discovered devices and one active connection:
        val secondCandidate =
            kickrCore2Candidate(
                name = "KICKR CORE 88CD",
                host = "192.168.1.46",
            )
        val communication = FakeDeviceCommunication()
        val coordinator =
            coordinator(
                wifiResult = DeviceDiscoveryResult.Found(listOf(candidate, secondCandidate)),
                communication = communication,
            )
        val devices = coordinator.discover().devices
        coordinator.connect(devices.first().id)

        // when the second device is selected:
        val secondResult = coordinator.connect(devices[1].id)

        // then both connections are open and the first connection remains available:
        assertEquals(ConnectionPhase.CONNECTED, secondResult.phase)
        assertEquals(listOf(device, secondCandidate.toDeviceAdvertisement()), communication.connectedDevices)
        assertEquals(2, coordinator.connectionSnapshots().size)
    }

    @Test
    fun `multiple trainers remain independently addressable without an implicit source selection`() {
        // given two discovered trainers with separate telemetry and control capabilities:
        val secondCandidate =
            kickrCore2Candidate(
                name = "KICKR CORE 88CD",
                host = "192.168.1.46",
                metadata = mapOf("serial-number" to "253045636"),
            )
        val firstTelemetry = FakeCyclingTelemetrySource()
        val secondTelemetry = FakeCyclingTelemetrySource()
        val firstControl = FakeTrainerControl()
        val secondControl = FakeTrainerControl()
        val communication =
            FakeDeviceCommunication { advertisement ->
                if (advertisement.name == device.name) {
                    DeviceConnectionSession(
                        connection = FakeDeviceConnection(advertisement),
                        capabilities = listOf(firstTelemetry, firstControl),
                    )
                } else {
                    DeviceConnectionSession(
                        connection = FakeDeviceConnection(advertisement),
                        capabilities = listOf(secondTelemetry, secondControl),
                    )
                }
            }
        val coordinator =
            coordinator(
                wifiResult = DeviceDiscoveryResult.Found(listOf(candidate, secondCandidate)),
                communication = communication,
            )
        val devices = coordinator.discover().devices
        coordinator.connect(devices[0].id)
        coordinator.connect(devices[1].id)
        val snapshots = coordinator.connectionSnapshots()
        val firstId = snapshots.single { it.device.name == device.name }.id
        val secondId = snapshots.single { it.device.name == secondCandidate.name }.id

        // when observations and listeners address each source by its connection ID:
        val firstObservation =
            CyclingTelemetry(
                powerWatts = 210,
                cadenceRpm = 88.0,
                receivedAt = Instant.parse("2026-09-14T12:00:00Z"),
            )
        val secondObservation =
            CyclingTelemetry(
                powerWatts = 250,
                cadenceRpm = 92.0,
                receivedAt = Instant.parse("2026-09-14T12:00:01Z"),
            )
        firstTelemetry.emit(firstObservation)
        secondTelemetry.emit(secondObservation)
        val receivedFromSecond = mutableListOf<CyclingTelemetry>()
        val registration = coordinator.addCyclingTelemetryListener(secondId) { telemetry -> receivedFromSecond += telemetry }
        secondTelemetry.emit(secondObservation.copy(speedKph = 31.0))

        // then each source keeps its own capabilities and data without a global fallback:
        assertEquals(firstObservation, coordinator.currentCyclingTelemetry(firstId))
        assertEquals(secondObservation.copy(speedKph = 31.0), coordinator.currentCyclingTelemetry(secondId))
        assertEquals(firstControl, coordinator.currentTrainerControl(firstId))
        assertEquals(secondControl, coordinator.currentTrainerControl(secondId))
        assertEquals(listOf(secondObservation.copy(speedKph = 31.0)), receivedFromSecond)
        registration.close()
    }

    @Test
    fun `combines separate cycling profiles from one physical connection without rewriting raw samples`() {
        // given one connected source whose session exposes independent power and cadence profiles:
        val powerSource = FakeCyclingTelemetrySource(setOf(CyclingMeasurement.POWER))
        val cadenceSource = FakeCyclingTelemetrySource(setOf(CyclingMeasurement.CADENCE))
        val communication =
            FakeDeviceCommunication {
                DeviceConnectionSession(
                    connection = FakeDeviceConnection(it),
                    capabilities = listOf(powerSource, cadenceSource),
                )
            }
        val coordinator =
            coordinator(
                communication = communication,
                clock = Clock.fixed(Instant.parse("2026-09-14T12:00:02Z"), ZoneOffset.UTC),
            )
        val deviceId =
            coordinator
                .discover()
                .devices
                .single()
                .id
        coordinator.connect(deviceId)
        val connectionId = coordinator.connectionSnapshots().single().id
        val received = mutableListOf<CyclingTelemetry>()
        val registration = coordinator.addCyclingTelemetryListener(connectionId) { received += it }

        // when the separate profiles publish sparse notifications:
        val power = CyclingTelemetry(powerWatts = 250, receivedAt = Instant.parse("2026-09-14T12:00:00Z"))
        val cadence = CyclingTelemetry(cadenceRpm = 91.0, receivedAt = Instant.parse("2026-09-14T12:00:01Z"))
        powerSource.emit(power)
        cadenceSource.emit(cadence)

        // then current state is a convenience projection while raw listeners see each sparse record unchanged:
        val snapshot = coordinator.connectionSnapshots().single()
        assertEquals(setOf(DeviceCapabilityType.CYCLING_TELEMETRY), snapshot.capabilities)
        assertEquals(TelemetryAvailability.CURRENT, snapshot.telemetry.availability)
        assertEquals(cadence.receivedAt, snapshot.telemetry.lastReceivedAt)
        assertEquals(
            CyclingTelemetry(
                powerWatts = 250,
                cadenceRpm = 91.0,
                receivedAt = cadence.receivedAt,
            ),
            coordinator.currentCyclingTelemetry(connectionId),
        )
        assertEquals(listOf(power, cadence), received)

        // when the physical connection is subsequently lost:
        (communication.connections.single().connection as FakeDeviceConnection).loseConnection()
        val interrupted = coordinator.connectionSnapshots().single()

        // then the projection keeps the receipt timestamp but withholds the stale sample:
        assertEquals(TelemetryAvailability.INTERRUPTED, interrupted.telemetry.availability)
        assertNull(interrupted.telemetry.sample)
        assertEquals(cadence.receivedAt, interrupted.telemetry.lastReceivedAt)
        registration.close()
    }

    @Test
    fun `publishes a measurement learned after connection setup and retains it after loss`() {
        // given a connected telemetry source that has not exposed any measurement yet:
        val telemetry = FakeCyclingTelemetrySource(emptySet())
        val communication =
            FakeDeviceCommunication {
                DeviceConnectionSession(
                    connection = FakeDeviceConnection(it),
                    capabilities = listOf(telemetry),
                )
            }
        val coordinator = coordinator(communication = communication)
        val deviceId =
            coordinator
                .discover()
                .devices
                .single()
                .id
        val connected = coordinator.connect(deviceId)

        // when the source learns that it provides cadence after the connection is published:
        assertEquals(emptySet(), connected.capabilities)
        telemetry.expose(CyclingMeasurement.CADENCE)

        // then the live connection projection exposes cadence:
        val updated = coordinator.connectionSnapshots().single()
        assertEquals(setOf(DeviceCapabilityType.CADENCE_TELEMETRY), updated.capabilities)

        // when the same connection is subsequently lost:
        communication.connections.single().connection.let { connection ->
            (connection as FakeDeviceConnection).loseConnection()
        }

        // then the disconnected record retains the last confirmed capability:
        val disconnected = coordinator.connectionSnapshots().single()
        assertEquals(ConnectionPhase.DISCONNECTED, disconnected.phase)
        assertEquals(setOf(DeviceCapabilityType.CADENCE_TELEMETRY), disconnected.capabilities)
    }

    @Test
    fun `losing one source keeps the other connection open without fallback`() {
        // given two connected trainer sources:
        val secondCandidate =
            kickrCore2Candidate(
                name = "KICKR CORE 88CD",
                host = "192.168.1.46",
                metadata = mapOf("serial-number" to "253045636"),
            )
        val firstControl = FakeTrainerControl()
        val secondControl = FakeTrainerControl()
        val communication =
            FakeDeviceCommunication { advertisement ->
                val control = if (advertisement.name == device.name) firstControl else secondControl
                DeviceConnectionSession(
                    connection = FakeDeviceConnection(advertisement),
                    capabilities = listOf<DeviceCapability>(control),
                )
            }
        val coordinator =
            coordinator(
                wifiResult = DeviceDiscoveryResult.Found(listOf(candidate, secondCandidate)),
                communication = communication,
            )
        val devices = coordinator.discover().devices
        coordinator.connect(devices[0].id)
        coordinator.connect(devices[1].id)
        val firstId = coordinator.connectionSnapshots().first { it.device.name == device.name }.id
        val secondId = coordinator.connectionSnapshots().first { it.device.name == secondCandidate.name }.id
        (communication.connections[0].connection as FakeDeviceConnection).loseConnection()

        // when the device layer refreshes the per-source lifecycle:
        val sources = coordinator.connectionSnapshots()

        // then only the lost source becomes unavailable, the other remains open, and no global fallback is selected:
        assertEquals(ConnectionPhase.DISCONNECTED, sources.single { it.id == firstId }.phase)
        assertEquals(ConnectionPhase.CONNECTED, sources.single { it.id == secondId }.phase)
        assertNull(coordinator.currentTrainerControl(firstId))
        assertEquals(secondControl, coordinator.currentTrainerControl(secondId))
        assertTrue((communication.connections[1].connection as FakeDeviceConnection).isOpen())
    }

    @Test
    fun `capabilities are published only after connection setup succeeds`() {
        // given a discovered device whose first connection attempt fails before a session exists:
        var shouldFail = true
        val telemetry = FakeCyclingTelemetrySource()
        val control = FakeTrainerControl()
        val communication =
            FakeDeviceCommunication { advertisement ->
                if (shouldFail) {
                    throw DeviceCommunicationException("profile initialization failed")
                }
                DeviceConnectionSession(
                    connection = FakeDeviceConnection(advertisement),
                    capabilities = listOf<DeviceCapability>(telemetry, control),
                )
            }
        val coordinator = coordinator(communication = communication)
        val deviceId =
            coordinator
                .discover()
                .devices
                .single()
                .id

        // when the connection attempt fails, before a successful profile initialization:
        val failed = coordinator.connect(deviceId)

        // then no connection or capability is exposed as authoritative:
        assertEquals(ConnectionPhase.FAILED, failed.phase)
        assertEquals(1, coordinator.connectionSnapshots().size)
        assertTrue(
            coordinator
                .connectionSnapshots()
                .single()
                .capabilities
                .isEmpty(),
        )
        val failedConnectionId = failed.id

        // when the same discovered device completes setup successfully:
        shouldFail = false
        val connected = coordinator.connect(deviceId)

        // then its capabilities appear only with the connected source record:
        assertEquals(ConnectionPhase.CONNECTED, connected.phase)
        assertEquals(failedConnectionId, connected.id)
        val source = coordinator.connectionSnapshots().single()
        assertEquals(
            setOf(DeviceCapabilityType.CYCLING_TELEMETRY, DeviceCapabilityType.ERG_POWER_CONTROL),
            source.capabilities,
        )
    }

    @Test
    fun `recovery replaces only the requested connection and preserves its source id`() {
        // given two connected trainers where the first trainer can be opened again:
        val secondCandidate =
            kickrCore2Candidate(
                name = "KICKR CORE 88CD",
                host = "192.168.1.46",
                metadata = mapOf("serial-number" to "253045636"),
            )
        val firstControl = FakeTrainerControl()
        val recoveredControl = FakeTrainerControl()
        val secondControl = FakeTrainerControl()
        val attempts = mutableMapOf<String, Int>()
        val communication =
            FakeDeviceCommunication { advertisement ->
                val attempt = attempts.merge(advertisement.name, 1, Int::plus) ?: 1
                val control =
                    when {
                        advertisement.name != device.name -> secondControl
                        attempt == 1 -> firstControl
                        else -> recoveredControl
                    }
                DeviceConnectionSession(
                    connection = FakeDeviceConnection(advertisement),
                    capabilities = listOf<DeviceCapability>(control),
                )
            }
        val coordinator =
            coordinator(
                wifiResult = DeviceDiscoveryResult.Found(listOf(candidate, secondCandidate)),
                communication = communication,
            )
        val devices = coordinator.discover().devices
        coordinator.connect(devices[0].id)
        coordinator.connect(devices[1].id)
        val firstId = coordinator.connectionSnapshots().first { it.device.name == device.name }.id
        val secondId = coordinator.connectionSnapshots().first { it.device.name == secondCandidate.name }.id
        (communication.connections[0].connection as FakeDeviceConnection).loseConnection()

        // when only the first source is recovered:
        val recoveredSession = coordinator.reconnectConnection(firstId).toCompletableFuture().join()

        // then the first logical source ID is preserved and the second source was not replaced or closed:
        assertEquals(recoveredControl, recoveredSession?.capability<TrainerControl>())
        assertEquals(firstId, coordinator.connectionSnapshots().first { it.device.name == device.name }.id)
        assertEquals(secondId, coordinator.connectionSnapshots().first { it.device.name == secondCandidate.name }.id)
        assertTrue((communication.connections[1].connection as FakeDeviceConnection).isOpen())
        assertEquals(3, communication.connectedDevices.size)
    }

    @Test
    fun `exposes heart-rate capabilities from the trainer and a separate device connection`() {
        // given a trainer bridge and a separate heart-rate device:
        val secondCandidate =
            kickrCore2Candidate(
                name = "Heart Rate Strap",
                host = "192.168.1.47",
            )
        val trainerHeartRate = FakeHeartRateTelemetrySource()
        val strapHeartRate = FakeHeartRateTelemetrySource()
        val powerControl = FakeTrainerControl()
        val communication =
            FakeDeviceCommunication { advertisement ->
                if (advertisement.name == device.name) {
                    DeviceConnectionSession(
                        connection = FakeDeviceConnection(advertisement),
                        capabilities = listOf<DeviceCapability>(powerControl, trainerHeartRate),
                    )
                } else {
                    DeviceConnectionSession(
                        connection = FakeDeviceConnection(advertisement),
                        capabilities = listOf(strapHeartRate),
                    )
                }
            }
        val coordinator =
            coordinator(
                wifiResult = DeviceDiscoveryResult.Found(listOf(candidate, secondCandidate)),
                communication = communication,
            )
        val deviceOptions = coordinator.discover().devices

        // when both physical connections are opened:
        coordinator.connect(deviceOptions[0].id)
        coordinator.connect(deviceOptions[1].id)
        val sources =
            coordinator
                .connectionSnapshots()
                .filter { DeviceCapabilityType.HEART_RATE in it.capabilities }
        val trainerSourceId = sources.single { it.device.name == device.name }.id
        val strapSourceId = sources.single { it.device.name == secondCandidate.name }.id
        trainerHeartRate.emit(HeartRateTelemetry(148, Instant.parse("2026-09-14T12:00:00Z")))
        strapHeartRate.emit(HeartRateTelemetry(152, Instant.parse("2026-09-14T12:00:01Z")))

        // then each source remains addressable without replacing the trainer control connection:
        assertEquals(2, sources.size)
        assertEquals(148, coordinator.currentHeartRate(trainerSourceId)?.heartRateBpm)
        assertEquals(152, coordinator.currentHeartRate(strapSourceId)?.heartRateBpm)
        val updatedSources = coordinator.connectionSnapshots().associateBy { it.id }
        assertEquals(
            Instant.parse("2026-09-14T12:00:00Z"),
            updatedSources.getValue(trainerSourceId).heartRate.lastReceivedAt,
        )
        assertEquals(
            Instant.parse("2026-09-14T12:00:01Z"),
            updatedSources.getValue(strapSourceId).heartRate.lastReceivedAt,
        )
        val trainerConnectionId = coordinator.connectionSnapshots().first { it.device.name == device.name }.id
        assertEquals(powerControl, coordinator.currentTrainerControl(trainerConnectionId))
        assertEquals(2, coordinator.connectionSnapshots().size)
    }

    @Test
    fun `discovery exposes every matching service advertisement`() {
        // given a discovery result containing an unknown device advertisement:
        val communication =
            FakeDeviceCommunication {
                error("connection should not be attempted")
            }
        val coordinator =
            coordinator(
                wifiResult =
                    DeviceDiscoveryResult.Found(
                        listOf(
                            DeviceDiscoveryCandidate(
                                name = "Kitchen speaker",
                                endpoint = DeviceEndpoint.Wifi("192.168.1.20", 80),
                            ),
                        ),
                    ),
                communication = communication,
            )

        // when available devices are requested:
        val result = coordinator.discover()

        // then the advertisement is returned without model filtering or connecting:
        assertEquals(DiscoveryPhase.DISCOVERED, result.state.phase)
        assertEquals(
            "Kitchen speaker",
            result.devices
                .single()
                .device.name,
        )
        assertEquals(emptyList(), communication.connectedDevices)
    }

    @Test
    fun `connecting passes an unknown service advertisement to communication`() {
        // given an unknown device advertisement that the user selected:
        val candidate =
            DeviceDiscoveryCandidate(
                name = "Unknown device",
                endpoint = DeviceEndpoint.Wifi("192.168.1.20", 80),
            )
        val communication = FakeDeviceCommunication()
        val coordinator =
            coordinator(
                wifiResult = DeviceDiscoveryResult.Found(listOf(candidate)),
                communication = communication,
            )
        val deviceId =
            coordinator
                .discover()
                .devices
                .single()
                .id

        // when the user opens the selected device connection:
        val result = coordinator.connect(deviceId)

        // then the endpoint is attempted without a model allowlist:
        assertEquals(ConnectionPhase.CONNECTED, result.phase)
        assertEquals("Unknown device", result.device.name)
        assertEquals("Unknown device", communication.connectedDevices.single().name)
    }

    @Test
    fun `discovery reports no device without attempting a connection`() {
        // given an empty discovery result:
        val communication =
            FakeDeviceCommunication {
                error("connection should not be attempted")
            }
        val coordinator = coordinator(wifiResult = DeviceDiscoveryResult.NotFound, communication = communication)

        // when available devices are requested:
        val result = coordinator.discover()

        // then the device is unavailable and communication is untouched:
        assertEquals(DiscoveryPhase.UNAVAILABLE, result.state.phase)
        assertEquals(DiscoveryFailureCode.NO_DEVICE_FOUND, result.state.failure?.code)
        assertEquals(
            "No device was found on the configured network or Bluetooth transports",
            result.state.failure?.message,
        )
        assertEquals(emptyList(), communication.connectedDevices)
    }

    @Test
    fun `opening a device connection reports communication failures`() {
        // given a discoverable device advertisement and a communication failure:
        val communication =
            FakeDeviceCommunication {
                throw DeviceCommunicationException("Connection refused")
            }
        val coordinator = coordinator(communication = communication)
        val deviceOption = coordinator.discover().devices.single()

        // when the selected device connection is opened:
        val result = coordinator.connect(deviceOption.id)

        // then the connection failure is exposed with its diagnostic message:
        assertEquals(ConnectionPhase.FAILED, result.phase)
        assertEquals(ConnectionFailureCode.CONNECTION_FAILED, result.failure?.code)
        assertEquals("Connection refused", result.failure?.message)
    }
}
