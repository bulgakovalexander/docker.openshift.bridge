package bridge.core.api

import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.util.concurrent.TimeUnit

abstract class BaseKubernetesProperties {
    var masterUrl: String = "http://localhost:7070";
    var namespace: String? = "default"
    @NestedConfigurationProperty
    val auth = Auth()
    @NestedConfigurationProperty
    val dockerVersion = DockerVersion()
    var initContainerImage = "busybox:latest"
    var imagePullPolicy = "IfNotPresent"
    var restartPolicy = "Never"
    var sharedFilesPath = "/tmp/shared"
    @NestedConfigurationProperty
    val exitCodeFile = ExitCodeFile()
    @NestedConfigurationProperty
    val initFilesScript = InitFilesScript()

    var connectionTimeout = 20L
    var connectionTimeoutUnit = TimeUnit.SECONDS

    var requestTimeout = 20L
    var requestTimeoutUnit = TimeUnit.SECONDS

    var websocketTimeout = 2L
    var websocketTimeoutUnit = TimeUnit.MINUTES

    var websocketPingInterval = 10L
    var websocketPingIntervalUnit = TimeUnit.SECONDS

    var trustCerts = false
    var disableHostnameVerification = true
    var disableDeleting = false
}

open class DockerVersion {
    var apiVersion = "1.40"
}

open class InitFilesScript {
    var name = "init.sh"
    var executableRights = "chmod u+x"
    var shebang = "#!/bin/sh"
}

open class ExitCodeFile {
    var name = "/var/mainCmdExitCode"
    var writeExitCode = ""
        get() = if (field.isEmpty()) "echo \$? >> $name" else field
    var checkFileExists = ""
        get() = if (field.isEmpty()) "if [ -f $name ]; then exit 0; else exit 1; fi" else field
    var extractExitStatus = ""
        get() = if (field.isEmpty()) "exit \$(cat $name)" else field
}