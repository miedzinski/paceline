package paceline.device.adapters.wifi

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.config.DeviceProperties
import paceline.device.domain.DeviceDiscoveryCandidate
import paceline.device.domain.DeviceEndpoint
import paceline.device.domain.DiscoveryFailureCode
import paceline.device.ports.DeviceDiscoveryResult
import paceline.device.ports.WifiDiscovery
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

@Component
class JmDnsDeviceDiscovery(
    private val jmDns: JmDNS,
    private val properties: DeviceProperties,
) : WifiDiscovery {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun discover(): DeviceDiscoveryResult =
        try {
            val serviceType = canonicalServiceType(properties.mdnsServiceType)
            val candidates =
                jmDns
                    .list(serviceType, properties.discoveryTimeout.toMillis().coerceAtLeast(1L))
                    .map(::toCandidate)

            if (candidates.isEmpty()) {
                DeviceDiscoveryResult.NotFound
            } else {
                candidates.forEach { candidate ->
                    val endpoint = candidate.endpoint as DeviceEndpoint.Wifi
                    logger.info(
                        "Found device advertisement {} at {}:{} via mDNS",
                        candidate.name,
                        endpoint.host,
                        endpoint.port,
                    )
                }
                DeviceDiscoveryResult.Found(candidates)
            }
        } catch (exception: Exception) {
            DeviceDiscoveryResult.Failed(
                code = DiscoveryFailureCode.DISCOVERY_ERROR,
                message = exception.message ?: "mDNS discovery failed",
            )
        }

    private fun toCandidate(service: ServiceInfo): DeviceDiscoveryCandidate {
        val addresses = service.hostAddresses.toList()
        val hostName = service.server?.takeIf(String::isNotBlank) ?: addresses.firstOrNull().orEmpty()
        val metadata =
            service.propertyNames
                .asSequence()
                .associateWith { propertyName -> service.getPropertyString(propertyName).orEmpty() }
        val name =
            metadata["name"]
                ?: metadata["device_name"]
                ?: service.qualifiedName.substringBefore("._").trimEnd('.')

        return DeviceDiscoveryCandidate(
            name = name.ifBlank { hostName.trimEnd('.') },
            endpoint =
                DeviceEndpoint.Wifi(
                    host = addresses.firstOrNull() ?: hostName.trimEnd('.'),
                    port = service.port,
                ),
            metadata = metadata,
        )
    }

    private fun canonicalServiceType(serviceType: String): String {
        val trimmed = serviceType.trim().trimEnd('.')
        return if (trimmed.endsWith(".local")) "$trimmed." else "$trimmed.local."
    }
}
