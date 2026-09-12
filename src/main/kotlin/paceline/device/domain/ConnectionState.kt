package paceline.device.domain

import java.time.Instant

enum class ConnectionPhase {
    READY,
    DISCOVERING,
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    UNAVAILABLE,
    FAILED,
    DISCONNECTED,
}

enum class ConnectionFailureCode {
    NO_DEVICE_FOUND,
    DISCOVERY_TIMEOUT,
    DISCOVERY_ERROR,
    CONNECTION_FAILED,
    CONNECTION_LOST,
}

data class ConnectionFailure(
    val code: ConnectionFailureCode,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Failure message must not be blank" }
    }
}

data class ConnectionState(
    val phase: ConnectionPhase,
    val device: DeviceAdvertisement? = null,
    val failure: ConnectionFailure? = null,
    val changedAt: Instant,
) {
    companion object {
        fun ready(now: Instant): ConnectionState =
            ConnectionState(
                phase = ConnectionPhase.READY,
                changedAt = now,
            )
    }
}
