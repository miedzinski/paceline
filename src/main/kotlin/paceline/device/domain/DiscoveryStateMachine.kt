package paceline.device.domain

import java.time.Clock
import java.time.Instant

sealed interface DiscoveryEvent {
    data object Begin : DiscoveryEvent

    data object DevicesFound : DiscoveryEvent

    data class Unavailable(
        val message: String,
    ) : DiscoveryEvent

    data class Failed(
        val code: DiscoveryFailureCode,
        val message: String,
    ) : DiscoveryEvent
}

class InvalidDiscoveryTransition(
    state: DiscoveryState,
    event: DiscoveryEvent,
) : IllegalStateException("Cannot apply $event while discovery is ${state.phase}")

class DiscoveryStateMachine(
    private val clock: Clock = Clock.systemUTC(),
) {
    private var current = DiscoveryState.ready(clock.instant())

    @Synchronized
    fun current(): DiscoveryState = current

    @Synchronized
    fun transition(event: DiscoveryEvent): DiscoveryState {
        val next =
            when (event) {
                DiscoveryEvent.Begin -> {
                    when (current.phase) {
                        DiscoveryPhase.READY,
                        DiscoveryPhase.DISCOVERED,
                        DiscoveryPhase.UNAVAILABLE,
                        DiscoveryPhase.FAILED,
                        -> state(DiscoveryPhase.DISCOVERING)

                        DiscoveryPhase.DISCOVERING -> invalid(event)
                    }
                }

                DiscoveryEvent.DevicesFound -> {
                    when (current.phase) {
                        DiscoveryPhase.DISCOVERING -> state(DiscoveryPhase.DISCOVERED)
                        else -> invalid(event)
                    }
                }

                is DiscoveryEvent.Unavailable -> {
                    when (current.phase) {
                        DiscoveryPhase.DISCOVERING -> {
                            state(
                                phase = DiscoveryPhase.UNAVAILABLE,
                                failure = DiscoveryFailure(DiscoveryFailureCode.NO_DEVICE_FOUND, event.message),
                            )
                        }

                        else -> {
                            invalid(event)
                        }
                    }
                }

                is DiscoveryEvent.Failed -> {
                    when (current.phase) {
                        DiscoveryPhase.DISCOVERING -> {
                            state(
                                phase = DiscoveryPhase.FAILED,
                                failure = DiscoveryFailure(event.code, event.message),
                            )
                        }

                        else -> {
                            invalid(event)
                        }
                    }
                }
            }
        current = next
        return next
    }

    private fun state(
        phase: DiscoveryPhase,
        failure: DiscoveryFailure? = null,
    ): DiscoveryState =
        DiscoveryState(
            phase = phase,
            failure = failure,
            changedAt = Instant.now(clock),
        )

    private fun invalid(event: DiscoveryEvent): Nothing = throw InvalidDiscoveryTransition(current, event)
}
