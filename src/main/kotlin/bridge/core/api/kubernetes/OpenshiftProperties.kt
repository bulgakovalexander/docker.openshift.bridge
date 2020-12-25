package bridge.core.api.kubernetes

import bridge.core.api.BaseKubernetesProperties
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("openshift")
class OpenshiftProperties : BaseKubernetesProperties() {

}