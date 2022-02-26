package io.gitpod.jetbrains.remote

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.jediterm.terminal.Questioner
import com.jediterm.terminal.TtyConnector
import org.jetbrains.plugins.terminal.cloud.CloudTerminalProcess
import org.jetbrains.plugins.terminal.cloud.CloudTerminalRunner
import java.io.IOException
import java.io.InputStreamReader
import java.lang.Exception
import java.lang.RuntimeException
import java.nio.charset.Charset

@Suppress("UnstableApiUsage")
class GitpodTerminalRunner(project: Project, pipeName: @NlsSafe String, process: CloudTerminalProcess?) : CloudTerminalRunner(project, pipeName, process) {
    override fun createTtyConnector(process: CloudTerminalProcess?): TtyConnector {
        return object : TtyConnector {
            private val inputStreamReader: InputStreamReader = InputStreamReader(process!!.inputStream)

            override fun init(q: Questioner): Boolean {
                return true
            }

            override fun close() {
                try {
                    process?.outputStream?.close()
                    process!!.inputStream.close()
                } catch (e: Exception) {
                    throw RuntimeException("Unable to close streams.", e)
                }
            }

            override fun getName(): String {
                return "TtyConnector"
            }

            @Throws(IOException::class)
            override fun read(buf: CharArray, offset: Int, length: Int): Int {
                return inputStreamReader.read(buf, offset, length)
            }

            @Throws(IOException::class)
            override fun write(bytes: ByteArray) {
                process?.outputStream?.write(bytes)
                process?.outputStream?.flush()
            }

            override fun isConnected(): Boolean {
                return true
            }

            @Throws(IOException::class)
            override fun write(string: String) {
                write(string.toByteArray(Charset.defaultCharset()))
            }

            override fun waitFor(): Int {
                return 0
            }

            override fun ready(): Boolean {
                return true
            }
        }
    }
}