package bridge.core.api

import org.springframework.http.ResponseEntity
import java.io.InputStream
import java.io.OutputStream

interface ContainerService {
    fun logs(name: String, out: OutputStream)

    fun stop(name: String): ResponseEntity<out Any>
    fun kill(name: String): ResponseEntity<out Any>
    fun start(name: String): ResponseEntity<out Any>
    fun delete(name: String): ResponseEntity<out Any>
    fun wait(name: String): ResponseEntity<out Any>
    fun createContainer(name: String?, createRequest: CreateRequest): ResponseEntity<out Any>

    fun build(t: String, inputStream: InputStream, outputStream: OutputStream): ResponseEntity<out Any>
    fun pull(imageName: String, outputStream: OutputStream): ResponseEntity<out Any>
    fun version(): ResponseEntity<out Any>
    fun inspectImage(imageName: String): ResponseEntity<out Any>
    fun putTar(name: String, path: String, inputStream: InputStream): ResponseEntity<out Any>
    fun getTar(name: String, path: String, outputStream: OutputStream): ResponseEntity<out Any>

}