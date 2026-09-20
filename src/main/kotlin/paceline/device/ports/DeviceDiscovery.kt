package paceline.device.ports

import paceline.device.domain.DeviceDiscoveryCandidate
import paceline.device.domain.DiscoveryFailureCode

sealed interface DeviceDiscoveryResult {
    data class Found(
        val candidates: List<DeviceDiscoveryCandidate>,
    ) : DeviceDiscoveryResult {
        init {
            require(candidates.isNotEmpty()) { "Device discovery must return at least one candidate" }
        }
    }

    data object NotFound : DeviceDiscoveryResult

    data class Failed(
        val code: DiscoveryFailureCode,
        val message: String,
    ) : DeviceDiscoveryResult
}

fun interface WifiDiscovery {
    fun discover(): DeviceDiscoveryResult
}

fun interface BluetoothDiscovery {
    fun discover(): DeviceDiscoveryResult
}
