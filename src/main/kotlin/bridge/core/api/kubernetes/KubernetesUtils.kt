package bridge.core.api.kubernetes

object KubernetesUtils {

    fun kuberName(name: String) = name.replace(".", "-").replace("_", "-")

}