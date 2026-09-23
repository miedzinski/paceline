package paceline.intervals.config

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import paceline.intervals.adapters.IntervalsActivityDto
import paceline.intervals.adapters.IntervalsActivityUploadDto
import paceline.intervals.adapters.IntervalsAthleteProfileDto
import paceline.intervals.adapters.IntervalsCalendarEventDto
import paceline.intervals.adapters.IntervalsLibraryWorkoutDto
import paceline.intervals.adapters.IntervalsSportSettingsDto

@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding(
    IntervalsAthleteProfileDto::class,
    IntervalsSportSettingsDto::class,
    IntervalsCalendarEventDto::class,
    IntervalsActivityDto::class,
    IntervalsActivityUploadDto::class,
    IntervalsLibraryWorkoutDto::class,
)
class IntervalsIcuConfiguration {
    @Bean
    @Qualifier("intervalsIcuRestClient")
    fun intervalsIcuRestClient(properties: IntervalsIcuProperties): RestClient =
        RestClient
            .builder()
            .baseUrl(properties.baseUrl.removeSuffix("/"))
            .build()
}
