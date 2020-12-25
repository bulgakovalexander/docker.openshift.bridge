package bridge.tomcat
/*
import mu.KotlinLogging
import org.apache.catalina.connector.Connector
import org.apache.coyote.http11.AbstractHttp11Protocol
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Configuration


@Configuration
class TomcatCustomizer : WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun customize(factory: TomcatServletWebServerFactory) {
        factory.addConnectorCustomizers(TomcatConnectorCustomizer { connector: Connector ->
            val protocol = connector.protocolHandler as AbstractHttp11Protocol<*>
            protocol.connectionTimeout = -1
        })
    }
}
 */