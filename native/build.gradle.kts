fun cargoExecutable(): String {
    val inHome = File(System.getProperty("user.home"), ".cargo/bin/cargo")
    return if (inHome.canExecute()) inHome.absolutePath else "cargo"
}

tasks.register<Exec>("buildHostNatives") {
    group = "native"
    description = "Builds the host Rust native libraries (release) into native/target/release for the client to bundle."
    val dir = projectDir
    val cargo = cargoExecutable()
    workingDir = dir
    environment.remove("DEVELOPER_DIR")
    commandLine(cargo, "build", "--release")
    doFirst { logger.lifecycle("Building host natives with '$cargo' in $dir...") }
}

tasks.register<Exec>("testHostNatives") {
    group = "native"
    description = "Runs the Rust native test suite (cargo test)."
    workingDir = projectDir
    environment.remove("DEVELOPER_DIR")
    commandLine(cargoExecutable(), "test")
}

tasks.register<Exec>("buildAndroidNatives") {
    group = "native"
    description = "Builds the Rust native libraries for Android ARM64 using the Android NDK."
    workingDir = projectDir
    val ndk = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT")
    val hostTag = when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x86_64"
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val linkerName = if (hostTag.startsWith("windows")) "aarch64-linux-android21-clang.cmd" else "aarch64-linux-android21-clang"
    val linker = ndk?.let { File(it, "toolchains/llvm/prebuilt/$hostTag/bin/$linkerName") }
    doFirst {
        if (linker == null) throw GradleException("ANDROID_NDK_HOME or ANDROID_NDK_ROOT is required for Android natives.")
        if (!linker.isFile) throw GradleException("Android NDK linker not found: $linker")
        logger.lifecycle("Building Android ARM64 natives with '$linker'...")
    }
    if (linker != null) environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.absolutePath)
    commandLine(cargoExecutable(), "build", "--release", "--target", "aarch64-linux-android")
}

tasks.register<Copy>("stageAndroidNatives") {
    group = "native"
    description = "Stages Android ARM64 Rust libraries for inclusion in the Fabric jar."
    dependsOn("buildAndroidNatives")
    from(layout.buildDirectory.dir("../target/aarch64-linux-android/release")) {
        include("libdreamdisplays_native.so", "libdreamdisplays_lav.so")
    }
    into(layout.buildDirectory.dir("ci-bundle/dreamdisplays-natives/android-arm64"))
}
