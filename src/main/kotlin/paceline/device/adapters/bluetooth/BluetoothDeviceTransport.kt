package paceline.device.adapters.bluetooth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import paceline.device.adapters.profiles.ftms.FtmsDeviceConnection
import paceline.device.domain.DeviceAdvertisement
import paceline.device.domain.DeviceEndpoint
import paceline.device.ports.DeviceCommunicationException
import paceline.device.ports.DeviceConnectionSession

@Component
class BluetoothDeviceTransport(
    private val bluetooth: BluetoothAccess,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun connect(device: DeviceAdvertisement): DeviceConnectionSession {
        val endpoint =
            device.endpoint as? DeviceEndpoint.Bluetooth
                ?: throw DeviceCommunicationException(
                    "Device ${device.name} does not have a Bluetooth endpoint",
                )
        return try {
            val connection =
                FtmsDeviceConnection(
                    device = device,
                    gattClient = bluetooth.connect(endpoint),
                )
            DeviceConnectionSession(
                connection = connection,
                capabilities = listOf(connection),
            ).also {
                logger.info(
                    "Opened device protocol session to {} at {} via Bluetooth LE",
                    device.name,
                    endpoint.address,
                )
            }
        } catch (exception: Exception) {
            logger.warn(
                "Unable to open device protocol session to {} at {} via Bluetooth LE",
                device.name,
                endpoint.address,
                exception,
            )
            throw DeviceCommunicationException(
                "Unable to open device protocol session to ${device.name} at " +
                    "${endpoint.address}: ${exception.message}",
                exception,
            )
        }
    }
}
