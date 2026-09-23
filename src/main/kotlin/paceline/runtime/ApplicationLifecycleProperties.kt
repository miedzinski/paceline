package paceline.runtime

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "paceline.lifecycle")
data class ApplicationLifecycleProperties(
    val idleTimeout: Duration = Duration.ZERO,
) {
    init {
        require(!idleTimeout.isNegative) {
            "Application idle timeout must not be negative"
        }
    }
}
