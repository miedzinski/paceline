package paceline.device.adapters.lan

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.config.DeviceProperties
import paceline.device.domain.DeviceDiscoveryCandidate
import paceline.device.domain.DeviceEndpoint
import paceline.device.domain.DiscoveryFailureCode
import paceline.device.ports.DeviceDiscoveryResult
import paceline.device.ports.LocalNetworkDiscovery
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

@Component
class JmDnsDeviceDiscovery(
    private val jmDns: JmDNS,
    private val properties: DeviceProperties,
) : LocalNetworkDiscovery {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun discover(): DeviceDiscoveryResult = discover { }

    override fun discover(onCandidate: (DeviceDiscoveryCandidate) -> Unit): DeviceDiscoveryResult {
        val serviceType = canonicalServiceType(properties.mdnsServiceType)
        val services = ConcurrentHashMap<String, ServiceInfo>()
        val listener =
            object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    runCatching {
                        jmDns.requestServiceInfo(event.type, event.name, true)
                    }.onFailure { exception ->
                        logger.debug("Could not request mDNS service details for {}", event.name, exception)
                    }
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    services.remove(event.name)
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val service = event.info ?: return
                    services[event.name] = service
                    runCatching { toCandidate(service) }
                        .onSuccess { candidate ->
                            logger.info("Found device advertisement {} via mDNS", candidate.name)
                            onCandidate(candidate)
                        }.onFailure { exception ->
                            logger.debug("Could not translate mDNS service {}", event.name, exception)
                        }
                }
            }

        return try {
            jmDns.addServiceListener(serviceType, listener)
            Thread.sleep(properties.discoveryTimeout.toMillis().coerceAtLeast(1L))
            val candidates = services.values.map(::toCandidate)
            if (candidates.isEmpty()) {
                DeviceDiscoveryResult.NotFound
            } else {
                DeviceDiscoveryResult.Found(candidates)
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            DeviceDiscoveryResult.Failed(
                code = DiscoveryFailureCode.DISCOVERY_ERROR,
                message = "mDNS discovery was interrupted",
            )
        } catch (exception: Exception) {
            DeviceDiscoveryResult.Failed(
                code = DiscoveryFailureCode.DISCOVERY_ERROR,
                message = exception.message ?: "mDNS discovery failed",
            )
        } finally {
            runCatching { jmDns.removeServiceListener(serviceType, listener) }
        }
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
                DeviceEndpoint.LocalNetwork(
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
