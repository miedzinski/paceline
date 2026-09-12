package paceline.device.adapters

import org.springframework.stereotype.Component
import paceline.device.adapters.bluetooth.BluetoothDeviceTransport
import paceline.device.adapters.wifi.WifiDeviceTransport
import paceline.device.domain.DeviceAdvertisement
import paceline.device.domain.DeviceEndpoint
import paceline.device.ports.DeviceCommunication
import paceline.device.ports.DeviceConnectionSession

@Component
class DeviceTransportRouter(
    private val wifi: WifiDeviceTransport,
    private val bluetooth: BluetoothDeviceTransport,
) : DeviceCommunication {
    override fun connect(device: DeviceAdvertisement): DeviceConnectionSession =
        when (device.endpoint) {
            is DeviceEndpoint.Wifi -> wifi.connect(device)
            is DeviceEndpoint.Bluetooth -> bluetooth.connect(device)
        }
}
