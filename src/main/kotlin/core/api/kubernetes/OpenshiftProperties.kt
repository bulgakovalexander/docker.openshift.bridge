package core.api.kubernetes

import core.api.BaseKubernetesProperties
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("openshift")
class OpenshiftProperties : BaseKubernetesProperties() {

}