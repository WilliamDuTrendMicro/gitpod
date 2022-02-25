package io.gitpod.jetbrains.remote

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.jetbrains.rdserver.terminal.BackendTerminalManager
import io.gitpod.supervisor.api.TerminalOuterClass
import io.gitpod.supervisor.api.TerminalServiceGrpc
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.guava.asDeferred
import org.jetbrains.plugins.terminal.*
import org.jetbrains.plugins.terminal.cloud.CloudTerminalProcess
import org.jetbrains.plugins.terminal.cloud.CloudTerminalRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
        val inputStream = ByteArrayInputStream("echo hi".toByteArray())
        val outputStream = ByteArrayOutputStream()
        supervisorTerminal.writeTo(outputStream)
        val process = CloudTerminalProcess(outputStream, inputStream)
        val runner = CloudTerminalRunner(project, supervisorTerminal.title, process)
        // This works to open the Terminal Tool Window:
        // terminalView.createNewSession(terminalView.terminalRunner, TerminalTabState().also { it.myTabName = supervisorTerminal.title })
        // But this doesn't:
        terminalView.createNewSession(runner, TerminalTabState().also { it.myTabName = supervisorTerminal.title })
        val shellTerminalWidget = terminalView.widgets.find {
            widget -> terminalView.toolWindow.contentManager.getContent(widget).tabName == supervisorTerminal.title
        } as ShellTerminalWidget
        backendTerminalManager.shareTerminal(shellTerminalWidget, supervisorTerminal.alias)
        connectSupervisorStream(shellTerminalWidget, supervisorTerminal)
    }

    private fun connectSupervisorStream(shellTerminalWidget: ShellTerminalWidget, supervisorTerminal: TerminalOuterClass.Terminal) {
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
                            val message = response.data.toStringUtf8()
                            debug("Printing '${message}' on '${supervisorTerminal.title}' terminal.")
                            shellTerminalWidget.writePlainMessage("${message}\n")
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