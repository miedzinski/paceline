package paceline.telemetry.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "paceline.telemetry")
data class TelemetryProperties(
    val freshness: Duration = Duration.ofSeconds(2),
) {
    init {
        require(!freshness.isNegative && !freshness.isZero) {
            "Telemetry freshness must be positive"
        }
    }
}
