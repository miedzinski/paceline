package paceline.runtime

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.tomcat.TomcatConnectorCustomizer
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
class SocketActivationConfiguration {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun inheritedChannelCustomizer(environment: Environment): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            val enabled =
                Binder
                    .get(environment)
                    .bind(
                        "paceline.server.socket-activation.enabled",
                        Bindable.of(Boolean::class.javaObjectType),
                    ).orElse(false) == true

            if (enabled) {
                factory.addConnectorCustomizers(
                    TomcatConnectorCustomizer { connector ->
                        connector.setProperty("useInheritedChannel", "true")
                        logger.info("Configured Tomcat to use the systemd inherited HTTP socket")
                    },
                )
            }
        }
}
