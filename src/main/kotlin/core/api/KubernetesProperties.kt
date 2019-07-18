package core.api

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

@ConfigurationProperties("kubernetes")
class KubernetesProperties : BaseKubernetesProperties() {
    @NestedConfigurationProperty
    val kaniko = Kaniko()
    @NestedConfigurationProperty
    val localDocker = LocalDockerProperties()
}

open class Kaniko {
    var restartPolicy = "Never"
    var image = "gcr.io/kaniko-project/executor:latest"
    var entrypoint = "/kaniko/executor"
    var contextDir: String = "/workspace"
    var dockerfile: String = ""
        get() = if (field.isEmpty()) "$contextDir/Dockerfile" else field
    var tag = "latest"

    var verbosity = "debug"
    var additionalParameters: List<String>? = listOf()
}


open class LocalDockerProperties {
    var registry = "localhost:5000"
    var insecure = true
    var skipTlsVerify = false
}