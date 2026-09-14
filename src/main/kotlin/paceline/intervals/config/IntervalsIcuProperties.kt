package paceline.intervals.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "paceline.intervals")
data class IntervalsIcuProperties(
    val baseUrl: String = "https://intervals.icu",
    val athleteId: String = "0",
    val apiKey: String? = null,
)
