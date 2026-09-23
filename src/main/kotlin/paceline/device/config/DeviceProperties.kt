package paceline.device.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.time.Duration

@ConfigurationProperties(prefix = "paceline.device")
data class DeviceProperties(
    val mdnsServiceType: String = "_wahoo-fitness-tnp._tcp.local.",
    val discoveryTimeout: Duration = Duration.ofSeconds(5),
    val connectTimeout: Duration = Duration.ofSeconds(3),
    @NestedConfigurationProperty val bluetooth: BluetoothProperties = BluetoothProperties(),
    @NestedConfigurationProperty val localNetwork: LocalNetworkProperties = LocalNetworkProperties(),
    val bluetoothAdapter: String? = null,
)

data class BluetoothProperties(
    val gattServiceDiscoveryTimeout: Duration = Duration.ofSeconds(10),
)

data class LocalNetworkProperties(
    val protocolTimeout: Duration = Duration.ofSeconds(3),
)
