package paceline.device.domain

data class AdvertisementOption(
    val id: String,
    val device: DeviceAdvertisement,
)

data class DiscoverySnapshot(
    val state: ConnectionState,
    val devices: List<AdvertisementOption>,
    val failure: ConnectionFailure? = null,
)
