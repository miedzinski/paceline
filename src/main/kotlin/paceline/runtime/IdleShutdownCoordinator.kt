package paceline.runtime

import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import paceline.device.domain.ConnectionCoordinator
import paceline.device.domain.DiscoveryPhase
import paceline.training.domain.TrainingSessionCoordinator
import java.util.concurrent.atomic.AtomicBoolean

@Component
class IdleShutdownCoordinator(
    private val properties: ApplicationLifecycleProperties,
    private val activity: RuntimeActivity,
    private val connectionCoordinator: ConnectionCoordinator,
    private val trainingSessionCoordinator: TrainingSessionCoordinator,
    private val applicationContext: ConfigurableApplicationContext,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val shutdownStarted = AtomicBoolean()

    @Scheduled(fixedDelay = IDLE_CHECK_INTERVAL_MILLIS)
    fun shutDownWhenIdle() {
        val timeout = properties.idleTimeout
        if (timeout.isZero || !activity.isIdle(timeout)) {
            return
        }
        if (connectionCoordinator.discoveryState().phase == DiscoveryPhase.DISCOVERING) {
            return
        }
        if (trainingSessionCoordinator.preventsIdleShutdown()) {
            return
        }
        if (!shutdownStarted.compareAndSet(false, true)) {
            return
        }

        logger.info("Stopping Paceline after {} of inactivity", timeout)
        applicationContext.close()
    }

    private companion object {
        const val IDLE_CHECK_INTERVAL_MILLIS = 1_000L
    }
}
