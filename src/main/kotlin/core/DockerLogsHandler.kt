package core

import core.api.ContainerService
import mu.KotlinLogging
import java.io.OutputStream
import javax.servlet.http.HttpUpgradeHandler
import javax.servlet.http.WebConnection

class DockerLogsHandler : HttpUpgradeHandler {
    companion object {
        val log = KotlinLogging.logger {}
    }
    @Volatile
    lateinit var webConnection: WebConnection
    @Volatile
    lateinit var containerService: ContainerService
    @Volatile
    lateinit var podName: String

    override fun destroy() {}
    override fun init(webConnection: WebConnection) {
        this.webConnection = webConnection
        logs(webConnection.outputStream)
    }

    protected fun logs(out: OutputStream) {
        log.debug { "pod's $podName logs reading" }
        containerService.logs(name = podName, out = out)
    }
}