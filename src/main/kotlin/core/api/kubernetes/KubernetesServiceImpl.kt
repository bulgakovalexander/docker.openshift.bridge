package core.api.kubernetes

import core.api.KubernetesProperties
import core.api.docker.DockerRegistryService
import core.api.kubernetes.BaseKubernetesServiceImpl.CreatePodStatus.Started
import core.api.kubernetes.KubernetesUtils.kuberName
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import okhttp3.OkHttpClient
import org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
import org.springframework.http.HttpStatus.NO_CONTENT
import org.springframework.http.ResponseEntity
import java.io.InputStream
import java.io.OutputStream


class KubernetesServiceImpl(
    kubernetesClient: KubernetesClient,
    httpClient: OkHttpClient,
    private val dockerRegistry: DockerRegistryService,
    properties: KubernetesProperties
) : BaseKubernetesServiceImpl<KubernetesClient, KubernetesProperties>(
    kubernetesClient,
    httpClient,
    properties
) {

    override fun checkLocalImage(image: String): String? {
        val exists = dockerRegistry.isImageExists(image)
        return if (exists) dockerRegistry.registry + "/" + image else null
    }

    override fun build(t: String, inputStream: InputStream, outputStream: OutputStream): ResponseEntity<out Any> {
        val podName = kuberName("build-image-$t")

        val imagePullPolicy = properties.imagePullPolicy
        val kaniko = properties.kaniko
        val docker = properties.localDocker
        val contextDir = kaniko.contextDir
        val sharedVolumeName = "shared-files"
        val podsApi = client.pods()
        val podResource = podsApi.withName(podName)
        podResource.delete()

        val registry = docker.registry
        val image = registry + "/" + t + ":" + kaniko.tag
        var args = arrayOf(
            kaniko.entrypoint,
            "--context=$contextDir",
            "--destination=$image",
            "--verbosity=" + kaniko.verbosity
        )

        val dockerfile = kaniko.dockerfile
        if (dockerfile.isNotEmpty()) args += "--dockerfile=$dockerfile"

        if (docker.insecure) args += arrayOf(
            "--insecure=true",
            "--insecure-pull=true",
            "--insecure-registry=$registry"
        )
        if (docker.skipTlsVerify) args += arrayOf("--skip-tls-verify=true", "--skip-tls-verify-pull=true")

        val additionalParameters = kaniko.additionalParameters
        if (additionalParameters != null) args += additionalParameters

        log.debug {
            "build image $t with destination $image. kaniko cmd ${args.reduce { l, r -> "$l $r" }}"
        }

        val stream = logBuildContent(t, inputStream)

        val build = PodBuilder()
            .withNewMetadata().withNewName(podName).endMetadata()
            .withNewSpec()
            .withRestartPolicy(kaniko.restartPolicy)
            .addImageSharedFiles(sharedVolumeName, contextDir)
            .addNewContainer()
            .withNewName(main)
            .addVolumeMount(sharedVolumeName, contextDir)
            .withNewImage(kaniko.image).withNewImagePullPolicy(imagePullPolicy).withArgs(*args)
            .endContainer()
            .endSpec()
            .build()
        podResource.createOrReplace(build)

        val status = waitInitContainerReadyToInvoke(podResource)
        val httpStatus = if (status == Started) {
            val execResult = copyDirToContainer(podResource,
                shared_files, stream, contextDir)
            if (execResult.status.code == 500) {
                log.error { "copyDirToContainer error $execResult" }
                INTERNAL_SERVER_ERROR
            } else {
                val containerState = finishSharedFilesAndStartMainContainer(podResource)
                log.trace { "main container started, state $containerState" }
                if (containerState.waiting != null) {
                    log.error { "startMainContainer waiting error $containerState" }
                    INTERNAL_SERVER_ERROR
                } else {
                    val terminated = containerState.terminated
                    if (terminated != null) {
                        if (terminated.exitCode > 0) {
                            log.error { "startMainContainer terminated error $containerState" }
                            INTERNAL_SERVER_ERROR
                        } else NO_CONTENT
                    } else {
                        val containerStatus = waitContainerStatus(podResource, waitReady = false)
                        val statusOnFinish = containerStatus.state
                        val terminatedOnFinish = statusOnFinish.terminated
                        if (terminatedOnFinish != null && terminatedOnFinish.exitCode == 0) NO_CONTENT
                        else {
                            log.error { "waitContainerStatus terminated error $statusOnFinish" }
                            INTERNAL_SERVER_ERROR
                        }
                    }
                }
            }
        } else {
            log.error { "waitContainerStatus init containers status $status" }
            INTERNAL_SERVER_ERROR
        }

        val podLog = podResource.getLog(true)
        if (httpStatus == NO_CONTENT) {
            log.trace { "build image $t log\n$podLog" }
            podResource.delete()
        } else {
            log.error { "build image $t error\n$podLog" }
        }

        return ResponseEntity.status(httpStatus).build()
    }

    override fun pull(imageName: String, outputStream: OutputStream) = ResponseEntity.ok().build<Any>()

    override fun inspectImage(imageName: String): ResponseEntity<Any> {
        return ResponseEntity.notFound().build()
    }

}