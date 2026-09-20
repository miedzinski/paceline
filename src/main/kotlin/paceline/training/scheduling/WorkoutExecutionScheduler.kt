package paceline.training.scheduling

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import paceline.training.domain.TrainingSessionCoordinator
import java.time.Clock

@Component
class WorkoutExecutionScheduler(
    private val coordinator: TrainingSessionCoordinator,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(fixedDelay = 250)
    fun advanceWorkout() {
        coordinator.tick(now = clock.instant())
    }
}
