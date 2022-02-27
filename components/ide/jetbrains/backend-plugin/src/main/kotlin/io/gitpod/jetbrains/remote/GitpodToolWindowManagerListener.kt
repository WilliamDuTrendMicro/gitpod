// Copyright (c) 2022 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License-AGPL.txt in the project root for license information.

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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.asDeferred
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalTabState
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalView
import org.jetbrains.plugins.terminal.cloud.CloudTerminalProcess
import org.jetbrains.plugins.terminal.cloud.CloudTerminalRunner
import java.io.PipedInputStream
import java.io.PipedOutputStream

@DelicateCoroutinesApi
@Suppress("UnstableApiUsage")
class GitpodToolWindowManagerListener(private val project: Project) : ToolWindowManagerListener {
    override fun toolWindowsRegistered(ids: MutableList<String>, toolWindowManager: ToolWindowManager) {
        if (ids.contains(TerminalToolWindowFactory.TOOL_WINDOW_ID)) {
            debug("ToolWindow '${TerminalToolWindowFactory.TOOL_WINDOW_ID}' has been registered on project '${project.name}'.")
            GlobalScope.launch {
                mirrorSupervisorTerminals()
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
        val terminalInputStream = PipedInputStream()
        val terminalInput = PipedOutputStream(terminalInputStream)
        val terminalOutput = PipedInputStream()
        val terminalOutputStream = PipedOutputStream(terminalOutput)

//        val cmd = arrayOf("/bin/sh", "-l")
//        val env: MutableMap<String, String> = HashMap(System.getenv())
//        env["TERM"] = "xterm"
//        val process = PtyProcessBuilder().setCommand(cmd).setEnvironment(env).start()
//
//        val terminalInput = process.outputStream
//        val terminalOutput = process.inputStream

        val runner = CloudTerminalRunner(project, supervisorTerminal.title, CloudTerminalProcess(terminalInput, terminalOutput))
        terminalView.createNewSession(runner, TerminalTabState().also { it.myTabName = supervisorTerminal.title })
        val shellTerminalWidget = terminalView.widgets.find {
            widget -> terminalView.toolWindow.contentManager.getContent(widget).tabName == supervisorTerminal.title
        } as ShellTerminalWidget
        backendTerminalManager.shareTerminal(shellTerminalWidget, supervisorTerminal.alias)
        connectSupervisorStream(shellTerminalWidget, supervisorTerminal, terminalOutputStream)

//        GlobalScope.launch {
//            outputStream.bufferedReader().useLines { lines ->
//                val results = StringBuilder()
//                lines.forEach { results.append(it) }
//                debug(results.toString())

//                val writeTerminalRequest = TerminalOuterClass.WriteTerminalRequest.newBuilder().setAlias(supervisorTerminal.alias).setStdin(ByteString.readFrom(input)).build()
//                val terminalResponseObserver = object : StreamObserver<TerminalOuterClass.WriteTerminalResponse> {
//                    override fun onNext(response: TerminalOuterClass.WriteTerminalResponse?) {
//                        if (response != null) {
//                            debug("bytesWritten = ${response.bytesWritten}")
//                        }
//                    }
//
//                    override fun onError(e: Throwable?) {
//                        debug("'${supervisorTerminal.title}' terminal threw error: ${e?.message}.")
//                    }
//
//                    override fun onCompleted() {
//                        debug("'${supervisorTerminal.title}' terminal finished writing stream.")
//                    }
//
//                }
//
//                terminalServiceStub.write(writeTerminalRequest, terminalResponseObserver)
//            }
//        }
    }

    private fun connectSupervisorStream(shellTerminalWidget: ShellTerminalWidget, supervisorTerminal: TerminalOuterClass.Terminal, terminalOutputStream: PipedOutputStream) {
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
                            debug("Printing a text on '${supervisorTerminal.title}' terminal.")
                            terminalOutputStream.write(response.data.toByteArray())
                        }

                        response.hasExitCode() -> {
                            debug("Closing '${supervisorTerminal.title}' terminal (Exit Code: ${response.exitCode}.")
                            shellTerminalWidget.close()
                        }
                    }
                }
            }

            override fun onCompleted() {
                debug("'${supervisorTerminal.title}' terminal finished reading stream.")
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
