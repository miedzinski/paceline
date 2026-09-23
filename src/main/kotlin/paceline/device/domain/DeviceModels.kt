package paceline.device.domain

data class DeviceDiscoveryCandidate(
    val name: String,
    val endpoint: DeviceEndpoint,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "Device discovery candidate name must not be blank" }
    }

    fun toDeviceAdvertisement(): DeviceAdvertisement =
        DeviceAdvertisement(
            name = name,
            endpoint = endpoint,
            metadata = metadata,
        )
}

enum class DeviceTransport {
    LOCAL_NETWORK,
    BLUETOOTH,
}

sealed interface DeviceEndpoint {
    val transport: DeviceTransport

    data class LocalNetwork(
        val host: String,
        val port: Int,
    ) : DeviceEndpoint {
        override val transport: DeviceTransport = DeviceTransport.LOCAL_NETWORK

        init {
            require(host.isNotBlank()) { "Local network device endpoint host must not be blank" }
            require(port in 1..65_535) { "Local network device endpoint port must be between 1 and 65535" }
        }
    }

    data class Bluetooth(
        val address: String,
        val adapterAddress: String,
    ) : DeviceEndpoint {
        override val transport: DeviceTransport = DeviceTransport.BLUETOOTH

        init {
            require(address.isNotBlank()) { "Bluetooth device endpoint address must not be blank" }
            require(adapterAddress.isNotBlank()) { "Bluetooth device endpoint adapter address must not be blank" }
        }
    }
}

data class DeviceAdvertisement(
    val name: String,
    val endpoint: DeviceEndpoint,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "Device name must not be blank" }
    }
}
