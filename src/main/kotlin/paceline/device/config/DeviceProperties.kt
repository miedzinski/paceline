package paceline.device.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "paceline.device")
data class DeviceProperties(
    val mdnsServiceType: String = "_wahoo-fitness-tnp._tcp.local.",
    val discoveryTimeout: Duration = Duration.ofSeconds(5),
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val bluetooth: BluetoothProperties = BluetoothProperties(),
    val wifi: WifiProperties = WifiProperties(),
    val bluetoothAdapter: String? = null,
)

data class BluetoothProperties(
    val gattServiceDiscoveryTimeout: Duration = Duration.ofSeconds(10),
)

data class WifiProperties(
    val protocolTimeout: Duration = Duration.ofSeconds(3),
)
