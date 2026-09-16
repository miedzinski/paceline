package paceline.training.domain

import paceline.device.domain.IndoorBikeTelemetry
import paceline.device.ports.IndoorBikePowerControl
import paceline.training.ports.TrainingDevice
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class TrainerConnectionAvailability(
    val connected: Boolean,
    val recovered: Boolean,
    val status: TrainerConnectionStatus,
    val retryAttempt: Int?,
    val error: String?,
)

class TrainerConnectionManager(
    private val trainingDevice: TrainingDevice,
    private val clock: Clock,
    private val telemetryFreshness: Duration,
    private val onInterrupted: (Instant, String?, Boolean) -> Unit,
    private val onRecovered: (Instant) -> Unit,
    private val recordActivityEvent: (UUID, TrainingActivityEvent) -> Unit,
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    private var sessionId: UUID? = null
    private var activePowerControl: IndoorBikePowerControl? = null
    private var connectionInterruptedAt: Instant? = null
    private var nextConnectionRecoveryAt: Instant? = null
    private var connectionRecoveryAttempt = 0
    private var connectionRecovery: CompletionStage<IndoorBikePowerControl?>? = null
    private var targetSynchronizationPending = false
    private var targetSynchronizationAt: Instant? = null
    private var pendingZeroPowerCommand = false
    private var lastTelemetryReceivedAt: Instant? = null
    private var telemetryObserved = false
    private var connectionError: String? = null

    @Synchronized
    fun currentPowerControl(): IndoorBikePowerControl? = activePowerControl

    @Synchronized
    fun powerControlForStart(): IndoorBikePowerControl? = trainingDevice.currentPowerControl()

    @Synchronized
    fun requestControl(
        powerControl: IndoorBikePowerControl,
        reason: String,
    ) {
        try {
            powerControl.requestControl()
        } catch (exception: Exception) {
            throw TrainingSessionUnavailableException(
                "The connected device did not grant ERG control$reason",
                exception,
            )
        }
    }

    @Synchronized
    fun beginSession(
        sessionId: UUID,
        powerControl: IndoorBikePowerControl,
    ) {
        this.sessionId = sessionId
        activePowerControl = powerControl
        connectionInterruptedAt = null
        nextConnectionRecoveryAt = null
        connectionRecoveryAttempt = 0
        connectionRecovery = null
        targetSynchronizationPending = false
        targetSynchronizationAt = null
        pendingZeroPowerCommand = false
        lastTelemetryReceivedAt = null
        telemetryObserved = false
        connectionError = null
    }

    @Synchronized
    fun observeTelemetry(telemetry: IndoorBikeTelemetry?) {
        if (telemetry == null || !runCatching { trainingDevice.hasTelemetryCapability() }.getOrDefault(false)) {
            return
        }
        val receivedAt = telemetry.receivedAt
        if (telemetryObserved && lastTelemetryReceivedAt?.let { !receivedAt.isAfter(it) } == true) {
            return
        }
        telemetryObserved = true
        lastTelemetryReceivedAt = receivedAt
    }

    @Synchronized
    fun hasPendingZeroPowerCommand(): Boolean = pendingZeroPowerCommand

    @Synchronized
    fun markZeroPowerCommandPending(pending: Boolean) {
        pendingZeroPowerCommand = pending
    }

    @Synchronized
    fun hasPendingTargetSynchronization(): Boolean = targetSynchronizationPending

    @Synchronized
    fun markConnectionInterrupted(
        at: Instant,
        error: String? = null,
    ) {
        if (connectionInterruptedAt != null) {
            if (error != null) {
                connectionError = error
                onInterrupted(at, error, false)
            }
            return
        }

        connectionInterruptedAt = at
        nextConnectionRecoveryAt = at
        connectionRecoveryAttempt = 0
        activePowerControl = null
        targetSynchronizationPending = true
        targetSynchronizationAt = null
        connectionError = error
        onInterrupted(at, error, true)
        recordTrainerConnectionEvent(
            type = TrainingActivityEventType.TRAINER_CONNECTION_INTERRUPTED,
            occurredAt = at,
        )
        logger.warn("Trainer connection interrupted: sessionId={} at={}", sessionId, at)
    }

    @Synchronized
    fun ensure(
        now: Instant,
        paused: Boolean,
    ): TrainerConnectionAvailability {
        val currentPowerControl = runCatching { trainingDevice.currentPowerControl() }.getOrNull()
        val telemetryStale = !paused && isTelemetryStale(now)
        if (connectionInterruptedAt == null && currentPowerControl != null && !telemetryStale) {
            activePowerControl = currentPowerControl
            connectionError = null
            return availability(connected = true, recovered = false)
        }

        if (connectionInterruptedAt == null) {
            markConnectionInterrupted(now)
        }

        val pendingRecovery = connectionRecovery
        if (pendingRecovery != null) {
            val future = pendingRecovery.toCompletableFuture()
            if (!future.isDone) {
                return availability(connected = false, recovered = false)
            }

            connectionRecovery = null
            val recoveryResult = runCatching { future.join() }
            val recoveredPowerControl = recoveryResult.getOrNull()
            if (recoveredPowerControl != null) {
                val latestPowerControl = runCatching { trainingDevice.currentPowerControl() }.getOrNull()
                return trainerConnectionRecovered(
                    powerControl = latestPowerControl ?: recoveredPowerControl,
                    at = now,
                )
            }

            val failure =
                recoveryResult.exceptionOrNull()?.let { exception ->
                    exception.cause?.message ?: exception.message
                } ?: "Trainer reconnection failed"
            val currentPowerControlAfterRecovery =
                runCatching { trainingDevice.currentPowerControl() }.getOrNull()
            if (currentPowerControlAfterRecovery != null) {
                telemetryObserved = false
                lastTelemetryReceivedAt = null
            }
            activePowerControl = null
            connectionError = failure
            nextConnectionRecoveryAt = now.plus(connectionRecoveryDelay(connectionRecoveryAttempt.coerceAtLeast(1)))
            return availability(connected = false, recovered = false)
        }

        val nextRecoveryAt = nextConnectionRecoveryAt
        if (nextRecoveryAt != null && now.isBefore(nextRecoveryAt)) {
            return availability(connected = false, recovered = false)
        }

        val attempt = connectionRecoveryAttempt + 1
        connectionRecoveryAttempt = attempt
        recordTrainerConnectionEvent(
            type = TrainingActivityEventType.TRAINER_RECONNECT_ATTEMPTED,
            occurredAt = now,
            retryAttempt = attempt,
        )
        logger.info(
            "Trainer connection recovery attempt: sessionId={} attempt={}",
            sessionId,
            attempt,
        )

        val recoveryStage: CompletionStage<IndoorBikePowerControl?> =
            if (currentPowerControl != null && !telemetryStale) {
                CompletableFuture.completedFuture(currentPowerControl)
            } else {
                runCatching { trainingDevice.reconnectPowerControl(force = telemetryStale) }
                    .onFailure { exception ->
                        logger.warn(
                            "Trainer connection recovery could not start: sessionId={} attempt={} error={}",
                            sessionId,
                            attempt,
                            exception.message,
                            exception,
                        )
                    }.getOrElse {
                        CompletableFuture.completedFuture<IndoorBikePowerControl?>(null)
                    }
            }
        startTrainerRecovery(recoveryStage)
        return availability(connected = false, recovered = false)
    }

    @Synchronized
    fun activePowerControlOrThrow(): IndoorBikePowerControl =
        activePowerControl
            ?: throw TrainingSessionUnavailableException(
                "The active training session no longer has a connected ERG power-control device",
            )

    @Synchronized
    fun setTarget(
        powerControl: IndoorBikePowerControl,
        powerWatts: Int,
        description: String,
    ) {
        logger.debug(
            "ERG target command: sessionId={} targetPowerWatts={} reason={}",
            sessionId,
            powerWatts,
            description,
        )
        try {
            powerControl.setTargetPower(powerWatts)
            logger.debug(
                "ERG target command accepted: sessionId={} targetPowerWatts={} reason={}",
                sessionId,
                powerWatts,
                description,
            )
            recordTargetSynchronizationIfPending(powerWatts)
        } catch (exception: Exception) {
            logger.warn(
                "ERG target command rejected: sessionId={} targetPowerWatts={} reason={} error={}",
                sessionId,
                powerWatts,
                description,
                exception.message,
                exception,
            )
            throw TrainingSessionUnavailableException(
                "The connected device rejected the $description target",
                exception,
            )
        }
    }

    @Synchronized
    fun setFreeRide(
        powerControl: IndoorBikePowerControl,
        description: String,
    ) {
        logger.debug(
            "Free Ride command: sessionId={} reason={}",
            sessionId,
            description,
        )
        try {
            powerControl.setFreeRide()
            logger.debug(
                "Free Ride command accepted: sessionId={} reason={}",
                sessionId,
                description,
            )
            recordTargetSynchronizationIfPending(null)
        } catch (exception: Exception) {
            logger.warn(
                "Free Ride command rejected: sessionId={} reason={} error={}",
                sessionId,
                description,
                exception.message,
                exception,
            )
            throw TrainingSessionUnavailableException(
                "The connected device rejected the $description command",
                exception,
            )
        }
    }

    @Synchronized
    fun trySetTargetForExecution(
        powerWatts: Int,
        description: String,
        at: Instant,
    ): Boolean {
        val powerControl = activePowerControl ?: return false
        return try {
            setTarget(powerControl, powerWatts, description)
            true
        } catch (exception: TrainingSessionUnavailableException) {
            if (runCatching { trainingDevice.currentPowerControl() }.getOrNull() == null) {
                markConnectionInterrupted(at)
                false
            } else {
                throw exception
            }
        }
    }

    @Synchronized
    fun trySetFreeRideForExecution(
        description: String,
        at: Instant,
    ): Boolean {
        val powerControl = activePowerControl ?: return false
        return try {
            setFreeRide(powerControl, description)
            true
        } catch (exception: TrainingSessionUnavailableException) {
            if (runCatching { trainingDevice.currentPowerControl() }.getOrNull() == null) {
                markConnectionInterrupted(at)
                false
            } else {
                throw exception
            }
        }
    }

    @Synchronized
    fun clear(preserveRecovery: Boolean = false) {
        activePowerControl = null
        if (!preserveRecovery) {
            connectionInterruptedAt = null
            nextConnectionRecoveryAt = null
            connectionRecoveryAttempt = 0
            connectionRecovery = null
            targetSynchronizationPending = false
            targetSynchronizationAt = null
            pendingZeroPowerCommand = false
            lastTelemetryReceivedAt = null
            telemetryObserved = false
            connectionError = null
            sessionId = null
        }
    }

    private fun startTrainerRecovery(recoveryStage: CompletionStage<IndoorBikePowerControl?>) {
        connectionRecovery =
            try {
                recoveryStage.thenApplyAsync { powerControl ->
                    powerControl?.also { it.requestControl() }
                }
            } catch (exception: Exception) {
                logger.warn(
                    "Trainer connection recovery could not be prepared: sessionId={} error={}",
                    sessionId,
                    exception.message,
                    exception,
                )
                CompletableFuture.completedFuture(null)
            }
    }

    private fun trainerConnectionRecovered(
        powerControl: IndoorBikePowerControl,
        at: Instant,
    ): TrainerConnectionAvailability {
        activePowerControl = powerControl
        connectionInterruptedAt = null
        nextConnectionRecoveryAt = null
        connectionRecoveryAttempt = 0
        targetSynchronizationPending = true
        targetSynchronizationAt = at
        telemetryObserved = false
        lastTelemetryReceivedAt = null
        connectionError = null
        onRecovered(at)
        recordTrainerConnectionEvent(
            type = TrainingActivityEventType.TRAINER_RECONNECTED,
            occurredAt = at,
        )
        logger.info("Trainer connection recovered: sessionId={}", sessionId)
        return availability(connected = true, recovered = true)
    }

    private fun isTelemetryStale(now: Instant): Boolean {
        if (!runCatching { trainingDevice.hasTelemetryCapability() }.getOrDefault(false) || !telemetryObserved) {
            return false
        }
        val lastReceivedAt = lastTelemetryReceivedAt ?: return false
        return Duration.between(lastReceivedAt, now) > telemetryFreshness
    }

    private fun availability(
        connected: Boolean,
        recovered: Boolean,
    ): TrainerConnectionAvailability =
        TrainerConnectionAvailability(
            connected = connected,
            recovered = recovered,
            status =
                if (connected) {
                    TrainerConnectionStatus.CONNECTED
                } else if (connectionRecovery != null || connectionRecoveryAttempt > 0) {
                    TrainerConnectionStatus.RECONNECTING
                } else {
                    TrainerConnectionStatus.INTERRUPTED
                },
            retryAttempt = connectionRecoveryAttempt.takeIf { it > 0 },
            error = connectionError,
        )

    private fun connectionRecoveryDelay(attempt: Int): Duration {
        var delay = CONNECTION_RECOVERY_INITIAL_DELAY
        repeat((attempt - 1).coerceAtLeast(0)) {
            val doubled = delay.multipliedBy(2)
            delay = if (doubled > CONNECTION_RECOVERY_MAX_DELAY) CONNECTION_RECOVERY_MAX_DELAY else doubled
        }
        return delay
    }

    private fun recordTargetSynchronizationIfPending(targetPowerWatts: Int?) {
        if (!targetSynchronizationPending) {
            return
        }
        val eventSessionId = sessionId
        if (eventSessionId != null) {
            recordActivityEvent(
                eventSessionId,
                TrainingActivityEvent(
                    type = TrainingActivityEventType.TRAINER_TARGET_SYNCHRONIZED,
                    occurredAt = targetSynchronizationAt ?: clock.instant(),
                    targetPowerWatts = targetPowerWatts,
                ),
            )
        }
        targetSynchronizationPending = false
        targetSynchronizationAt = null
    }

    private fun recordTrainerConnectionEvent(
        type: TrainingActivityEventType,
        occurredAt: Instant,
        retryAttempt: Int? = null,
    ) {
        val eventSessionId = sessionId ?: return
        recordActivityEvent(
            eventSessionId,
            TrainingActivityEvent(
                type = type,
                occurredAt = occurredAt,
                retryAttempt = retryAttempt,
            ),
        )
    }

    private companion object {
        val CONNECTION_RECOVERY_INITIAL_DELAY: Duration = Duration.ofSeconds(1)
        val CONNECTION_RECOVERY_MAX_DELAY: Duration = Duration.ofSeconds(8)
    }
}
