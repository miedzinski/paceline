package paceline.device.ports

import paceline.device.domain.DeviceAdvertisement

fun interface DeviceCommunication {
    @Throws(DeviceCommunicationException::class)
    fun connect(device: DeviceAdvertisement): DeviceConnectionSession
}

class DeviceCommunicationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
