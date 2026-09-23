package paceline.training.domain

import paceline.telemetry.domain.CyclingTelemetry
import paceline.training.ports.CyclingTelemetrySource
import paceline.training.ports.TrainerControl
import paceline.training.ports.TrainerControlConnection
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

class TrainerControlCoordinator(
    private val clock: Clock,
    private val telemetryFreshness: Duration,
    private val onInterrupted: (Instant, String?, Boolean) -> Unit,
    private val onRecovered: (Instant) -> Unit,
    private val recordActivityEvent: (UUID, TrainingActivityEvent) -> Unit,
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    private var sessionId: UUID? = null
    private var selectedTrainer: TrainerControlConnection? = null
    private var selectedTrainerTelemetry: CyclingTelemetrySource? = null
    private var activeTrainerControl: TrainerControl? = null
    private var connectionInterruptedAt: Instant? = null
    private var nextConnectionRecoveryAt: Instant? = null
    private var connectionRecoveryAttempt = 0
    private var connectionRecovery: CompletionStage<TrainerControlConnection?>? = null
    private var targetSynchronizationPending = false
    private var targetSynchronizationAt: Instant? = null
    private var pendingStopCommand = false
    private var lastTelemetryReceivedAt: Instant? = null
    private var telemetryObserved = false
    private var connectionError: String? = null

    @Synchronized
    fun currentControl(): TrainerControl? {
        val active = activeTrainerControl ?: return null
        val selected = runCatching { selectedTrainer?.control() }.getOrNull()
        return active.takeIf { selected != null }
    }

    @Synchronized
    fun requestControl(
        trainerControl: TrainerControl,
        reason: String,
    ) {
        try {
            trainerControl.requestControl()
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
        trainer: TrainerControlConnection,
        trainerControl: TrainerControl,
        trainerTelemetry: CyclingTelemetrySource?,
    ) {
        this.sessionId = sessionId
        selectedTrainer = trainer
        selectedTrainerTelemetry = trainerTelemetry
        activeTrainerControl = trainerControl
        connectionInterruptedAt = null
        nextConnectionRecoveryAt = null
        connectionRecoveryAttempt = 0
        connectionRecovery = null
        targetSynchronizationPending = false
        targetSynchronizationAt = null
        pendingStopCommand = false
        lastTelemetryReceivedAt = null
        telemetryObserved = false
        connectionError = null
    }

    @Synchronized
    fun observeTelemetry(telemetry: CyclingTelemetry?) {
        if (telemetry == null || !hasCyclingTelemetry()) {
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
    fun hasPendingStopCommand(): Boolean = pendingStopCommand

    @Synchronized
    fun markStopCommandPending(pending: Boolean) {
        pendingStopCommand = pending
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
        activeTrainerControl = null
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
        val currentTrainerControl = runCatching { selectedTrainer?.control() }.getOrNull()
        val telemetryStale = !paused && isTelemetryStale(now)
        if (connectionInterruptedAt == null && currentTrainerControl != null && !telemetryStale) {
            activeTrainerControl = currentTrainerControl
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
            val recoveredTrainer = recoveryResult.getOrNull()
            if (recoveredTrainer != null) {
                return trainerConnectionRecovered(
                    trainer = recoveredTrainer,
                    at = now,
                )
            }

            val failure =
                recoveryResult.exceptionOrNull()?.let { exception ->
                    exception.cause?.message ?: exception.message
                } ?: "Trainer reconnection failed"
            val currentTrainerAfterRecovery = runCatching { selectedTrainer?.control() }.getOrNull()
            if (currentTrainerAfterRecovery != null) {
                telemetryObserved = false
                lastTelemetryReceivedAt = null
            }
            activeTrainerControl = null
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

        val recoveryStage: CompletionStage<TrainerControlConnection?> =
            if (currentTrainerControl != null && !telemetryStale) {
                CompletableFuture.completedFuture(selectedTrainer)
            } else {
                runCatching { reconnectSelectedTrainer(force = telemetryStale) }
                    .onFailure { exception ->
                        logger.warn(
                            "Trainer connection recovery could not start: sessionId={} attempt={} error={}",
                            sessionId,
                            attempt,
                            exception.message,
                            exception,
                        )
                    }.getOrElse {
                        CompletableFuture.completedFuture<TrainerControlConnection?>(null)
                    }
            }
        startTrainerRecovery(recoveryStage)
        return availability(connected = false, recovered = false)
    }

    @Synchronized
    fun activeTrainerControlOrThrow(): TrainerControl =
        currentControl()
            ?: throw TrainingSessionUnavailableException(
                "The active training session no longer has a connected ERG power-control device",
            )

    @Synchronized
    fun setTarget(
        trainerControl: TrainerControl,
        powerWatts: Int,
        description: String,
    ) {
        if (powerWatts == 0) {
            releaseResistance(trainerControl, "$description resistance release")
            return
        }
        executeTrainerCommand(
            action = "ERG target",
            description = description,
            synchronizedTargetPowerWatts = powerWatts,
        ) { trainerControl.setTargetPower(powerWatts) }
    }

    @Synchronized
    fun setFreeRide(
        trainerControl: TrainerControl,
        description: String,
    ) {
        executeTrainerCommand(
            action = "Free Ride",
            description = description,
            synchronizedTargetPowerWatts = null,
        ) { trainerControl.setFreeRide() }
    }

    @Synchronized
    fun releaseResistance(
        trainerControl: TrainerControl,
        description: String,
    ) {
        executeTrainerCommand(
            action = "Resistance release",
            description = description,
            synchronizedTargetPowerWatts = null,
        ) { trainerControl.releaseResistance() }
    }

    @Synchronized
    fun stop(
        trainerControl: TrainerControl,
        description: String,
    ) {
        executeTrainerCommand(
            action = "Trainer stop",
            description = description,
            synchronizedTargetPowerWatts = null,
        ) { trainerControl.stop() }
    }

    @Synchronized
    fun pause(
        trainerControl: TrainerControl,
        description: String,
    ) {
        executeTrainerCommand(
            action = "Trainer pause",
            description = description,
            synchronizedTargetPowerWatts = null,
        ) { trainerControl.pause() }
    }

    @Synchronized
    fun startOrResume(
        trainerControl: TrainerControl,
        description: String,
    ) {
        executeTrainerCommand(
            action = "Trainer start or resume",
            description = description,
            synchronizedTargetPowerWatts = null,
            recordSynchronization = false,
        ) { trainerControl.startOrResume() }
    }

    @Synchronized
    fun trySetTargetForExecution(
        powerWatts: Int,
        description: String,
        at: Instant,
    ): Boolean =
        tryCommandForExecution(at) { trainerControl ->
            setTarget(trainerControl, powerWatts, description)
        }

    @Synchronized
    fun trySetZeroWattTargetForExecution(
        description: String,
        at: Instant,
    ): Boolean =
        tryCommandForExecution(at) { trainerControl ->
            executeTrainerCommand(
                action = "ERG target",
                description = "$description zero-watt target",
                synchronizedTargetPowerWatts = 0,
            ) { trainerControl.setTargetPower(0) }
        }

    @Synchronized
    fun trySetFreeRideForExecution(
        description: String,
        at: Instant,
    ): Boolean =
        tryCommandForExecution(at) { trainerControl ->
            setFreeRide(trainerControl, description)
        }

    @Synchronized
    fun tryReleaseResistanceForExecution(
        description: String,
        at: Instant,
    ): Boolean =
        tryCommandForExecution(at) { trainerControl ->
            releaseResistance(trainerControl, description)
        }

    @Synchronized
    fun tryStopForExecution(
        description: String,
        at: Instant,
    ): Boolean =
        tryCommandForExecution(at) { trainerControl ->
            stop(trainerControl, description)
        }

    @Synchronized
    fun tryPauseForExecution(
        description: String,
        at: Instant,
    ): Boolean =
        tryCommandForExecution(at) { trainerControl ->
            pause(trainerControl, description)
        }

    private fun executeTrainerCommand(
        action: String,
        description: String,
        synchronizedTargetPowerWatts: Int?,
        recordSynchronization: Boolean = true,
        command: () -> Unit,
    ) {
        logger.debug(
            "{} command: sessionId={} reason={} targetPowerWatts={}",
            action,
            sessionId,
            description,
            synchronizedTargetPowerWatts,
        )
        try {
            command()
            logger.debug(
                "{} command accepted: sessionId={} reason={} targetPowerWatts={}",
                action,
                sessionId,
                description,
                synchronizedTargetPowerWatts,
            )
            if (recordSynchronization) {
                recordTargetSynchronizationIfPending(synchronizedTargetPowerWatts)
            }
        } catch (exception: Exception) {
            logger.warn(
                "{} command rejected: sessionId={} reason={} targetPowerWatts={} error={}",
                action,
                sessionId,
                description,
                synchronizedTargetPowerWatts,
                exception.message,
                exception,
            )
            throw TrainingSessionUnavailableException(
                "The connected device rejected the $description command",
                exception,
            )
        }
    }

    private fun tryCommandForExecution(
        at: Instant,
        command: (TrainerControl) -> Unit,
    ): Boolean {
        if (activeTrainerControl == null) {
            return false
        }
        val trainerControl = currentControl()
        if (trainerControl == null) {
            markConnectionInterrupted(at)
            return false
        }
        return try {
            command(trainerControl)
            true
        } catch (exception: TrainingSessionUnavailableException) {
            if (runCatching { selectedTrainer?.control() }.getOrNull() == null) {
                markConnectionInterrupted(at)
                false
            } else {
                throw exception
            }
        }
    }

    @Synchronized
    fun clear(preserveRecovery: Boolean = false) {
        activeTrainerControl = null
        if (!preserveRecovery) {
            selectedTrainer = null
            selectedTrainerTelemetry = null
            connectionInterruptedAt = null
            nextConnectionRecoveryAt = null
            connectionRecoveryAttempt = 0
            connectionRecovery = null
            targetSynchronizationPending = false
            targetSynchronizationAt = null
            pendingStopCommand = false
            lastTelemetryReceivedAt = null
            telemetryObserved = false
            connectionError = null
            sessionId = null
        }
    }

    private fun startTrainerRecovery(recoveryStage: CompletionStage<TrainerControlConnection?>) {
        connectionRecovery =
            try {
                recoveryStage.thenApplyAsync { trainer ->
                    trainer?.takeIf { it.control() != null }?.also { it.control()?.requestControl() }
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

    private fun reconnectSelectedTrainer(force: Boolean): CompletionStage<TrainerControlConnection?> =
        selectedTrainer?.reconnect(force) ?: CompletableFuture.completedFuture(null)

    private fun hasCyclingTelemetry(): Boolean = selectedTrainerTelemetry != null

    private fun trainerConnectionRecovered(
        trainer: TrainerControlConnection,
        at: Instant,
    ): TrainerConnectionAvailability {
        selectedTrainer = trainer
        activeTrainerControl = trainer.control()
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
        if (!hasCyclingTelemetry() || !telemetryObserved) {
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
