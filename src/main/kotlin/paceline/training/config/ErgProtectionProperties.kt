package paceline.training.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "paceline.training.erg-protection")
data class ErgProtectionProperties(
    val enabled: Boolean = true,
    val lowCadenceRpm: Double = 45.0,
    val lowCadenceDuration: Duration = Duration.ofSeconds(3),
    val recoveryCadenceRpm: Double = 60.0,
    val recoveryDuration: Duration = Duration.ofSeconds(2),
    val targetChangeGracePeriod: Duration = Duration.ofSeconds(2),
) {
    init {
        require(lowCadenceRpm.isFinite() && lowCadenceRpm >= 0.0) {
            "ERG protection low cadence threshold must be finite and non-negative"
        }
        require(recoveryCadenceRpm.isFinite() && recoveryCadenceRpm > lowCadenceRpm) {
            "ERG protection recovery cadence threshold must be greater than the low cadence threshold"
        }
        require(!lowCadenceDuration.isNegative && !lowCadenceDuration.isZero) {
            "ERG protection low cadence duration must be positive"
        }
        require(!recoveryDuration.isNegative && !recoveryDuration.isZero) {
            "ERG protection recovery duration must be positive"
        }
        require(!targetChangeGracePeriod.isNegative) {
            "ERG protection target-change grace period must not be negative"
        }
    }
}
