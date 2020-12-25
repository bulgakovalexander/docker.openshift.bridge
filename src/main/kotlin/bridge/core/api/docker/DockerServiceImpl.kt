package bridge.core.api.docker

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.SyncDockerCmd
import com.github.dockerjava.api.model.*
import com.github.dockerjava.api.model.LogConfig.LoggingType.fromValue
import com.github.dockerjava.core.command.BuildImageResultCallback
import com.github.dockerjava.core.command.LogContainerResultCallback
import com.github.dockerjava.core.command.PullImageResultCallback
import com.github.dockerjava.core.command.WaitContainerResultCallback
import bridge.core.api.ContainerService
import bridge.core.api.CreateRequest
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.io.InputStream
import java.io.OutputStream


class DockerServiceImpl(
    val client: DockerClient,
    val objectMapper: ObjectMapper = ObjectMapper()
) : ContainerService {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun version() = ResponseEntity.ok(client.versionCmd().exec())

    override fun logs(
        name: String,
        out: OutputStream
    ) {
        client.logContainerCmd(name).withFollowStream(true).exec(object : LogContainerResultCallback() {
            override fun onNext(item: Frame) {
                super.onNext(item)
                out.write(item.streamType.ordinal)
                out.write(item.payload)
            }
        })
    }

    override fun start(name: String) = exec(client.startContainerCmd(name))

    override fun stop(name: String) = exec(client.stopContainerCmd(name))

    override fun kill(name: String) = exec(client.killContainerCmd(name))

    override fun delete(name: String) = exec(client.removeContainerCmd(name))

    override fun wait(name: String): ResponseEntity<out Any> {
        val cmd = client.waitContainerCmd(name)
        val callback = WaitContainerResultCallback()
        cmd.exec(callback)
        val awaitStatusCode = callback.awaitStatusCode()
        return ResponseEntity.status(HttpStatus.OK).body(mapOf("StatusCode" to awaitStatusCode))
    }

    override fun createContainer(name: String?, createRequest: CreateRequest): ResponseEntity<*> {
        val image = createRequest.image
        val cmd = client.createContainerCmd(image)
        if (name != null) cmd.withName(name)

        val hostConfig = createRequest.hostConfig
        if (hostConfig != null) {
            val newHostConfig = HostConfig.newHostConfig()
            newHostConfig.withNetworkMode(hostConfig.networkMode)
            newHostConfig.withMemory(hostConfig.memory)
            val restartPolicy = hostConfig.restartPolicy
            if (restartPolicy != null) {
                val anyProperties = restartPolicy.anyProperties
                val name = anyProperties.get("Name")
                val maximumRetryCount = anyProperties.get("MaximumRetryCount") ?: ""
                if (name != null) {
                    val parsed = RestartPolicy.parse("$name:$maximumRetryCount")
                    newHostConfig.withRestartPolicy(parsed)
                }
            }
            val logConfig = hostConfig.logConfig
            if (logConfig != null) {
                val newLogConfig = LogConfig()
                newLogConfig.type = fromValue(logConfig.type)
                newLogConfig.config = logConfig.config
                newHostConfig.withLogConfig(newLogConfig)
            }
            cmd.withHostConfig(newHostConfig)
        }
        cmd.withEntrypoint(createRequest.entrypoint).withCmd(createRequest.cmd).withEnv(createRequest.env)
        val result = cmd.exec()
        return ResponseEntity.ok(result)
    }

    override fun build(t: String, inputStream: InputStream, outputStream: OutputStream) = ok {
        client.buildImageCmd(inputStream).withTags(setOf(t)).exec(object : BuildImageResultCallback() {
            override fun onNext(item: BuildResponseItem) {
                objectMapper.writeValue(outputStream, item)
                outputStream.flush()
                super.onNext(item)
            }
        }).awaitCompletion()
    }

    override fun pull(imageName: String, outputStream: OutputStream) = ok {
        client.pullImageCmd(imageName).exec(object : PullImageResultCallback() {
            override fun onNext(item: PullResponseItem?) {
                objectMapper.writeValue(outputStream, item)
                outputStream.flush()
                super.onNext(item)
            }
        }).awaitCompletion()
    }

    override fun inspectImage(imageName: String) = ResponseEntity.ok(client.inspectImageCmd(imageName).exec())

    override fun putTar(name: String, path: String, inputStream: InputStream) = ok {
        client.copyArchiveToContainerCmd(name).withRemotePath(path).withTarInputStream(inputStream).exec()
    }

    override fun getTar(name: String, path: String, outputStream: OutputStream) = ok {
        val input = client.copyArchiveFromContainerCmd(name, path).exec()
        input.copyTo(outputStream)
        outputStream.flush()
    }

    private fun exec(cmd: SyncDockerCmd<Void>): ResponseEntity<Any> {
        cmd.exec()
        return ResponseEntity.ok().build()
    }

    private fun ok(cmd: () -> Unit): ResponseEntity<Any> {
        cmd.invoke()
        return ResponseEntity.ok().build()
    }

}