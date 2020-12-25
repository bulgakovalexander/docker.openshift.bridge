package bridge.core.api.docker.config

import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.netty.NettyDockerCmdExecFactory
import bridge.core.api.docker.DockerServiceImpl
import bridge.core.api.kubernetes.DockerProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty("service.type", havingValue = "docker")
class DockerServiceConfig(val dockerProperties: DockerProperties) {

    @Bean
    fun dockerService() = DockerServiceImpl(dockerClient())

    private fun dockerClient() =
        DockerClientBuilder.getInstance().withDockerCmdExecFactory(dockerCmdExecFactory()).build()

    private fun dockerCmdExecFactory(): NettyDockerCmdExecFactory {
        val factory = NettyDockerCmdExecFactory()
        factory.init(dockerClientConfig())
        return factory
    }

    private fun dockerClientConfig() =
        DefaultDockerClientConfig.Builder().withDockerHost(dockerProperties.dockerUrl).build()
}