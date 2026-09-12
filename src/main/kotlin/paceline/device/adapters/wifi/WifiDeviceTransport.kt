package paceline.device.adapters.wifi

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.adapters.profiles.ftms.FtmsDeviceConnection
import paceline.device.config.DeviceProperties
import paceline.device.domain.DeviceAdvertisement
import paceline.device.domain.DeviceEndpoint
import paceline.device.ports.DeviceCommunicationException
import paceline.device.ports.DeviceConnectionSession
import java.net.InetSocketAddress
import java.net.Socket

@Component
class WifiDeviceTransport(
    private val properties: DeviceProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun connect(device: DeviceAdvertisement): DeviceConnectionSession {
        val endpoint =
            device.endpoint as? DeviceEndpoint.Wifi
                ?: throw DeviceCommunicationException(
                    "Device ${device.name} does not have a Wi-Fi endpoint",
                )
        val socket = Socket()
        var protocolClient: WftnpClient? = null
        try {
            socket.connect(
                InetSocketAddress(endpoint.host, endpoint.port),
                properties.connectTimeout
                    .toMillis()
                    .coerceIn(1L, Int.MAX_VALUE.toLong())
                    .toInt(),
            )
            socket.keepAlive = true
            socket.tcpNoDelay = true

            protocolClient =
                WftnpClient(
                    input = socket.getInputStream(),
                    output = socket.getOutputStream(),
                    requestTimeout = properties.protocolTimeout,
                ).also(WftnpClient::start)

            val connection =
                FtmsDeviceConnection(
                    device = device,
                    gattClient = WftnpGattClient(protocolClient) { socket.close() },
                )
            return DeviceConnectionSession(
                connection = connection,
                capabilities = listOf(connection),
            ).also {
                logger.info(
                    "Opened device protocol session to {} at {}:{} via Wi-Fi",
                    device.name,
                    endpoint.host,
                    endpoint.port,
                )
            }
        } catch (exception: Exception) {
            protocolClient?.close()
            try {
                socket.close()
            } catch (_: Exception) {
                // Preserve the protocol failure as the useful diagnostic.
            }
            logger.warn(
                "Unable to open device protocol session to {} at {}:{} via Wi-Fi",
                device.name,
                endpoint.host,
                endpoint.port,
                exception,
            )
            throw DeviceCommunicationException(
                "Unable to open device protocol session to ${device.name} at " +
                    "${endpoint.host}:${endpoint.port}: ${exception.message}",
                exception,
            )
        }
    }
}
