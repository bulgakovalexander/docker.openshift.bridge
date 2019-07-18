package core.api.kubernetes

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(DockerProperties::class)
class DockerConfig(val properties: DockerProperties)