package paceline

import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.client.RestTestClient
import paceline.device.adapters.bluetooth.BluetoothAccess
import paceline.device.adapters.gatt.ftms.FtmsUuid
import paceline.device.adapters.wifi.WftnpFrame
import paceline.device.adapters.wifi.WftnpFrameCodec
import paceline.device.adapters.wifi.WftnpMessageType
import paceline.device.adapters.wifi.toWftnpBytes
import paceline.device.domain.ConnectionCoordinator
import paceline.device.domain.ConnectionPhase
import paceline.device.domain.DiscoveryPhase
import paceline.testsupport.NoopBluetoothAccess
import paceline.testsupport.TestWftnpServer
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "paceline.device.mdns-service-type=_paceline-integration._tcp.local.",
        "paceline.device.discovery-timeout=5s",
        "paceline.device.connect-timeout=1s",
        "paceline.device.wifi.protocol-timeout=1s",
    ],
)
@Import(DeviceConnectionIntegrationTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DeviceConnectionIntegrationTest {
    @LocalServerPort
    private var serverPort: Int = 0

    @Autowired
    private lateinit var coordinator: ConnectionCoordinator

    @Autowired
    private lateinit var deviceServer: TestWftnpServer

    private lateinit var restClient: RestTestClient

    @BeforeEach
    fun createRestClient() {
        restClient =
            RestTestClient
                .bindToServer()
                .baseUrl("http://127.0.0.1:$serverPort")
                .build()
    }

    @Test
    fun `application remains ready until a device operation is requested`() {
        // given the application has started:
        assertEquals(DiscoveryPhase.READY, coordinator.discoveryState().phase)

        // when the current connection endpoint is requested before discovery:
        val response = connectionResponse()

        // then no device protocol connection has been opened and the source list is empty:
        assertTrue(response.contains("\"connections\":[]"))
        assertEquals(0, deviceServer.acceptedConnections)
    }

    @Test
    fun `network discovery exposes the mDNS advertisement without opening its protocol`() {
        // given the synthetic Wahoo bridge is advertised over mDNS:
        assertEquals(0, deviceServer.acceptedConnections)

        // when the available-devices endpoint is requested:
        val response = discoverResponse()

        // then the real mDNS adapter reports the device and no TCP session is opened:
        assertTrue(response.contains("\"state\":\"DISCOVERED\""))
        assertTrue(response.contains("\"name\":\"KICKR CORE 2 Integration\""))
        assertTrue(response.contains("\"transport\":\"WIFI\""))
        assertTrue(response.contains("\"host\":\"${deviceServer.address.hostAddress}\""))
        assertEquals(0, deviceServer.acceptedConnections)
    }

    @Test
    fun `selected network advertisement opens WFTNP FTMS and publishes telemetry`() {
        // given a device returned by the real mDNS discovery adapter:
        val deviceId = deviceId(discoverResponse())

        // when the selected device is opened through the REST API:
        val openResponse = openConnectionResponse(deviceId)

        // then the real TCP, WFTNP, and FTMS layers complete the connection attempt:
        assertTrue(openResponse.contains("\"state\":\"CONNECTED\""))
        awaitConnectedAndTelemetry()

        // and the connection endpoint exposes the notification decoded by the FTMS profile:
        val response = connectionResponse()
        val connectionId = coordinator.connectionSnapshots().single().id
        assertTrue(response.contains("\"state\":\"CONNECTED\""))
        assertTrue(response.contains("\"name\":\"KICKR CORE 2 Integration\""))
        assertTrue(response.contains("\"powerWatts\":200"))
        assertTrue(response.contains("\"cadenceRpm\":90.0"))
        assertTrue(response.contains("\"speedKph\":25.0"))
        assertTrue(response.contains("\"distanceMeters\":null"))
        assertTrue(response.contains("\"heartRateBpm\":120"))
        assertTrue(response.contains("\"availability\":\"CURRENT\""))
        assertEquals(1, deviceServer.acceptedConnections)
    }

    @Test
    fun `active training session acquires control and updates the ERG target`() {
        // given a connected WFTNP trainer with an FTMS control point:
        val deviceId = deviceId(discoverResponse())
        openConnectionResponse(deviceId)

        // when a training session is started and its target is changed:
        val sessionResponse =
            restClient
                .post()
                .uri("/training-sessions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val sessionId =
            Regex("\"sessionId\":\"([^\"]+)\"")
                .find(sessionResponse)
                ?.groupValues
                ?.get(1)
                ?: error("No session id in response: $sessionResponse")
        val targetResponse =
            restClient
                .put()
                .uri("/training-sessions/$sessionId/erg-target")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"powerWatts\":300}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val stopResponse =
            restClient
                .post()
                .uri("/training-sessions/$sessionId/stop")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        // then the FTMS control procedures, including neutral Free Ride and the zero-watt stop target, are sent through the WFTNP bridge:
        assertTrue(sessionResponse.contains("\"state\":\"ACTIVE\""))
        assertTrue(sessionResponse.contains("\"controlMode\":\"FREE_RIDE\""))
        assertTrue(targetResponse.contains("\"controlMode\":\"ERG\""))
        assertTrue(targetResponse.contains("\"ergTargetPowerWatts\":300"))
        assertTrue(stopResponse.contains("\"state\":\"STOPPED\""))
        assertTrue(stopResponse.contains("\"ergTargetPowerWatts\":0"))
        assertEquals(4, deviceServer.writes.size)
        assertContentEquals(byteArrayOf(0x00), deviceServer.writes[0].second)
        assertContentEquals(byteArrayOf(0x04, 0x00, 0x00), deviceServer.writes[1].second)
        assertContentEquals(byteArrayOf(0x05, 0x2c, 0x01), deviceServer.writes[2].second)
        assertContentEquals(byteArrayOf(0x05, 0x00, 0x00), deviceServer.writes[3].second)
    }

    @Test
    fun `failed network protocol opening is visible through the connection endpoint`() {
        // given a discovered network advertisement whose synthetic server is no longer reachable:
        val deviceId = deviceId(discoverResponse())
        deviceServer.close()

        // when the selected device is opened:
        val response = openConnectionResponse(deviceId)

        // then the transport failure is returned as a failed device connection:
        assertTrue(response.contains("\"state\":\"FAILED\""))
        assertTrue(response.contains("\"code\":\"CONNECTION_FAILED\""))
        assertTrue(response.contains("Unable to open device protocol session"))
        assertEquals(ConnectionPhase.FAILED, coordinator.connectionSnapshots().single().phase)
    }

    @Test
    fun `a device id from an older discovery response cannot be opened`() {
        // given an id returned by the first discovery request:
        val firstDeviceId = deviceId(discoverResponse())

        // when discovery is requested again and the old id is submitted:
        val refreshedDeviceId = deviceId(discoverResponse())
        assertTrue(firstDeviceId != refreshedDeviceId)
        restClient
            .post()
            .uri("/devices/$firstDeviceId/connection")
            .exchange()
            .expectStatus()
            .isNotFound()

        // then no protocol connection is opened for the stale selection:
        assertEquals(0, deviceServer.acceptedConnections)
    }

    private fun awaitConnectedAndTelemetry() {
        Awaitility
            .await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted {
                assertTrue(coordinator.connectionSnapshots().any { it.phase == ConnectionPhase.CONNECTED })
                assertTrue(
                    coordinator.connectionSnapshots().any { source ->
                        coordinator.currentCyclingTelemetry(source.id) != null
                    },
                )
            }
    }

    private fun connectionResponse(): String =
        restClient
            .get()
            .uri("/devices/connections")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun discoverResponse(): String =
        restClient
            .get()
            .uri("/devices")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    private fun deviceId(response: String): String =
        Regex("\"id\":\"([^\"]+)\"")
            .find(response)
            ?.groupValues
            ?.get(1)
            ?: error("No device id in discovery response: $response")

    private fun openConnectionResponse(deviceId: String): String =
        restClient
            .post()
            .uri("/devices/$deviceId/connection")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
}

@TestConfiguration(proxyBeanMethods = false)
class DeviceConnectionIntegrationTestConfiguration {
    @Bean
    @Primary
    fun bluetoothAccess(): BluetoothAccess = NoopBluetoothAccess()

    @Bean(destroyMethod = "close")
    fun deviceServer(mdnsAddress: InetAddress): TestWftnpServer = TestWftnpServer(mdnsAddress)

    @Bean
    fun mdnsAddress(): InetAddress =
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress("1.1.1.1", 53))
            socket.localAddress
        }

    @Bean(destroyMethod = "close")
    fun mdnsAdvertiser(
        mdnsAddress: InetAddress,
        deviceServer: TestWftnpServer,
    ): JmDNS =
        JmDNS.create(mdnsAddress).also { jmDns ->
            jmDns.registerService(
                ServiceInfo.create(
                    "_paceline-integration._tcp.local.",
                    "KICKR CORE 2 Integration",
                    deviceServer.port,
                    0,
                    0,
                    mapOf("serial-number" to "integration"),
                ),
            )
        }

    @Bean(destroyMethod = "close")
    @Primary
    fun controlledJmDns(mdnsAddress: InetAddress): JmDNS = JmDNS.create(mdnsAddress)
}
