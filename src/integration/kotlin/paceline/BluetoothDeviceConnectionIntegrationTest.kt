package paceline

import io.mockk.every
import io.mockk.mockk
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.client.RestTestClient
import paceline.device.adapters.GattCharacteristic
import paceline.device.adapters.GattCharacteristicProperty
import paceline.device.adapters.GattService
import paceline.device.adapters.bluetooth.BluetoothDeviceCandidate
import paceline.device.adapters.profiles.ftms.FtmsUuid
import paceline.device.domain.ConnectionCoordinator
import paceline.device.domain.ConnectionPhase
import paceline.device.domain.DeviceEndpoint
import paceline.testsupport.SyntheticBluetoothAccess
import paceline.testsupport.SyntheticGattClient
import java.time.Duration
import javax.jmdns.JmDNS
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "paceline.device.mdns-service-type=_paceline-integration._tcp.local.",
        "paceline.device.discovery-timeout=1s",
        "paceline.device.connect-timeout=1s",
        "paceline.device.protocol-timeout=1s",
    ],
)
@Import(BluetoothDeviceConnectionIntegrationTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BluetoothDeviceConnectionIntegrationTest {
    @LocalServerPort
    private var serverPort: Int = 0

    @Autowired
    private lateinit var coordinator: ConnectionCoordinator

    @Autowired
    private lateinit var bluetooth: SyntheticBluetoothAccess

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
    fun `Bluetooth discovery exposes an FTMS advertisement without opening a connection`() {
        // given a synthetic Bluetooth access boundary with an FTMS advertisement:
        assertEquals(0, bluetooth.connectCalls)

        // when the available-devices endpoint is requested:
        val response = discoverResponse()

        // then the real Bluetooth discovery adapter maps it without touching a GATT connection:
        assertTrue(response.contains("\"state\":\"DISCOVERED\""))
        assertTrue(response.contains("\"name\":\"KICKR CORE 2 Bluetooth Integration\""))
        assertTrue(response.contains("\"transport\":\"BLUETOOTH\""))
        assertTrue(response.contains("\"address\":\"AA:BB:CC:DD:EE:FF\""))
        assertTrue(response.contains("\"host\":null"))
        assertEquals(
            setOf(FtmsUuid.FITNESS_MACHINE_SERVICE),
            bluetooth.requestedServiceUuids,
        )
        assertEquals(0, bluetooth.connectCalls)
    }

    @Test
    fun `selected Bluetooth advertisement opens GATT FTMS and publishes telemetry`() {
        // given a discovered Bluetooth FTMS advertisement:
        val deviceId = deviceId(discoverResponse())

        // when the selected Bluetooth device is opened through the REST API:
        val openResponse = openConnectionResponse(deviceId)

        // then the real Bluetooth transport and FTMS profile use the synthetic GATT client:
        assertTrue(openResponse.contains("\"state\":\"CONNECTED\""))
        assertEquals(1, bluetooth.connectCalls)
        assertEquals(
            DeviceEndpoint.Bluetooth("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
            bluetooth.connectedEndpoint,
        )
        assertEquals(listOf(FtmsUuid.INDOOR_BIKE_DATA), bluetooth.gattClient.enabledNotifications)

        // and an Indoor Bike Data notification is decoded by the connected session:
        bluetooth.gattClient.emit(FtmsUuid.INDOOR_BIKE_DATA, telemetryNotification())
        awaitConnectedAndTelemetry()
        val response = connectionResponse()
        assertTrue(response.contains("\"state\":\"CONNECTED\""))
        assertTrue(response.contains("\"powerWatts\":200"))
        assertTrue(response.contains("\"cadenceRpm\":90.0"))
        assertTrue(response.contains("\"speedKph\":25.0"))
    }

    @Test
    fun `Bluetooth FTMS setup failures are reported without a physical device`() {
        // given an advertisement and a synthetic GATT client with no notifiable data characteristic:
        val brokenGatt =
            SyntheticGattClient(
                listOf(
                    GattService(
                        FtmsUuid.FITNESS_MACHINE_SERVICE,
                        listOf(GattCharacteristic(FtmsUuid.INDOOR_BIKE_DATA, emptySet())),
                    ),
                ),
            )
        bluetooth.gattClient = brokenGatt
        val deviceId = deviceId(discoverResponse())

        // when the selected Bluetooth device is opened:
        val response = openConnectionResponse(deviceId)

        // then profile initialization fails through the normal connection lifecycle and closes GATT:
        assertTrue(response.contains("\"state\":\"FAILED\""))
        assertTrue(response.contains("\"code\":\"CONNECTION_FAILED\""))
        assertTrue(response.contains("notifiable FTMS Indoor Bike Data characteristic"))
        assertFalse(brokenGatt.isOpen())
    }

    @Test
    fun `Bluetooth discovery reports unavailable when no FTMS advertisement exists`() {
        // given the synthetic Bluetooth adapter has no matching advertisements:
        bluetooth.candidates = emptyList()

        // when the available-devices endpoint is requested:
        val response = discoverResponse()

        // then the application reports an unavailable device without attempting a connection:
        assertTrue(response.contains("\"state\":\"UNAVAILABLE\""))
        assertTrue(response.contains("\"code\":\"NO_DEVICE_FOUND\""))
        assertEquals(0, bluetooth.connectCalls)
    }

    private fun awaitConnectedAndTelemetry() {
        Awaitility
            .await()
            .atMost(Duration.ofSeconds(2))
            .untilAsserted {
                assertEquals(ConnectionPhase.CONNECTED, coordinator.current().phase)
                assertTrue(coordinator.currentTelemetry() != null)
            }
    }

    private fun connectionResponse(): String =
        restClient
            .get()
            .uri("/devices/connection")
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

    private fun telemetryNotification(): ByteArray =
        byteArrayOf(
            0x44,
            0x00,
            0xC4.toByte(),
            0x09,
            0xB4.toByte(),
            0x00,
            0xC8.toByte(),
            0x00,
        )
}

@TestConfiguration(proxyBeanMethods = false)
class BluetoothDeviceConnectionIntegrationTestConfiguration {
    @Bean
    @Primary
    fun bluetoothAccess(): SyntheticBluetoothAccess =
        SyntheticBluetoothAccess(
            candidates =
                listOf(
                    BluetoothDeviceCandidate(
                        name = "KICKR CORE 2 Bluetooth Integration",
                        address = "AA:BB:CC:DD:EE:FF",
                        adapterAddress = "11:22:33:44:55:66",
                        rssi = -42,
                    ),
                ),
            gattClient = notifiableGattClient(),
        )

    @Bean(destroyMethod = "close")
    @Primary
    fun controlledJmDns(): JmDNS =
        mockk<JmDNS>(relaxed = true).also { jmDns ->
            every { jmDns.list(any<String>(), any<Long>()) } returns emptyArray()
        }

    private fun notifiableGattClient(): SyntheticGattClient =
        SyntheticGattClient(
            listOf(
                GattService(
                    FtmsUuid.FITNESS_MACHINE_SERVICE,
                    listOf(
                        GattCharacteristic(
                            FtmsUuid.INDOOR_BIKE_DATA,
                            setOf(GattCharacteristicProperty.NOTIFY),
                        ),
                    ),
                ),
            ),
        )
}
