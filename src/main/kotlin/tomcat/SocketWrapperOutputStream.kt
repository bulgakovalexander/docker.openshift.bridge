package tomcat
/*
import org.apache.tomcat.util.net.SocketWrapperBase
import java.io.OutputStream

class SocketWrapperOutputStream(private val socket: SocketWrapperBase<out Any>, private val block: Boolean = true) : OutputStream() {
    override fun write(b: Int) {
        socket.write(block, byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        socket.write(block, b, off, len)
    }

    override fun flush() {
        socket.flush(block)
    }

    override fun close() {
        socket.close()
    }
}
*/