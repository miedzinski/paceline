package paceline.device.config

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS

@Configuration(proxyBeanMethods = false)
class JmDnsConfiguration {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(JmDNS::class)
    fun jmDns(): JmDNS {
        val address = preferredIpv4Address()
        logger.info("Starting mDNS discovery on {}", address.hostAddress)
        return JmDNS.create(address)
    }

    private fun preferredIpv4Address(): InetAddress =
        defaultRouteIpv4Address()
            ?: NetworkInterface
                .getNetworkInterfaces()
                .asSequence()
                .filter { networkInterface ->
                    networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isVirtual
                }.flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
            ?: InetAddress.getLocalHost()

    private fun defaultRouteIpv4Address(): InetAddress? =
        try {
            DatagramSocket().use { socket ->
                // UDP connect selects the OS route without sending a packet.
                socket.connect(InetSocketAddress("1.1.1.1", 53))
                socket.localAddress.let { address ->
                    address.takeIf { it is Inet4Address && !it.isAnyLocalAddress }
                }
            }
        } catch (_: IOException) {
            null
        }
}
