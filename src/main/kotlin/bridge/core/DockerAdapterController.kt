package bridge.core

import bridge.core.api.ContainerService
import bridge.core.api.CreateRequest
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.client.KubernetesClientException
import mu.KotlinLogging
import org.springframework.core.io.Resource
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.server.ServerHttpRequest
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@RestController
@RequestMapping("\${controller.path:/}")
class DockerAdapterController(val service: ContainerService) {

    companion object {
        val log = KotlinLogging.logger {}
    }

    @ExceptionHandler
    fun kubernetesClientException(e: KubernetesClientException): ResponseEntity<Status>? {
        val status = e.status
        val code = status?.code ?: HttpStatus.INTERNAL_SERVER_ERROR.value()
        log.error(e) { "status $status" }
        return ResponseEntity.status(code).body(status)
    }

    @RequestMapping("/version")
    fun version(@RequestParam parameters: MultiValueMap<String, String>) = service.version()

    @PostMapping("/images/create")
    fun createImage(@RequestParam(name = "fromImage") fromImage: String, response: HttpServletResponse) =
        service.pull(fromImage, response.outputStream)

    @GetMapping("/images/**/json")
    fun inspectImage(request: HttpServletRequest) = service.inspectImage(getImageName(request.requestURI))

    private fun getImageName(requestURI: String): String {
        val pattern = ".*/images/(?<name>.+)/json".toRegex()
        log.trace { "extracts image name from uri $requestURI by pattern $pattern" }
        val matchResult = pattern.find(requestURI)
        val name = matchResult!!.groups["name"]!!.value
        log.trace { "extracted image name image $name from uri $requestURI" }
        return name
    }

    @PostMapping("/build")
    fun build(
        @RequestParam("t") t: String, @RequestParam parameters: MultiValueMap<String, String>,
        request: HttpServletRequest, response: HttpServletResponse
    ) = service.build(t, request.inputStream, response.outputStream)

    @PostMapping("/containers/create")
    fun containerCreate(
        @RequestParam("name", required = false) name: String?,
        @RequestParam parameters: MultiValueMap<String, String>,
        @RequestBody createRequest: CreateRequest
    ) = service.createContainer(name, createRequest)

    @PostMapping("/containers/{name}/attach")
    fun attachContainer(
        @PathVariable("name") name: String,
        @RequestParam(name = "logs", required = false) logs: Boolean,
        @RequestParam(name = "stream", required = false) stream: Boolean,
        @RequestParam(name = "stdin", required = false) stdin: Boolean,
        @RequestParam(name = "stdout", required = false) stdout: Boolean,
        @RequestParam(name = "stderr", required = false) stderr: Boolean,
        @RequestParam(name = "detachKeys", required = false) detachKeys: String?,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        response.status = HttpServletResponse.SC_SWITCHING_PROTOCOLS
        response.addHeader("Upgrade", "tcp")
        response.addHeader("Connection", "Upgrade")
//        val receiver = request.upgrade(DockerLogsTomcatHandler::class.java)
        val receiver = request.upgrade(DockerLogsHandler::class.java)
        receiver.containerService = service
        receiver.podName = name
    }

    @PostMapping("/containers/{name}/stop")
    fun stopContainer(@PathVariable("name") name: String) = service.stop(name)

    @PostMapping("/containers/{name}/kill")
    fun killContainer(@PathVariable("name") name: String) = service.kill(name)

    @PostMapping("/containers/{name}/start")
    fun startContainer(@PathVariable("name") name: String) = service.start(name)

    @PostMapping("/containers/{name}/wait")
    fun waitContainer(@PathVariable("name") name: String) = service.wait(name)

    @DeleteMapping("/containers/{name}")
    fun deleteContainer(@PathVariable("name") name: String) = service.delete(name)

    @PutMapping("/containers/{id}/archive")
    fun putTar(
        @PathVariable("id") id: String, @RequestParam("path") path: String,
        @RequestParam parameters: MultiValueMap<String, String>,
        @RequestBody request: Resource
    ) = service.putTar(id, path, request.inputStream)

    @GetMapping("/containers/{id}/archive", produces = ["application/x-tar"])
    fun getTar(
        @PathVariable("id") id: String,
        @RequestParam parameters: MultiValueMap<String, String>,
        @RequestParam("path") path: String,
        response: HttpServletResponse
    ) = service.getTar(id, path, response.outputStream)

}

