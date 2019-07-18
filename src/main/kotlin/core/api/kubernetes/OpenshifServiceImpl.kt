package core.api.kubernetes

import core.api.kubernetes.KubernetesUtils.kuberName
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.openshift.api.model.Build
import io.fabric8.openshift.api.model.BuildStatus
import io.fabric8.openshift.client.OpenShiftClient
import okhttp3.OkHttpClient
import org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
import org.springframework.http.HttpStatus.NO_CONTENT
import org.springframework.http.ResponseEntity
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

class OpenshifServiceImpl(
    client: OpenShiftClient,
    httpClient: OkHttpClient,
    properties: OpenshiftProperties
) : BaseKubernetesServiceImpl<OpenShiftClient, OpenshiftProperties>(
    client,
    httpClient,
    properties
) {

    override fun checkLocalImage(image: String): String? {
        val imageWithTag = image.split(":")
        val registryWithImage = imageWithTag[0]
        val withRegistry = registryWithImage.contains("/")
        if (withRegistry) {
            log.trace { "$image is not ImageStream" }
            return null
        } else {
            val tag = if (imageWithTag.size > 1) imageWithTag[1] else "latest"
            val imageStreamName = kuberName(registryWithImage)
            val imageStreamResource = client.imageStreams().withName(imageStreamName)
            val imageStream = try {
                imageStreamResource.get()
            } catch (e: KubernetesClientException) {
                log.error(e) { "checkLocalImage error for image $image" }
                null
            }
            val status = imageStream?.status
            val tagExists = status?.tags?.find { it.tag == tag }
            return if (tagExists != null) {
                val dockerImageRepository = status.dockerImageRepository
                log.trace { "$image is local image $dockerImageRepository" }
                dockerImageRepository
            } else {
                log.trace { "$image doesn't have  " + if (status != null) "tag $tag" else "ImageStream" }
                null
            }
        }
    }

    override fun build(t: String, inputStream: InputStream, outputStream: OutputStream): ResponseEntity<out Any> {
        val imageStreamName = kuberName(t)
        val buildConfigName = "build-image-$imageStreamName"
        val buildConfigs = client.buildConfigs()
        val buildConfigResource = buildConfigs.withName(buildConfigName)
        val imageStreamNameVersion = "$imageStreamName:latest"
        val builder =
            buildConfigResource.createOrReplaceWithNew().withNewMetadata().withNewName(buildConfigName).endMetadata()
                .withNewSpec()
                .withNewSource().withNewBinary().endBinary().endSource()
                .withNewOutput().withNewTo().withNewKind("ImageStreamTag").withNewName(imageStreamNameVersion).endTo()
                .endOutput()
                .withNewStrategy().withNewDockerStrategy().endDockerStrategy().endStrategy()
                .endSpec()
        val done = builder.done()
        log.trace { "BuildConfig $buildConfigName has been created successfully. $done" }

        var stream = logBuildContent(t, inputStream)
        if (stream !is ByteArrayInputStream) {
            stream = ByteArrayInputStream(inputStream.readBytes())
        }

        val imageStream = client.imageStreams().createOrReplaceWithNew()
            .withNewMetadata().withNewName(imageStreamName).endMetadata()
            .done()

        log.trace { "creating image stream success, $imageStream" }

        //workaround for logging of request's input stream
        val repeatableStream = object : InputStream() {
            var reset = false;
            override fun read(): Int {
                val read = stream.read()
                if (read == -1) {
                    log.debug { "image $t stream has been reset" }
                    reset = true
                    stream.reset()
                }
                return read
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reset) {
                    reset = false
                    log.trace { "clear reset flag for image $t" }
                    return -1
                }
                return super.read(b, off, len)
            }
        }

        val build = buildConfigResource.instantiateBinary().fromInputStream(repeatableStream)
        val status = build.status
        val httpStatus = if (status.phase == "Running") {
            val buildName = build.metadata.name
            val buildResource = client.builds().withName(buildName)
            var buildStatus: BuildStatus
            var buildRequested: Build
            do {
                buildRequested = buildResource.get()
                buildStatus = buildRequested.status
            } while ("Running" == buildStatus.phase)
            val phase = buildStatus.phase
            if ("Complete" == phase) {
                log.debug { "build success, status $buildStatus" }
                NO_CONTENT
            } else {
                log.error { "build error, phase $phase, status $buildStatus" }
                INTERNAL_SERVER_ERROR
            }
        } else {
            log.error { "instantiateBinary error, $buildConfigName, status $status" }
            INTERNAL_SERVER_ERROR
        }
        return ResponseEntity.status(httpStatus).build()
    }

    override fun pull(imageName: String, outputStream: OutputStream): ResponseEntity<out Any> {
        return ResponseEntity.ok().build()
    }

    override fun inspectImage(imageName: String): ResponseEntity<out Any> {
        return ResponseEntity.notFound().build()
    }
}
