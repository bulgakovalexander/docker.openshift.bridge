package bridge.core.api.kubernetes.config

import bridge.core.api.kubernetes.OpenshifServiceImpl
import bridge.core.api.kubernetes.OpenshiftProperties
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.openshift.client.DefaultOpenShiftClient
import io.fabric8.openshift.client.OpenShiftConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(OpenshiftProperties::class)
@ConditionalOnProperty("service.type", havingValue = "openshift", matchIfMissing = true)
class OpenShiftServiceConfig : BaseKubernetesServiceConfig() {

    @Bean
    fun openShiftService(
        openshiftProperties: OpenshiftProperties
    ): OpenshifServiceImpl {
        val config = ConfigBuilder().init(openshiftProperties).build()
        val okHttpClient = okHttpClient(config)
        val openShiftConfig = OpenShiftConfig.wrap(config)

        val openShiftClient = DefaultOpenShiftClient(okHttpClient, openShiftConfig)
        return OpenshifServiceImpl(
            openShiftClient,
            okHttpClient,
            openshiftProperties
        )
    }
}