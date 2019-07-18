package core.api.kubernetes

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("docker")
class DockerProperties {
    var dockerUrl: String = "http://localhost:7070";
}