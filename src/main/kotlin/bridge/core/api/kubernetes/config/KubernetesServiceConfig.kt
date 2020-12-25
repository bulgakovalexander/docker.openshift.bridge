package bridge.core.api.kubernetes.config

import bridge.core.api.KubernetesProperties
import bridge.core.api.docker.DockerRegistryService
import bridge.core.api.kubernetes.KubernetesServiceImpl
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(KubernetesProperties::class)
@ConditionalOnProperty("service.type", havingValue = "kubernetes")
class KubernetesServiceConfig : BaseKubernetesServiceConfig() {

    @Bean
    fun kubernetesService(
        dockerRegistry: DockerRegistryService,
        kubernetesProperties: KubernetesProperties
    ): KubernetesServiceImpl {
        val config = ConfigBuilder().init(kubernetesProperties).build()
        val okHttpClient = okHttpClient(config)
        val kubernetesClient = DefaultKubernetesClient(okHttpClient, config)
        return KubernetesServiceImpl(
            kubernetesClient,
            okHttpClient,
            dockerRegistry,
            kubernetesProperties
        )
    }
}