package bridge.core.api

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty

//@JsonAutoDetect(fieldVisibility = PUBLIC_ONLY)
open class AnyPropertied {
    @JsonAnySetter
    var anyProperties = HashMap<String, Any>()
        @JsonAnyGetter get() = field
        set(anyProperties) {
            field = anyProperties
        }
}


class CreateRequest : AnyPropertied() {
    @JsonProperty("Env")
    var env: List<String> = ArrayList()
    @JsonProperty("Cmd")
    var cmd: List<String> = ArrayList()
    @JsonProperty("Image")
    lateinit var image: String
    @JsonProperty("Entrypoint")
    var entrypoint: String? = null
    @JsonProperty("HostConfig")
    var hostConfig: HostConfig? = null

    override fun toString(): String {
        return "CreateRequest(env=$env, cmd=$cmd, image='$image', entrypoint=$entrypoint, hostConfig=$hostConfig)"
    }

}

class RestartPolicy : AnyPropertied() {
    override fun toString(): String {
        return "RestartPolicy()"
    }
}

class HostConfig : AnyPropertied() {
    @JsonProperty("NetworkMode")
    var networkMode: String? = null
    @JsonProperty("RestartPolicy")
    var restartPolicy: RestartPolicy? = null
    @JsonProperty("LogConfig")
    var logConfig: LogConfig? = null
    @JsonProperty("Memory")
    var memory: Long? = null

    override fun toString(): String {
        return "HostConfig(networkMode=$networkMode, restartPolicy=$restartPolicy, logConfig=$logConfig, memory=$memory)"
    }

}

class LogConfig : AnyPropertied() {
    @JsonProperty("Type")
    var type = "json-file"
    @JsonProperty("Config")
    var config = HashMap<String, String>()

    override fun toString(): String {
        return "LogConfig(type='$type', config=$config)"
    }

}

class Config : AnyPropertied() {
    @JsonProperty("max-file")
    var maxFile: String? = null
    @JsonProperty("max-size")
    var maxSize: String? = null

    override fun toString(): String {
        return "Config(maxFile=$maxFile, maxSize=$maxSize)"
    }

}