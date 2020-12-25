package bridge.core.api.kubernetes.config

import bridge.core.api.Auth
import bridge.core.api.BaseKubernetesProperties
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.ConfigFluent
import io.fabric8.kubernetes.client.utils.HttpClientUtils

open class BaseKubernetesServiceConfig {
    protected fun <T : ConfigFluent<T>> T.init(properties: BaseKubernetesProperties): T {
        val builder = this
        return with(properties) {
            builder.initAuth(auth).withMasterUrl(masterUrl)
            if (namespace != null) builder.withNamespace(namespace)
            builder
                .withConnectionTimeout(connectionTimeoutUnit.toMillis(connectionTimeout).toInt())
                .withRequestTimeout(requestTimeoutUnit.toMillis(requestTimeout).toInt())
                .withWebsocketTimeout(websocketTimeoutUnit.toMillis(websocketTimeout))
                .withTrustCerts(trustCerts)
                .withDisableHostnameVerification(disableHostnameVerification)
                .withWebsocketPingInterval(websocketPingIntervalUnit.toMillis(websocketPingInterval))
        }
    }

    private fun <T : ConfigFluent<T>> T.initAuth(auth: Auth) = if (auth.token != null) this.withOauthToken(auth.token)
    else this.withUsername(auth.userName).withPassword(auth.password)

    fun okHttpClient(config: Config) = HttpClientUtils.createHttpClient(config)!!
}