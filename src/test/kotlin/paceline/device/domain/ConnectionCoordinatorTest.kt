package paceline.device.domain

import org.awaitility.Awaitility
import paceline.device.domain.HeartRateTelemetry
import paceline.device.ports.DeviceCapability
import paceline.device.ports.DeviceCommunicationException
import paceline.device.ports.DeviceConnectionSession
import paceline.device.ports.DeviceDiscoveryResult
import paceline.testsupport.FakeBluetoothDiscovery
import paceline.testsupport.FakeDeviceCommunication
import paceline.testsupport.FakeDeviceConnection
import paceline.testsupport.FakeHeartRateTelemetrySource
import paceline.testsupport.FakeIndoorBikePowerControl
import paceline.testsupport.FakeWifiDiscovery
import paceline.testsupport.kickrCore2Candidate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConnectionCoordinatorTest {
    private val candidate = kickrCore2Candidate()
    private val device = candidate.toDeviceAdvertisement()

    private fun coordinator(
        communication: FakeDeviceCommunication = FakeDeviceCommunication(),
        wifiResult: DeviceDiscoveryResult = DeviceDiscoveryResult.Found(listOf(candidate)),
        bluetoothResult: DeviceDiscoveryResult = DeviceDiscoveryResult.NotFound,
    ): ConnectionCoordinator =
        ConnectionCoordinator(
            wifiDiscovery = FakeWifiDiscovery(wifiResult),
            bluetoothDiscovery = FakeBluetoothDiscovery(bluetoothResult),
            communication = communication,
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
        assertEquals(ConnectionPhase.READY, coordinator.current().phase)
    }

    @Test
    fun `discovery lists device advertisements without connecting`() {
        // given a discoverable device advertisement and a fake communication port:
        val communication = FakeDeviceCommunication()
        val coordinator = coordinator(communication = communication)

        // when the user requests available devices:
        val result = coordinator.discover()

        // then discovery reports the advertisement while no connection is opened:
        assertEquals(ConnectionPhase.DISCOVERED, result.state.phase)
        assertEquals(device, result.devices.single().device)
        assertEquals(emptyList(), communication.connectedDevices)
        assertEquals(ConnectionPhase.DISCOVERED, coordinator.current().phase)
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
                            ConnectionFailureCode.DISCOVERY_ERROR,
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
        assertEquals(ConnectionPhase.DISCOVERED, result.state.phase)
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
        assertEquals(ConnectionPhase.CONNECTED, coordinator.current().phase)

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
        val firstControl = FakeIndoorBikePowerControl()
        val secondControl = FakeIndoorBikePowerControl()
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
        val connectionId = coordinator.connectedDevices().single().id
        firstConnection?.loseConnection()

        // when the active trainer connection is recovered:
        assertEquals(ConnectionPhase.DISCONNECTED, coordinator.current().phase)
        val recoveredControl = coordinator.reconnectPrimaryTrainingConnection().toCompletableFuture().join()

        // then the replacement exposes the same logical connection and its new control capability:
        assertEquals(secondControl, recoveredControl)
        assertEquals(ConnectionPhase.CONNECTED, coordinator.current().phase)
        assertEquals(connectionId, coordinator.connectedDevices().single().id)
        assertEquals(2, communication.connectedDevices.size)
    }

    @Test
    fun `manual connection wins over an in-flight automatic recovery`() {
        // given an automatic recovery whose transport call is still blocked:
        val firstControl = FakeIndoorBikePowerControl()
        val automaticControl = FakeIndoorBikePowerControl()
        val manualControl = FakeIndoorBikePowerControl()
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
        val connectionId = coordinator.connectedDevices().single().id
        communication.connections.single().connection.let { connection ->
            (connection as FakeDeviceConnection).loseConnection()
        }
        val recovery = coordinator.reconnectPrimaryTrainingConnection()
        assertTrue(recoveryStarted.await(1, TimeUnit.SECONDS))

        // when the rider scans and connects manually before automatic recovery returns:
        val manualState = coordinator.connect(deviceId)
        releaseRecovery.countDown()
        recovery.toCompletableFuture().join()

        // then the manual connection remains primary and the stale automatic result is closed:
        assertEquals(ConnectionPhase.CONNECTED, manualState.phase)
        assertTrue(connectionId != coordinator.connectedDevices().single().id)
        assertEquals(manualControl, coordinator.currentPowerControl())
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
        assertEquals(ConnectionPhase.CONNECTED, refreshed.state.phase)
        assertEquals(device, refreshed.devices.single().device)
        assertEquals(ConnectionPhase.CONNECTED, coordinator.current().phase)
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
        assertEquals(ConnectionPhase.CONNECTED, refreshed.state.phase)
        assertEquals(ConnectionFailureCode.NO_DEVICE_FOUND, refreshed.failure?.code)
        assertEquals(ConnectionPhase.CONNECTED, coordinator.current().phase)
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
        assertEquals(2, coordinator.connectedDevices().size)
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
        val powerControl = FakeIndoorBikePowerControl()
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
        val sources = coordinator.heartRateSources()
        val trainerSourceId = sources.single { it.device.name == device.name }.id
        val strapSourceId = sources.single { it.device.name == secondCandidate.name }.id
        trainerHeartRate.emit(HeartRateTelemetry(148, Instant.parse("2026-09-14T12:00:00Z")))
        strapHeartRate.emit(HeartRateTelemetry(152, Instant.parse("2026-09-14T12:00:01Z")))

        // then each source remains addressable without replacing the trainer control connection:
        assertEquals(2, sources.size)
        assertEquals(148, coordinator.currentHeartRate(trainerSourceId)?.heartRateBpm)
        assertEquals(152, coordinator.currentHeartRate(strapSourceId)?.heartRateBpm)
        assertEquals(powerControl, coordinator.currentPowerControl())
        assertEquals(2, coordinator.connectedDevices().size)
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
        assertEquals(ConnectionPhase.DISCOVERED, result.state.phase)
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
        assertEquals("Unknown device", result.device?.name)
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
        assertEquals(ConnectionPhase.UNAVAILABLE, result.state.phase)
        assertEquals(ConnectionFailureCode.NO_DEVICE_FOUND, result.state.failure?.code)
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
