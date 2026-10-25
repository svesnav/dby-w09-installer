package org.matepad.installer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/** Commands as root through su (APatch, Magisk or KernelSU). */
object Root {
    class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
        fun message() = err.trim().ifEmpty { out.trim() }.ifEmpty { "exit code $code" }
    }

    class Failed(command: String, result: Result) :
        IOException("${command.substringBefore(' ')}: ${result.message()}")

    private fun start(command: String): Process = ProcessBuilder("su", "-c", command).start()

    private fun drain(stream: InputStream, into: StringBuilder) = thread(isDaemon = true) {
        stream.bufferedReader().useLines { lines -> lines.forEach { synchronized(into) { into.appendLine(it) } } }
    }

    /** Runs the command, stdout split into lines as they come. */
    fun run(command: String, onLine: (String) -> Unit = {}): Result {
        val p = start(command)
        p.outputStream.close()
        val err = StringBuilder()
        val errThread = drain(p.errorStream, err)
        val out = StringBuilder()
        p.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { out.appendLine(it); onLine(it) }
        }
        val code = p.waitFor()
        errThread.join()
        return Result(code, out.toString(), err.toString())
    }

    fun check(command: String, onLine: (String) -> Unit = {}): Result =
        run(command, onLine).also { if (!it.ok) throw Failed(command, it) }

    /** The command's stdout as bytes (for partitions and files only root can read). */
    fun read(command: String): ByteArray {
        val p = start(command)
        p.outputStream.close()
        val err = StringBuilder()
        val errThread = drain(p.errorStream, err)
        val bytes = ByteArrayOutputStream()
        p.inputStream.use { it.copyTo(bytes, 1 shl 20) }
        val code = p.waitFor()
        errThread.join()
        if (code != 0) throw Failed(command, Result(code, "", err.toString()))
        return bytes.toByteArray()
    }

    /** Feeds the command's stdin from [writer] (e.g. dd of=...). */
    fun pipe(command: String, writer: (OutputStream) -> Unit): Result {
        val p = start(command)
        val err = StringBuilder()
        val errThread = drain(p.errorStream, err)
        val out = StringBuilder()
        val outThread = drain(p.inputStream, out)
        try {
            p.outputStream.buffered(1 shl 20).use(writer)
        } catch (e: IOException) {
            p.destroy()
            p.waitFor()
            errThread.join()
            throw IOException("${command.substringBefore(' ')}: ${err.toString().trim().ifEmpty { e.message }}", e)
        }
        val code = p.waitFor()
        errThread.join()
        outThread.join()
        val result = Result(code, out.toString(), err.toString())
        if (!result.ok) throw Failed(command, result)
        return result
    }

    fun write(path: String, data: ByteArray) {
        pipe("cat > $path") { it.write(data) }
    }

    fun available(): Boolean = try {
        run("id -u").out.trim() == "0"
    } catch (e: IOException) {
        false
    }
}
