package paceline.device.domain

import java.time.Instant

enum class DiscoveryPhase {
    READY,
    DISCOVERING,
    DISCOVERED,
    UNAVAILABLE,
    FAILED,
}

enum class ConnectionPhase {
    CONNECTING,
    CONNECTED,
    FAILED,
    DISCONNECTED,
}

enum class ConnectionFailureCode {
    CONNECTION_FAILED,
    CONNECTION_LOST,
}

enum class DiscoveryFailureCode {
    NO_DEVICE_FOUND,
    DISCOVERY_TIMEOUT,
    DISCOVERY_ERROR,
}

data class ConnectionFailure(
    val code: ConnectionFailureCode,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Failure message must not be blank" }
    }
}

data class DiscoveryFailure(
    val code: DiscoveryFailureCode,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Failure message must not be blank" }
    }
}

data class DiscoveryState(
    val phase: DiscoveryPhase,
    val failure: DiscoveryFailure? = null,
    val changedAt: Instant,
) {
    companion object {
        fun ready(now: Instant): DiscoveryState =
            DiscoveryState(
                phase = DiscoveryPhase.READY,
                changedAt = now,
            )
    }
}

data class ConnectionState(
    val phase: ConnectionPhase,
    val device: DeviceAdvertisement,
    val failure: ConnectionFailure? = null,
    val changedAt: Instant,
) {
    companion object {
        fun connecting(
            device: DeviceAdvertisement,
            now: Instant,
        ): ConnectionState =
            ConnectionState(
                phase = ConnectionPhase.CONNECTING,
                device = device,
                changedAt = now,
            )
    }
}
