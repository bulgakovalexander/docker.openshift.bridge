package core.api.kubernetes

import mu.KotlinLogging
import java.io.OutputStream
import java.util.*

class ContainerAttachOutputStream(private val out: OutputStream) : OutputStream() {
    companion object {
        val log = KotlinLogging.logger {}
    }

    private fun writeFrame(frameContent: ByteArray, off: Int = 0, frameSize: Int = frameContent.size) {
        val frameHeader = ByteArray(8)
        frameHeader[0] = BaseKubernetesServiceImpl.STD_OUT
        frameHeader[4] = (frameSize and 0x7F000000 shr 24).toByte()
        frameHeader[5] = (frameSize and 0x00FF0000 shr 16).toByte()
        frameHeader[6] = (frameSize and 0x0000FF00 shr 8).toByte()
        frameHeader[7] = (frameSize and 0x000000FF).toByte()

        log.trace {
            "frame size $frameSize, header ${Arrays.toString(frameHeader)}, content ${Arrays.toString(
                frameContent
            )}"
        }

        out.write(frameHeader)
        out.write(frameContent, off, frameSize)
        out.flush()
    }

    override fun write(b: Int) = writeFrame(byteArrayOf(b.toByte()))
    override fun write(b: ByteArray, off: Int, len: Int) = writeFrame(b, off, len)
    override fun flush() = out.flush()
    override fun close() = out.close()
}