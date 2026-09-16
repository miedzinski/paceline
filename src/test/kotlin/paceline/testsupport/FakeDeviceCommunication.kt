package paceline.testsupport

import paceline.device.domain.DeviceAdvertisement
import paceline.device.ports.DeviceCommunication
import paceline.device.ports.DeviceConnection
import paceline.device.ports.DeviceConnectionSession
import java.util.concurrent.atomic.AtomicBoolean

class FakeDeviceCommunication(
    private val connectionFactory: (DeviceAdvertisement) -> DeviceConnectionSession = {
        DeviceConnectionSession(FakeDeviceConnection(it))
    },
) : DeviceCommunication {
    val connectedDevices = mutableListOf<DeviceAdvertisement>()
    val connections = mutableListOf<DeviceConnectionSession>()

    override fun connect(device: DeviceAdvertisement): DeviceConnectionSession =
        connectionFactory(device).also {
            connectedDevices += device
            connections += it
        }
}

class FakeDeviceConnection(
    override val device: DeviceAdvertisement,
) : DeviceConnection {
    private val open = AtomicBoolean(true)

    override fun isOpen(): Boolean = open.get()

    override fun close() {
        open.set(false)
    }

    fun loseConnection() {
        open.set(false)
    }
}
