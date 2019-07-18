package core.api.docker.config

import com.fasterxml.jackson.databind.ObjectMapper
import core.api.KubernetesProperties
import core.api.docker.DockerRegistryService
import okhttp3.OkHttpClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnBean(KubernetesProperties::class)
class DockerRegistryServiceConfig {

    @Bean
    fun dockerRegistryService(properties: KubernetesProperties) =
        DockerRegistryService(
            properties.localDocker, OkHttpClient(), ObjectMapper()
        )
}