package paceline.training.domain

import org.springframework.stereotype.Component
import paceline.device.ports.IndoorBikePowerControl
import paceline.training.ports.TrainingDevice
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
class TrainingSessionCoordinator(
    private val trainingDevice: TrainingDevice,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var state = TrainingSessionState.notStarted(clock.instant())
    private var activePowerControl: IndoorBikePowerControl? = null

    @Synchronized
    fun current(): TrainingSessionState = state

    @Synchronized
    fun start(): TrainingSessionState {
        if (state.phase == TrainingSessionPhase.ACTIVE) {
            throw TrainingSessionAlreadyActiveException()
        }

        val powerControl =
            trainingDevice.currentPowerControl()
                ?: throw TrainingSessionUnavailableException(
                    "A connected device with ERG power control is required to start a training session",
                )
        try {
            powerControl.requestControl()
        } catch (exception: Exception) {
            throw TrainingSessionUnavailableException(
                "The connected device did not grant ERG control",
                exception,
            )
        }

        activePowerControl = powerControl
        state = TrainingSessionState.active(UUID.randomUUID(), clock.instant())
        return state
    }

    @Synchronized
    fun setTargetPower(
        sessionId: UUID,
        powerWatts: Int,
    ): TrainingSessionState {
        val activeState =
            when {
                state.phase != TrainingSessionPhase.ACTIVE -> throw TrainingSessionNotActiveException()
                state.sessionId != sessionId -> throw TrainingSessionMismatchException(sessionId)
                else -> state
            }
        require(powerWatts in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()) {
            "ERG target power must fit the FTMS signed 16-bit watt field"
        }

        val powerControl =
            activePowerControl
                ?: throw TrainingSessionUnavailableException(
                    "The active training session no longer has a connected ERG power-control device",
                )
        try {
            powerControl.setTargetPower(powerWatts)
        } catch (exception: Exception) {
            throw TrainingSessionUnavailableException(
                "The connected device rejected the ERG target power",
                exception,
            )
        }

        state =
            activeState.copy(
                changedAt = Instant.now(clock),
                ergTargetPowerWatts = powerWatts,
            )
        return state
    }

    @Synchronized
    fun stop(sessionId: UUID): TrainingSessionState {
        val activeState =
            when {
                state.phase != TrainingSessionPhase.ACTIVE -> throw TrainingSessionNotActiveException()
                state.sessionId != sessionId -> throw TrainingSessionMismatchException(sessionId)
                else -> state
            }

        val powerControl =
            activePowerControl
                ?: throw TrainingSessionUnavailableException(
                    "The active training session no longer has a connected ERG power-control device",
                )
        try {
            powerControl.setTargetPower(0)
        } catch (exception: Exception) {
            throw TrainingSessionUnavailableException(
                "The connected device rejected the 0 W stop target",
                exception,
            )
        }

        state =
            activeState.copy(
                phase = TrainingSessionPhase.STOPPED,
                changedAt = clock.instant(),
                ergTargetPowerWatts = 0,
            )
        activePowerControl = null
        return state
    }
}
