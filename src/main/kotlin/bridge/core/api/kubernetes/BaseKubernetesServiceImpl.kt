package bridge.core.api.kubernetes

import com.fasterxml.jackson.databind.ObjectMapper
import bridge.core.api.BaseKubernetesProperties
import bridge.core.api.ContainerService
import bridge.core.api.CreateRequest
import bridge.core.api.kubernetes.KubernetesUtils.kuberName
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.PodFluent.SpecNested
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.OperationInfo
import io.fabric8.kubernetes.client.dsl.*
import io.fabric8.kubernetes.client.dsl.internal.ExecWebSocketListener
import io.fabric8.kubernetes.client.dsl.internal.LogWatchCallback
import io.fabric8.kubernetes.client.dsl.internal.PodOperationContext
import io.fabric8.kubernetes.client.dsl.internal.PodOperationsImpl
import io.fabric8.kubernetes.client.utils.URLUtils
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.apache.commons.codec.binary.Base64InputStream
import org.apache.commons.codec.binary.Base64OutputStream
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatus.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.ResponseEntity.ok
import org.springframework.http.ResponseEntity.status
import java.io.*
import java.lang.Boolean.TRUE
import java.net.URL
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPInputStream


abstract class BaseKubernetesServiceImpl<T : KubernetesClient, P : BaseKubernetesProperties>(
    protected val client: T,
    protected val httpClient: OkHttpClient,
    protected val properties: P,
    private val objectMapper: ObjectMapper = ObjectMapper()
) : ContainerService {

    val sh_i = "/bin/sh -i"
    val shI = sh_i.split(" ")
    val shC = listOf("/bin/sh", "-c")
    val sh = listOf("/bin/sh")

    companion object {
        val log = KotlinLogging.logger {}
        val logTar = KotlinLogging.logger(log.name + ".tar")
        const val arguments = "arguments"
        const val entrypoint = "entrypoint"
        const val image_pull_check = "image-pull-check"
        const val shared_files = "shared-files"
        const val main = "bridge-main"
        val invalid_image_reasons = listOf("ImagePullBackOff", "ErrImagePull")
        val end = "\n".toByteArray()
        val end_term = end + 4
        const val UTF_8 = "UTF-8"
        const val STD_OUT: Byte = 1
        const val STD_ERR: Byte = 2
        const val RAW: Byte = 3
    }

    protected fun unpackDir(dirName: String): File {
        val tmpRootDir = System.getProperty("java.io.tmpdir")
        val tmpDir = File(tmpRootDir, "docker.kuber.proxy")
        val tmpTarOutput = File(tmpDir, dirName)
        return tmpTarOutput
    }

    open fun pod(name: String): PodResource<Pod, DoneablePod> {
        return client.pods().withName(name)
    }

    override fun logs(name: String, out: OutputStream) {
        val podName = kuberName(name)
        val resource = pod(podName)

        val status = waitContainerStatus(resource, waitReady = true)
        log.trace { "pod $podName ready to read logs, status $status" }

        val out = ContainerAttachOutputStream(out)
        val withPrettyOutput = resource.inContainer(main).withPrettyOutput() as PodOperationsImpl
        val logWatch = withPrettyOutput.watchLog1(out) as LogWatchCallback
        try {
            val execResult = mainContainerExecResult(resource)
            log.trace { "pod $podName logs reading finish, exit status ${execResult.status}" }
        } catch (e: Exception) {
            log.error(e) { "pod $podName logs reading error" }
        } finally {
            logWatch.close()
            out.flush()
        }
    }

    private fun startAndJoin(podName: String, wait: () -> Unit) {
        val waitRoutine = Thread(wait)
        waitRoutine.start()
        log.trace { "pod $podName is being waited to exit by $waitRoutine" }
        waitRoutine.join()
        log.trace { "pod $podName attaching has finished" }
    }

    override fun stop(name: String) = delete(name)

    override fun kill(name: String) = delete(name)
    override fun start(name: String): ResponseEntity<out Any> {
        val podName = kuberName(name)
        val resource = pod(podName)
        val success = isSuccess(finishSharedFilesAndStartMainContainer(resource))
        if (!success) log.error {
            val strLog = resource.log
            "container's starting was failed. container: $name \n log:$strLog"
        }
        return status(if (success) NO_CONTENT else INTERNAL_SERVER_ERROR).build()
    }

    protected fun finishSharedFilesAndStartMainContainer(
        resource: PodResource<Pod, DoneablePod>
    ): ContainerState {
        terminateContainer(shared_files, resource)
        return waitContainerStatus(resource, waitReady = true).state
    }

    open fun terminateContainer(
        containerName: String,
        resource: PodResource<Pod, DoneablePod>,
        wait: Long? = 60,
        waitTimeUnit: TimeUnit? = SECONDS
    ): Status = sendToContainerProcessStdin(resource, containerName, "exit", wait, waitTimeUnit)

    fun isSuccess(state: ContainerState): Boolean {
        val terminated = state.terminated
        val waiting = state.waiting

        return if (terminated != null) {
            val exitCode = terminated.exitCode
            exitCode != null && exitCode == 0
        } else waiting == null
    }

    fun waitContainerStatus(
        resource: PodResource<Pod, DoneablePod>,
        containerName: String = main,
        waitReady: Boolean
    ): ContainerStatus {
        var containerStatus: ContainerStatus
        var sleep = 0L;
        var resume = true
        do {
            sleep = sleep(sleep)
            val pod = resource.get()
            val status = pod.status
            containerStatus = status.containerStatuses.first { it.name == containerName }
            if (containerStatus == null) throw java.lang.IllegalStateException(
                "container status $containerName doesn't exists in $containerStatus "
            )
            val state = containerStatus.state
            val terminated = state.terminated
            val waiting = state.waiting
            if (terminated != null) {
                resume = false
            } else if (waiting != null) {
                val reason = waiting.reason
                if ("PodInitializing" == reason) resume = true
                else if (isInvalidImage(reason) || runError(reason)) {
                    resume = false
                } else log.trace { "waiting reason $reason" }
            } else if (waitReady && TRUE == containerStatus.ready) {
                resume = false
            }
        } while (resume)
        return containerStatus
    }

    private fun runError(reason: String) = listOf("RunContainerError", "CrashLoopBackOff").contains(reason)

    private fun callUnzipTarScript(): String {
        val initFilesScript = properties.initFilesScript
        val script = properties.sharedFilesPath + "/" + initFilesScript.name
        val chmod = initFilesScript.executableRights + " $script"
        return "$chmod && $script"
    }

    protected fun sendToContainerProcessStdin(
        resource: PodResource<Pod, DoneablePod>,
        containerName: String,
        value: String,
        wait: Long? = 60,
        waitTimeUnit: TimeUnit? = SECONDS
    ): Status {
        val latch = CountDownLatch(1)
        val bytes = value.toByteArray() + "\r\n".toByteArray()
        val errChannel = ByteArrayOutputStream()
        val out = ByteArrayOutputStream()
        val attachable =
            resource.inContainer(containerName).readingInput(ByteArrayInputStream(bytes))
                .writingOutput(out).writingErrorChannel(errChannel).withTTY()
                .usingListener(waitListener(latch)) as PodOperationsImpl
        attachable.attach()
        latch.wait(wait, waitTimeUnit)
        return status(errChannel)
    }

    protected fun PodOperationsImpl.watchLog1(out: OutputStream? = null): LogWatch {
        try {
            val context = this.context
            val url = URL(URLUtils.join(this.resourceUrl.toString(), context.getLogParameters() + "&follow=true"))
            val request = Request.Builder().url(url).get().build()
            val configuration = client.configuration
            val callback = LogWatchCallback(configuration, out)
            val httpClientBuilder = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
            httpClientBuilder.networkInterceptors().replaceAll {
                if (it is HttpLoggingInterceptor && it.level == HttpLoggingInterceptor.Level.BODY) {
                    val newInt = HttpLoggingInterceptor()
                    newInt.level = HttpLoggingInterceptor.Level.HEADERS
                    newInt
                } else it
            }

            val clone = httpClientBuilder.build()
            val newCall = clone.newCall(request)
            newCall.enqueue(callback)

            return callback
        } catch (t: Throwable) {
            throw KubernetesClientException.launderThrowable(forOperationType("watchLog"), t)
        }
    }

    private fun PodOperationContext.getLogParameters(): String {
        val sb = StringBuilder()
        sb.append("log?pretty=").append(isPrettyOutput)
        if (containerId != null && !containerId.isEmpty()) {
            sb.append("&container=").append(containerId)
        }
        if (isTerminatedStatus) {
            sb.append("&previous=true")
        }
        if (sinceSeconds != null) {
            sb.append("&sinceSeconds=").append(sinceSeconds)
        } else if (sinceTimestamp != null) {
            sb.append("&sinceTime=").append(sinceTimestamp)
        }
        if (tailingLines != null) {
            sb.append("&tailLines=").append(tailingLines)
        }
        if (limitBytes != null) {
            sb.append("&limitBytes=").append(limitBytes)
        }
        if (isTimestamps) {
            sb.append("&timestamps=true")
        }
        return sb.toString()
    }


    protected fun PodOperationsImpl.attach() = attach(
        context.containerId,
        context.isTty,
        context.`in`,
        context.out,
        context.err,
        resourceUrl,
        config,
        context.errChannel,
        context.execListener,
        context.bufferSize,
        ::forOperationType
    )

    protected fun attach(
        container: String?,
        withTTY: Boolean,
        stdin: InputStream?,
        out: OutputStream?,
        err: OutputStream?,
        resourceUrl: URL,
        config: Config?,
        errChannel: OutputStream?,
        execListener: ExecListener?,
        bufferSize: Int?,
        operationInfo: (String) -> OperationInfo
    ): ExecWebSocketListener {
        val parametersList = ArrayList<String>()

        if (container != null && container.isNotEmpty()) {
            parametersList.add("container=$container")
        }
        if (withTTY) {
            parametersList.add("tty=true")
        }

        if (stdin != null) {
            parametersList.add("stdin=true")
        }
        if (out != null) {
            parametersList.add("stdout=true")
        }
        if (err != null) {
            parametersList.add("stderr=true")
        }

        val parameters = parametersList.reduce { l, r -> "$l&$r" }
        val cmdUrl = "attach" + if (parameters.isNotEmpty()) "?$parameters" else parameters

        try {
            val url = URL(URLUtils.join(resourceUrl.toString(), cmdUrl))
            val r = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "v4.channel.k8s.io").get()
            val clone = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            val execWebSocketListener = ExecWebSocketListener(
                config,
                stdin,
                out,
                err,
                errChannel,
                null,
                null,
                null,
                null,
                execListener,
                bufferSize
            )
            clone.newWebSocket(r.build(), execWebSocketListener)
            execWebSocketListener.waitUntilReady()
            return execWebSocketListener
        } catch (t: Throwable) {
            throw KubernetesClientException.launderThrowable(operationInfo.invoke("attach"), t)
        }
    }

    private fun exec(
        pod: TtyExecErrorable<String, OutputStream, PipedInputStream, ExecWatch>,
        cmd: Array<String>,
        tty: Boolean = false,
        awaitTime: Long? = 30,
        awaitTimeUnit: TimeUnit = SECONDS
    ): ExecResult<Status, String> {
        val latch = CountDownLatch(1)
        val listener = waitListener(latch)
        val errorChannel = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val channelable = pod.writingError(error).writingErrorChannel(errorChannel)
        val listenable = if (tty) channelable.withTTY() else channelable
        listenable.usingListener(listener).exec(*cmd)

        latch.wait(awaitTime, awaitTimeUnit) { "exec waiting time reached timeout $awaitTime $awaitTimeUnit" }

        return ExecResult(
            status(errorChannel),
            error.toString(UTF_8)
        )
    }

    protected fun status(errorChannel: ByteArrayOutputStream): Status {
        val statusRaw = errorChannel.toString(UTF_8)
        val status: Status =
            if (statusRaw.isNotEmpty()) objectMapper.readerFor(Status::class.java).readValue(statusRaw) else Status()
        return status
    }

    open class ExecResult<EC, E>(val status: EC, val error: E)

    override fun delete(name: String) = status(
        if (properties.disableDeleting) OK else {
            val podName = kuberName(name)
            val pod = pod(podName)
            val delete = pod.delete()
            if (delete) OK else NOT_FOUND
        }
    ).build<Any>()

    override fun wait(name: String): ResponseEntity<out Any> {
        val podName = kuberName(name)
        val resource = pod(podName)
        val execResult = mainContainerExecResult(resource)
        val httpStatus = httpStatus(execResult)
        val map = mapOf("StatusCode" to (if (httpStatus == OK) 0 else httpStatus.value()))
        return status(httpStatus).body(map)
    }

    private fun mainContainerExecResult(resource: PodResource<Pod, DoneablePod>): ExecResult<Status, String> {
        val pod = resource.inContainer(main)

        val exitCodeFile = properties.exitCodeFile
        val checkFileExists = exitCodeFile.checkFileExists
        log.trace { "check exit code file exists cmd: $checkFileExists" }
        var sleep = 0L;
        do {
            sleep = sleep(sleep)
            val execResult = exec(pod, arrayOf("sh", "-c", checkFileExists))
            val status = execResult.status
        } while (status.reason == "NonZeroExitCode")

        val extractExitStatus = exitCodeFile.extractExitStatus
        log.trace { "extract exit code  cmd: $extractExitStatus" }
        val outputStream = ByteArrayOutputStream()
        return exec(pod.writingOutput(outputStream), arrayOf("sh", "-c", extractExitStatus))
    }

    private fun toEnvs(envs: List<String>) = envs.map {
        val split = it.split("=")
        EnvVar(split[0], split[1], null)
    }

    override fun version() = ok(mapOf("ApiVersion" to properties.dockerVersion.apiVersion))

    override fun createContainer(name: String?, createRequest: CreateRequest): ResponseEntity<out Any> {
        val localImage = checkLocalImage(createRequest.image)
        val image = localImage ?: createRequest.image

        val podName = KubernetesUtils.kuberName(name ?: UUID.randomUUID().toString())
        val podsApi = client.pods()
        val podResource = podsApi.withName(podName)
        val sharedFilesPath = properties.sharedFilesPath
        val sharedVolumeName = "shared-files"
        val copySharedFiles = callUnzipTarScript()
        val lastExitCode = properties.exitCodeFile.writeExitCode
        log.trace { "write exit code cmd: $lastExitCode " }
        val cmd = modifyLast(copySharedFiles, createRequest.cmd, listOf(lastExitCode, sh_i))

        val imagePullPolicy = properties.imagePullPolicy
        val restartPolicy = properties.restartPolicy

        val initFilesScript = properties.initFilesScript

        podResource.delete()
        val createSharedFilesScriptCmd = "touch " + properties.sharedFilesPath + "/" + initFilesScript.name
        podResource.createOrReplace(
            PodBuilder()
                .withNewMetadata().withName(podName)
                .withAnnotations(
                    mapOf(
                        entrypoint to createRequest.entrypoint,
                        arguments to cmd.reduce { l, r -> "$l $r" })
                )
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy(restartPolicy)
                .addImageCheckContainer(image, properties)

                .addImageSharedFiles(
                    sharedVolumeName,
                    sharedFilesPath,
                    createSharedFilesScriptCmd
                )

                .addNewContainer()
                .withName(main)
                .addVolumeMount(sharedVolumeName, sharedFilesPath)
                .withArgs(cmd)
                .withEnv(toEnvs(createRequest.env))
                .withImage(image)
                .withNewImagePullPolicy(imagePullPolicy)
                .withStdinOnce(true).withStdin(true).withTty(true)
                .endContainer()
                .endSpec()
                .build()
        )

        val status = waitInitContainerReadyToInvoke(podResource)
        return if (status == CreatePodStatus.Started) ok(mapOf("Id" to podName)) else notFound()
    }

    protected abstract fun checkLocalImage(image: String): String?

    fun SpecNested<PodBuilder>.addSharedEmptyDir(name: String) =
        this.addNewVolume().withNewName(name).withNewEmptyDir().endEmptyDir().endVolume()

    protected fun SpecNested<PodBuilder>.addImageSharedFiles(
        volumeName: String,
        mountPath: String,
        createScriptCmd: String = ""
    ): SpecNested<PodBuilder> {
        val scriptAndShInteractive = if (createScriptCmd.isNotEmpty()) "$createScriptCmd && $sh_i" else sh_i
        return this
            .addNewInitContainer()
            .withName(shared_files)
            .addVolumeMount(volumeName, mountPath)
            .withImage(properties.initContainerImage).withCommand(shC).withArgs(scriptAndShInteractive)
            .withStdin(true).withTty(true).withNewImagePullPolicy(properties.imagePullPolicy)
            .endInitContainer()
            .addSharedEmptyDir(volumeName)
    }

    fun <T : ContainerFluent<T>> T.addVolumeMount(
        volumeName: String, mountPath: String
    ) = this.addNewVolumeMount().withName(volumeName).withMountPath(mountPath).endVolumeMount()

    private fun SpecNested<PodBuilder>.addImageCheckContainer(image: String, properties: P) = this
        .addNewInitContainer()
        .withName(image_pull_check).withNewImage(image).withArgs(sh)
        .withNewImagePullPolicy(properties.imagePullPolicy)
        .endInitContainer()

    protected fun removeShellFirst(cmd: MutableList<String>): Boolean {
        val iterator = cmd.listIterator()
        if (iterator.hasNext()) {
            val first = iterator.next()
            if (first == "sh" || first == "/bin/sh") {
                iterator.remove()
                if (iterator.hasNext()) {
                    val second = iterator.next()
                    if (second.startsWith("-")) {
                        iterator.remove()
                    }
                }
                return true;
            }
        }
        return false
    }

    private fun modifyLast(
        prefix: String,
        cmd: List<String>,
        postfixes: List<String>
    ): List<String> {
        val cmds = ArrayList(cmd)
        val shellRemoved = removeShellFirst(cmds)
        if (shellRemoved) log.trace("shell removed from $cmd, result $cmds")

        val baseCmd = cmds.reduce { l, r -> "$l $r" }
        val postfix = postfixes.reduce { l, r -> "$l && $r" }
        val resultCmd = "$prefix && $baseCmd && $postfix"
        return ArrayList(shC) + resultCmd
    }


    private fun notFound() = status(NOT_FOUND).body("")

    enum class CreatePodStatus {
        InvalidImage,
        StartFailed,
        Started,
        Terminated
    }

    fun waitInitContainerReadyToInvoke(
        podResource: PodResource<Pod, DoneablePod>
    ): CreatePodStatus {

        var sleep = 0L
        while (true) {
            sleep = sleep(sleep)

            val pod = podResource.get()
            val podName = pod.metadata.name
            if (pod == null) log.debug { "no pods retrieved for deployment $podName. Will repeat." }
            else {
                val status = pod.status
                val containerStatuses = status.initContainerStatuses

                containerStatuses.forEach { containerStatus ->
                    val containerName = containerStatus.name
                    val state = containerStatus.state
                    val waiting = state.waiting
                    val waitingReason = waiting?.reason ?: ""

                    if (containerName == image_pull_check) {
                        if (isInvalidImage(waitingReason)) {
                            podResource.delete()
                            return CreatePodStatus.InvalidImage
                        } else if ("ContainerCreating" == waitingReason) {
                            throw IllegalStateException("init $containerName failed on start, reason $waitingReason")
                        }
                    } else if (containerName == shared_files) {
                        if (isInvalidImage(waitingReason)) {
                            throw IllegalStateException("init $containerName failed on image pulling, reason $waitingReason")
                        } else if ("ContainerCreating" == waitingReason) {
                            throw IllegalStateException("init $containerName failed on start, reason $waitingReason")
                        } else {
                            val terminated = state.terminated
                            if (terminated != null) throw IllegalStateException(
                                "init $containerName terminated unexpectedly, " +
                                        "reason ${terminated.reason}, " +
                                        "exitCode:${terminated.exitCode}"
                            ) else {
                                val running = state.running
                                if (running != null) {
                                    return CreatePodStatus.Started
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sleep(sleep: Long, init: Long = 1000, rate: Double = 1.5) = if (sleep > 0) {
        Thread.sleep(sleep)
        (sleep * rate).toLong()
    } else init

    private fun isInvalidImage(reason: String) = invalid_image_reasons.contains(reason)

    override fun putTar(
        name: String,
        path: String,
        inputStream: InputStream
    ): ResponseEntity<out Any> {

        val podName = kuberName(name)
        val podResource = client.pods().withName(podName)

        val execResult = copyDirToContainerWithDelayInit(
            podName, podResource,
            shared_files, inputStream, path
        )

        return toResponse(execResult)
    }

    override fun getTar(name: String, path: String, outputStream: OutputStream): ResponseEntity<out Any> {
        val podName = kuberName(name)
        val podResource = client.pods().withName(podName)
        copyPathPod(name, podResource, path, outputStream)
        return ok().contentType(MediaType.parseMediaType("application/x-tar")).build()
    }

    private fun copyPathPod(
        podName: String,
        podResource: PodResource<Pod, DoneablePod>,
        path: String,
        outputStream: OutputStream,
        wait: Long? = 60,
        waitTimeUnit: TimeUnit? = SECONDS
    ) {
        val dirPostfix = "/."
        val isDirectory = path.endsWith(dirPostfix)

        val error = ByteArrayOutputStream()
        val copyCmd = if (isDirectory) {
            val dir = path.subSequence(0, path.length - 1)
            "tar -P -C $dir -zcf - ./ | base64"
        } else "cat $path | base64"

        log.trace {
            val type = if (isDirectory) "directory" else "file"
            "copy $type from pod $podName by cmd $copyCmd"
        }

        val logTarContent = logTar.isTraceEnabled
        val debugOut: ByteArrayOutputStream? = if (logTarContent) ByteArrayOutputStream() else null

        val latch = CountDownLatch(1)
        podResource.writingOutput(Base64OutputStream(debugOut ?: outputStream, false)).writingError(error)
            .usingListener(waitListener(latch))
            .exec("sh", "-c", copyCmd)
        latch.wait(
            wait,
            waitTimeUnit
        ) { "copy path $path from pod $podName waiting time reached timeout $wait $waitTimeUnit" }

        if (error.size() > 0) {
            val errMsg = error.toString(UTF_8)
            val replace = errMsg.replace("'", "").replace("`", "")
            if (!replace.contains("Removing leading / from member names")) {
                val msg = "copyPathPod error $error, path $path"
                log.error { msg }
                throw IllegalStateException(msg)
            }
        }

        if (debugOut != null) {
            val bytes = debugOut.toByteArray()
            logTarContent(path, ByteArrayInputStream(bytes))
            outputStream.write(bytes)
        }

        outputStream.flush()
    }

    protected fun CountDownLatch.wait(
        wait: Long? = 60,
        waitTimeUnit: TimeUnit? = SECONDS,
        msg: () -> String = { "reached timeout $wait $waitTimeUnit" }
    ) {
        if (wait != null) {
            val success = this.await(wait, waitTimeUnit)
            if (!success) throw TimeoutException(msg.invoke())
        } else this.await()
    }

    private fun toResponse(execResult: ExecResult<Status, String>): ResponseEntity<out Any> =
        status(httpStatus(execResult)).build()

    private fun httpStatus(execResult: ExecResult<Status, String>): HttpStatus {
        val status = execResult.status
        log.debug { "end with status:$status" }
        return if (status.code == 500) INTERNAL_SERVER_ERROR else OK
    }

    private fun copyDirToContainerWithDelayInit(
        podName: String,
        pod: PodResource<Pod, DoneablePod>,
        container: String,
        dirTarGzipInputStream: InputStream,
        path: String,
        awaitTime: Long? = 30,
        awaitTimeUnit: TimeUnit = SECONDS
    ): ExecResult<Status, String> {

        log.trace { "copy directory to pod's container. dir $path, container $container, pod $podName" }

        val stream = if (logTar.isTraceEnabled) {
            val stream = ByteArrayInputStream(dirTarGzipInputStream.readBytes())
            logTarContent(path, stream)
            stream.reset()
            stream
        } else dirTarGzipInputStream

        val input = base64EncodedWithEndTerm(stream)

        val archName = UUID.randomUUID().toString() + ".tar.gz"
        val sharedFilesPath = properties.sharedFilesPath
        val sharedPath = "$sharedFilesPath/$archName"
        val copyToTmp = "base64 -d > $sharedPath"
        val script = sharedFilesPath + "/" + properties.initFilesScript.name
        val addToScript = "echo \"tar -zxmf $sharedPath -C $path\n\" >> $script"

        val inContainer = pod.inContainer(container).readingInput(input)

        val cmd = "$copyToTmp && $addToScript"
        log.trace { "cmd '$cmd'" }
        return exec(
            inContainer,
            arrayOf("sh", "-c", cmd),
            true,
            awaitTime,
            awaitTimeUnit
        )
    }

    fun copyDirToContainer(
        pod: PodResource<Pod, DoneablePod>,
        container: String,
        dirTarGzipInputStream: InputStream,
        path: String,
        awaitTime: Long? = 30,
        awaitTimeUnit: TimeUnit = SECONDS
    ): ExecResult<Status, String> {
        val input = base64EncodedWithEndTerm(dirTarGzipInputStream)

        val copyToPath = "base64 -d - | tar -zxmf - -C $path"
        val inContainer = pod.inContainer(container).readingInput(input)
        return exec(
            inContainer,
            arrayOf("sh", "-c", copyToPath),
            true,
            awaitTime,
            awaitTimeUnit
        )
    }

    private fun base64EncodedWithEndTerm(inputStream: InputStream): ByteArrayInputStream {
        val content = Base64InputStream(inputStream, true).readBytes()
        val input = ByteArrayInputStream(content + end_term)
        return input
    }

    private fun <T> newName(nameTemplate: String, existsProvider: (name: String) -> T?): String {
        var obj: T?
        var name = nameTemplate
        var num = 0;
        do {
            obj = existsProvider.invoke(name)
            if (obj != null) name = "$nameTemplate-${++num}"
        } while (obj == null)
        return name
    }

    private fun newVolumeName(pod: Pod, template: String) =
        newName(template) { volumeName: String -> pod.spec.volumes.firstOrNull { it.name == volumeName } }


    protected fun waitListener(latch: CountDownLatch) = object : ExecListener {
        override fun onOpen(response: Response?) {
        }

        override fun onFailure(t: Throwable?, response: Response?) {
            latch.countDown()
            log.error(t) { "exec error response $response" }
        }

        override fun onClose(code: Int, reason: String?) {
            latch.countDown()
            log.trace { "finish exec code $code, reason $reason" }
        }
    }

    protected fun logTarContent(archiveName: String, stream: InputStream, gzip: Boolean = true) {
        try {
            val writeLog: (msg: () -> Any?) -> Unit = logTar::trace
            val tarStream = if (gzip) GZIPInputStream(stream) else stream
            unpack("tar", tarStream) { entry, tar ->
                val directory = entry.isDirectory
                writeLog {
                    val bytes = tar.readBytes()
                    val read = bytes.size
                    val type = if (directory) "directory" else "file"
                    "$archiveName contains $type ${entry.name}, read $read from ${entry.size}"
                }
            }
        } catch (e: Exception) {
            log.error(e) { "error on tar content logging. $archiveName, gzip: $gzip" }
            throw e
        }
    }

    protected fun unpack(type: String, input: InputStream, handler: (ArchiveEntry, ArchiveInputStream) -> Unit) {
        val archiveStreamFactory = ArchiveStreamFactory()
        val archiveInputStream = archiveStreamFactory.createArchiveInputStream(type, input)
        while (true) {
            val entry: ArchiveEntry? = archiveInputStream.nextEntry
            if (entry != null) handler.invoke(entry, archiveInputStream) else break
        }
    }

    protected fun unpack(type: String, input: InputStream, outputDir: File) =
        unpack(type, input) { entry, archiveInput ->
            val name = entry.name
            val outputFile = File(outputDir, name)
            val path = outputFile.absolutePath
            if (entry.isDirectory) {
                log.trace { "write tar directory $path" }
                if (!outputFile.exists()) {
                    log.trace { "create directory $path" }
                    if (!outputFile.mkdirs()) throw IllegalStateException("Couldn't create directory $path")
                }
            } else {
                log.trace { "write tar file directory $path" }
                val parentFile = outputFile.parentFile
                parentFile.mkdirs()
                FileOutputStream(outputFile).use {
                    archiveInput.copyTo(it)
                }
            }
        }

    protected fun logBuildContent(t: String, inputStream: InputStream) = if (logTar.isTraceEnabled) {
        val debugStream = ByteArrayInputStream(inputStream.readBytes())
        logTarContent("build $t", debugStream)
        debugStream.reset()
        debugStream
    } else inputStream

}

