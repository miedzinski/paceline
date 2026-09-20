package paceline.device.adapters.wifi

import io.mockk.every
import io.mockk.mockk
import paceline.device.config.DeviceProperties
import paceline.device.domain.DeviceEndpoint
import paceline.device.domain.DiscoveryFailureCode
import paceline.device.ports.DeviceDiscoveryResult
import java.io.IOException
import java.time.Duration
import java.util.Collections
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JmDnsDeviceDiscoveryTest {
    @Test
    fun `maps returned Wahoo TNP advertisements to discovery candidates`() {
        // given mDNS service records returned by JmDNS:
        val jmDns = mockk<JmDNS>()
        val deviceService =
            serviceInfo(
                qualifiedName = "KICKR CORE 77AB._wahoo-fitness-tnp._tcp.local.",
                server = "KICKR-CORE-77AB.local.",
                port = 36866,
                addresses = listOf("192.168.1.45"),
                txt =
                    mapOf(
                        "serial-number" to "253045635",
                        "mac-address" to "FC-01-2C-63-86-B8",
                        "ble-service-uuids" to "0x1818,0x1826,0xFC82",
                    ),
            )
        val unrelatedService =
            serviceInfo(
                qualifiedName = "Unrelated TNP service._wahoo-fitness-tnp._tcp.local.",
                server = "other.local.",
                port = 12345,
                addresses = emptyList(),
            )
        every { jmDns.list(any<String>(), any<Long>()) } returns arrayOf(deviceService, unrelatedService)
        val properties = DeviceProperties(discoveryTimeout = Duration.ofSeconds(1))

        // when the discovery adapter browses for devices:
        val result = JmDnsDeviceDiscovery(jmDns, properties).discover()
        val found = assertIs<DeviceDiscoveryResult.Found>(result)

        // then it translates every record without applying device-selection policy:
        assertEquals(2, found.candidates.size)
        assertEquals("KICKR CORE 77AB", found.candidates[0].name)
        val endpoint = assertIs<DeviceEndpoint.Wifi>(found.candidates[0].endpoint)
        assertEquals("192.168.1.45", endpoint.host)
        assertEquals(36866, endpoint.port)
        assertEquals("253045635", found.candidates[0].metadata["serial-number"])
        assertEquals("Unrelated TNP service", found.candidates[1].name)
    }

    @Test
    fun `returns not found when JmDNS returns no services`() {
        // given JmDNS with no service records:
        val jmDns = mockk<JmDNS>()
        every { jmDns.list(any<String>(), any<Long>()) } returns emptyArray()

        // when discovery is requested:
        val result = JmDnsDeviceDiscovery(jmDns, DeviceProperties()).discover()

        // then discovery reports that no service was found:
        assertIs<DeviceDiscoveryResult.NotFound>(result)
    }

    @Test
    fun `converts JmDNS errors into a discovery failure`() {
        // given JmDNS that cannot browse the network:
        val jmDns = mockk<JmDNS>()
        every { jmDns.list(any<String>(), any<Long>()) } throws IOException("multicast unavailable")

        // when discovery is requested:
        val result = JmDnsDeviceDiscovery(jmDns, DeviceProperties()).discover()
        val failed = assertIs<DeviceDiscoveryResult.Failed>(result)

        // then the technical error is exposed through the discovery port:
        assertEquals(DiscoveryFailureCode.DISCOVERY_ERROR, failed.code)
        assertEquals("multicast unavailable", failed.message)
    }

    private fun serviceInfo(
        qualifiedName: String,
        server: String,
        port: Int,
        addresses: List<String>,
        txt: Map<String, String> = emptyMap(),
    ): ServiceInfo {
        val service = mockk<ServiceInfo>()
        every { service.qualifiedName } returns qualifiedName
        every { service.server } returns server
        every { service.port } returns port
        every { service.hostAddresses } returns addresses.toTypedArray()
        every { service.propertyNames } returns Collections.enumeration(txt.keys)
        txt.forEach { (key, value) ->
            every { service.getPropertyString(key) } returns value
        }
        return service
    }
}
