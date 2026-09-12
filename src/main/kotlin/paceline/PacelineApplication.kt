package paceline

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PacelineApplication

fun main(args: Array<String>) {
    runApplication<PacelineApplication>(*args)
}
