package bridge.core.api.docker

import com.fasterxml.jackson.databind.ObjectMapper
import bridge.core.api.LocalDockerProperties
import okhttp3.OkHttpClient
import okhttp3.Request

class DockerRegistryService(
    protected val properties: LocalDockerProperties,
    val httpClient: OkHttpClient,
    protected val objectMapper: ObjectMapper
) {
    private val scheme = if (properties.insecure) "http" else "https"
    val registry = properties.registry
    val url = "$scheme://$registry"

    fun isImageExists(name: String): Boolean {
        val request = urlBuilder("GET", "/v2/_catalog").build()
        val src = call(request)
        val map: Map<String, List<String>> = objectMapper.readerFor(Map::class.java).readValue(src)
        val repositories = map["repositories"]
        val isExists = repositories?.contains(name)
        return isExists ?: false
    }

    private fun call(request: Request): String? {
        val call = httpClient.newCall(request)
        val execute = call.execute()
        val body = execute.body()
        val source = body!!.source()
        val byteString = source.readByteString()
        return byteString.utf8()
    }

    private fun urlBuilder(method: String, path: String) = Request.Builder().url(url + path).method(method, null)


}