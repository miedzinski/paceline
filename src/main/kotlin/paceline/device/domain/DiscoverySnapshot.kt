package paceline.device.domain

data class AdvertisementOption(
    val id: String,
    val device: DeviceAdvertisement,
)

data class DiscoverySnapshot(
    val state: DiscoveryState,
    val devices: List<AdvertisementOption>,
    val failure: DiscoveryFailure? = null,
)
