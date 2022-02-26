package io.gitpod.jetbrains.remote

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.jediterm.terminal.Questioner
import com.jediterm.terminal.TtyConnector
import com.jetbrains.rdserver.terminal.BackendTerminalManager
import io.gitpod.supervisor.api.TerminalOuterClass
import io.gitpod.supervisor.api.TerminalServiceGrpc
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.asDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalView
import java.io.*
import java.nio.charset.Charset


@Suppress("UnstableApiUsage")
class GitpodToolWindowManagerListener(private val project: Project) : ToolWindowManagerListener {
    override fun toolWindowsRegistered(ids: MutableList<String>, toolWindowManager: ToolWindowManager) {
        if (ids.contains(TerminalToolWindowFactory.TOOL_WINDOW_ID)) {
            debug("ToolWindow '${TerminalToolWindowFactory.TOOL_WINDOW_ID}' has been registered on project '${project.name}'.")
            runBlocking {
                launch {
                    mirrorSupervisorTerminals()
                }
            }
        }
    }

    private val terminalView = TerminalView.getInstance(project)
    private val backendTerminalManager = BackendTerminalManager.getInstance(project)
    private val terminalServiceStub = TerminalServiceGrpc.newStub(GitpodManager.supervisorChannel)
    private val terminalServiceFutureStub = TerminalServiceGrpc.newFutureStub(GitpodManager.supervisorChannel)

    private suspend fun mirrorSupervisorTerminals() = coroutineScope {
        val supervisorTerminals = getSupervisorTerminalsAsync().await().terminalsList
        for (supervisorTerminal in supervisorTerminals) {
            createSharedTerminal(supervisorTerminal)
        }
    }

    private fun createSharedTerminal(supervisorTerminal: TerminalOuterClass.Terminal) = runInEdt {
        debug("Creating shared terminal '${supervisorTerminal.title}' on Backend IDE")
        val inputStream = ByteArrayInputStream(ByteArray(4096))
        val outputStream = ByteArrayOutputStream()
        val shellTerminalWidget = terminalView.createLocalShellWidget(project.basePath, supervisorTerminal.title, true)
        shellTerminalWidget.ttyConnector = object : TtyConnector {
            private val inputStreamReader: InputStreamReader = InputStreamReader(inputStream)

            override fun init(q: Questioner): Boolean {
                return true
            }

            override fun close() {
                try {
                    outputStream.close()
                    inputStream.close()
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
                outputStream.write(bytes)
                outputStream.flush()
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

        backendTerminalManager.shareTerminal(shellTerminalWidget, supervisorTerminal.alias)
        connectSupervisorStream(shellTerminalWidget, supervisorTerminal, outputStream)
    }

    private fun connectSupervisorStream(shellTerminalWidget: ShellTerminalWidget, supervisorTerminal: TerminalOuterClass.Terminal, outputStream: OutputStream) {
        val listenTerminalRequest = TerminalOuterClass.ListenTerminalRequest.newBuilder().setAlias(supervisorTerminal.alias).build()

        val terminalResponseObserver = object : StreamObserver<TerminalOuterClass.ListenTerminalResponse> {
            override fun onNext(response: TerminalOuterClass.ListenTerminalResponse?) {
                when (response) {
                    null -> return
                    else -> when {
                        response.hasTitle() -> {
                            val shellTerminalWidgetContent = terminalView.toolWindow.contentManager.getContent(shellTerminalWidget)
                            if (shellTerminalWidgetContent.tabName != response.title) {
                                debug("Renaming '${shellTerminalWidgetContent.tabName}' to '${response.title}'.")
                                shellTerminalWidgetContent.tabName = response.title
                            }
                        }

                        response.hasData() -> {
                            debug("Printing '${response.data.toStringUtf8()}' on '${supervisorTerminal.title}' terminal.")
                            outputStream.write(response.data.toByteArray())
                        }

                        response.hasExitCode() -> {
                            debug("Closing '${supervisorTerminal.title}' terminal (Exit Code: ${response.exitCode}.")
                            shellTerminalWidget.close()
                        }
                    }
                }
            }

            override fun onCompleted() {
                debug("'${supervisorTerminal.title}' terminal finished streaming.")
            }

            override fun onError(e: Throwable?) {
                debug("'${supervisorTerminal.title}' terminal threw error: ${e?.message}.")
            }
        }

        terminalServiceStub.listen(listenTerminalRequest, terminalResponseObserver)
    }

    private fun getSupervisorTerminalsAsync() = terminalServiceFutureStub
            .list(TerminalOuterClass.ListTerminalsRequest.newBuilder().build())
            .asDeferred()

    private fun debug(message: String) {
        if (System.getenv("JB_DEV").toBoolean())
            thisLogger().warn(message)
    }
}