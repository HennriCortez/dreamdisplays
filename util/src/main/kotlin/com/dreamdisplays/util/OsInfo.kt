package com.dreamdisplays.util

import java.util.*

/**
 * Single source of truth for OS / architecture detection.
 */
object OsInfo {
    private val os: String = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
    private val osVersion: String = System.getProperty("os.version", "").lowercase(Locale.ENGLISH)
    private val arch: String = System.getProperty("os.arch", "").lowercase(Locale.ENGLISH)
    private val runtime: String = System.getProperty("java.runtime.name", "").lowercase(Locale.ENGLISH)
    private val vm: String = System.getProperty("java.vm.name", "").lowercase(Locale.ENGLISH)

    val isWindows: Boolean = "win" in os
    val isMac: Boolean = "mac" in os
    val isAndroid: Boolean = "android" in runtime || "android" in osVersion || "dalvik" in vm || "art" in vm || hasAndroidRuntime()

    val isLinux: Boolean = "nux" in os || "nix" in os
    val isArm: Boolean = "aarch64" in arch || "arm64" in arch || "arm" in arch
    val isArm64: Boolean = "aarch64" in arch || "arm64" in arch

    private fun hasAndroidRuntime(): Boolean = runCatching {
        Class.forName("android.os.Build", false, OsInfo::class.java.classLoader)
        true
    }.getOrDefault(false)
}
