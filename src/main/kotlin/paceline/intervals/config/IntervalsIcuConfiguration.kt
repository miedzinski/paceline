package paceline.intervals.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration(proxyBeanMethods = false)
class IntervalsIcuConfiguration {
    @Bean
    @Qualifier("intervalsIcuRestClient")
    fun intervalsIcuRestClient(properties: IntervalsIcuProperties): RestClient =
        RestClient
            .builder()
            .baseUrl(properties.baseUrl.removeSuffix("/"))
            .build()
}
