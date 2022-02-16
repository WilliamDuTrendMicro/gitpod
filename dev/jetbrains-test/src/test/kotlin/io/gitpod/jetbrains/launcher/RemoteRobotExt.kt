package io.gitpod.jetbrains.launcher

import com.intellij.remoterobot.RemoteRobot

fun RemoteRobot.isAvailable(): Boolean = runCatching {
    callJs<Boolean>("true")
}.getOrDefault(false)