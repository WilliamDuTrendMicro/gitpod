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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.guava.asDeferred
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalView

@DelicateCoroutinesApi
class GitpodToolWindowManagerListener(private val project: Project) : ToolWindowManagerListener {
    override fun toolWindowsRegistered(ids: MutableList<String>, toolWindowManager: ToolWindowManager) {
        if (ids.contains(TerminalToolWindowFactory.TOOL_WINDOW_ID)) {
            debug("ToolWindow '${TerminalToolWindowFactory.TOOL_WINDOW_ID}' has been registered on project '${project.name}'.")
            mirrorSupervisorTerminals()
        }
    }

    private val terminalView = TerminalView.getInstance(project)
    private val backendTerminalManager = BackendTerminalManager.getInstance(project)
    private val terminalServiceStub = TerminalServiceGrpc.newStub(GitpodManager.supervisorChannel)
    private val terminalServiceFutureStub = TerminalServiceGrpc.newFutureStub(GitpodManager.supervisorChannel)

    private fun mirrorSupervisorTerminals() {
        GlobalScope.launch {
            val supervisorTerminals = getSupervisorTerminalsAsync().await().terminalsList;
            for (supervisorTerminal in supervisorTerminals) {
                createSharedTerminal(supervisorTerminal)
            }
        }
    }

    private fun createSharedTerminal(supervisorTerminal: TerminalOuterClass.Terminal) {
        runInEdt {
            debug("Creating shared terminal '${supervisorTerminal.title}' on Backend IDE")
            val shellTerminalWidget = terminalView.createLocalShellWidget(supervisorTerminal.currentWorkdir, supervisorTerminal.title, true)
            backendTerminalManager.shareTerminal(shellTerminalWidget, supervisorTerminal.alias)
            connectSupervisorStream(shellTerminalWidget, supervisorTerminal)
        }
    }

    private fun connectSupervisorStream(shellTerminalWidget: ShellTerminalWidget, supervisorTerminal: TerminalOuterClass.Terminal) {
        val listenTerminalRequest = TerminalOuterClass.ListenTerminalRequest.newBuilder().setAlias(supervisorTerminal.alias).build()

        val terminalResponseObserver = object : StreamObserver<TerminalOuterClass.ListenTerminalResponse> {
            override fun onNext(response: TerminalOuterClass.ListenTerminalResponse?) {
                if (response == null) return

                if (response.hasTitle()) {
                    debug("Renaming '${supervisorTerminal.title}' to '${response.title}'.")
                    shellTerminalWidget.terminalPanel.windowTitle = response.title
                } else if (response.hasData()) {
                    val message = response.data.toStringUtf8()
                    debug("Printing '${message}' on '${supervisorTerminal.title}' terminal.")
                    shellTerminalWidget.writePlainMessage("${message}\n")
                } else if (response.hasExitCode()) {
                    debug("Closing '${supervisorTerminal.title}' terminal (Exit Code: ${response.exitCode}.")
                    shellTerminalWidget.close()
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