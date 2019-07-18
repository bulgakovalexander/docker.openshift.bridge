package tomcat
/*
import ContainerService
import mu.KotlinLogging
import org.apache.coyote.http11.upgrade.InternalHttpUpgradeHandler
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState.CLOSED
import org.apache.tomcat.util.net.SSLSupport
import org.apache.tomcat.util.net.SocketEvent
import org.apache.tomcat.util.net.SocketEvent.OPEN_READ
import org.apache.tomcat.util.net.SocketEvent.OPEN_WRITE
import org.apache.tomcat.util.net.SocketWrapperBase
import java.io.OutputStream
import javax.servlet.http.WebConnection

class DockerLogsTomcatHandler : InternalHttpUpgradeHandler {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    @Volatile
    private var socketWrapper: SocketWrapperBase<*>? = null
    @Volatile
    private var webConnection: WebConnection? = null
    @Volatile
    lateinit var containerService: ContainerService
    @Volatile
    lateinit var podName: String

    override fun timeoutAsync(now: Long) {}

    override fun hasAsyncIO() = false

    override fun upgradeDispatch(status: SocketEvent?): SocketState {
        log.trace { "upgradeDispatch status $status" }
        if (status == OPEN_READ || status == OPEN_WRITE) logs(out = SocketWrapperOutputStream(socketWrapper!!))
        return CLOSED
    }

    private fun logs(out: OutputStream) = containerService.logs(name = podName, out = out)

    override fun setSocketWrapper(wrapper: SocketWrapperBase<*>?) {
        socketWrapper = wrapper
    }

    override fun pause() {}

    override fun setSslSupport(sslSupport: SSLSupport?) {}

    override fun destroy() {}

    override fun init(webConnection: WebConnection) {
        this.webConnection = webConnection
    }
}
 */