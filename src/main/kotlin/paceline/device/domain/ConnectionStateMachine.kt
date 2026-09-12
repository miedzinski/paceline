package paceline.device.domain

import java.time.Clock
import java.time.Instant

sealed interface ConnectionEvent {
    data object BeginDiscovery : ConnectionEvent

    data class DeviceDiscovered(
        val device: DeviceAdvertisement,
    ) : ConnectionEvent

    data class BeginConnection(
        val device: DeviceAdvertisement,
    ) : ConnectionEvent

    data object ConnectionEstablished : ConnectionEvent

    data class DiscoveryUnavailable(
        val message: String,
    ) : ConnectionEvent

    data class DiscoveryFailed(
        val code: ConnectionFailureCode,
        val message: String,
    ) : ConnectionEvent

    data class ConnectionFailed(
        val message: String,
    ) : ConnectionEvent

    data class ConnectionLost(
        val message: String,
    ) : ConnectionEvent
}

class InvalidConnectionTransition(
    state: ConnectionState,
    event: ConnectionEvent,
) : IllegalStateException("Cannot apply $event while device is ${state.phase}")

class ConnectionStateMachine(
    private val clock: Clock = Clock.systemUTC(),
    initialState: ConnectionState? = null,
) {
    private var current: ConnectionState = initialState ?: ConnectionState.ready(clock.instant())

    @Synchronized
    fun current(): ConnectionState = current

    @Synchronized
    fun transition(event: ConnectionEvent): ConnectionState {
        val next =
            when (event) {
                ConnectionEvent.BeginDiscovery -> {
                    when (current.phase) {
                        ConnectionPhase.READY,
                        ConnectionPhase.DISCOVERED,
                        ConnectionPhase.UNAVAILABLE,
                        ConnectionPhase.FAILED,
                        ConnectionPhase.DISCONNECTED,
                        -> state(ConnectionPhase.DISCOVERING)

                        else -> invalid(event)
                    }
                }

                is ConnectionEvent.DeviceDiscovered -> {
                    when (current.phase) {
                        ConnectionPhase.DISCOVERING -> {
                            state(
                                phase = ConnectionPhase.DISCOVERED,
                                device = event.device,
                            )
                        }

                        else -> {
                            invalid(event)
                        }
                    }
                }

                is ConnectionEvent.BeginConnection -> {
                    when (current.phase) {
                        ConnectionPhase.DISCOVERED,
                        ConnectionPhase.FAILED,
                        ConnectionPhase.DISCONNECTED,
                        -> state(ConnectionPhase.CONNECTING, device = event.device)

                        else -> invalid(event)
                    }
                }

                ConnectionEvent.ConnectionEstablished -> {
                    when (current.phase) {
                        ConnectionPhase.CONNECTING -> {
                            state(ConnectionPhase.CONNECTED, device = current.device)
                        }

                        else -> {
                            invalid(event)
                        }
                    }
                }

                is ConnectionEvent.DiscoveryUnavailable -> {
                    when (current.phase) {
                        ConnectionPhase.DISCOVERING -> {
                            state(
                                phase = ConnectionPhase.UNAVAILABLE,
                                failure =
                                    ConnectionFailure(
                                        code = ConnectionFailureCode.NO_DEVICE_FOUND,
                                        message = event.message,
                                    ),
                            )
                        }

                        else -> {
                            invalid(event)
                        }
                    }
                }

                is ConnectionEvent.DiscoveryFailed -> {
                    when (current.phase) {
                        ConnectionPhase.DISCOVERING -> {
                            state(
                                phase = ConnectionPhase.FAILED,
                                failure = ConnectionFailure(event.code, event.message),
                            )
                        }

                        else -> {
                            invalid(event)
                        }
                    }
                }

                is ConnectionEvent.ConnectionFailed -> {
                    when (current.phase) {
                        ConnectionPhase.CONNECTING -> {
                            state(
                                phase = ConnectionPhase.FAILED,
                                device = current.device,
                                failure =
                                    ConnectionFailure(
                                        code = ConnectionFailureCode.CONNECTION_FAILED,
                                        message = event.message,
                                    ),
                            )
                        }

                        else -> {
                            invalid(event)
                        }
                    }
                }

                is ConnectionEvent.ConnectionLost -> {
                    when (current.phase) {
                        ConnectionPhase.CONNECTED -> {
                            state(
                                phase = ConnectionPhase.DISCONNECTED,
                                device = current.device,
                                failure =
                                    ConnectionFailure(
                                        code = ConnectionFailureCode.CONNECTION_LOST,
                                        message = event.message,
                                    ),
                            )
                        }

                        else -> {
                            invalid(event)
                        }
                    }
                }
            }

        current = next
        return current
    }

    private fun state(
        phase: ConnectionPhase,
        device: DeviceAdvertisement? = null,
        failure: ConnectionFailure? = null,
    ): ConnectionState =
        ConnectionState(
            phase = phase,
            device = device,
            failure = failure,
            changedAt = Instant.now(clock),
        )

    private fun invalid(event: ConnectionEvent): Nothing = throw InvalidConnectionTransition(current, event)
}
