package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.util.OsInfo
import com.dreamdisplays.util.net.DreamHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** `FFmpeg` binary downloader. **/
object FFmpegBinary {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val CACHE_ROOT = "./dreamdisplays/ffmpeg"
    private const val BTBN_BASE = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest"
    private const val ANDROID_PATH_PROPERTY = "dreamdisplays.ffmpeg.android.path"
    private const val ANDROID_RESOURCE = "/dreamdisplays-ffmpeg/android-arm64/ffmpeg"
    private const val ANDROID_RESOURCE_LIB_DIR = "/dreamdisplays-ffmpeg/android-arm64/lib"

    @Volatile
    private var androidLibraryDirectory: File? = null

    @Volatile
    private var cachedPath: String? = null

    /** Returns the path to a usable `FFmpeg` binary, resolving and caching it on the first call. */
    fun getPath(): String? {
        cachedPath?.let { return it }
        synchronized(this) {
            cachedPath?.let { return it }
            cachedPath = resolve()
            return cachedPath
        }
    }

    /**
     * Resolves the `FFmpeg` binary in the background to minimize latency on first use, and probes
     * its optional filters while it is there — otherwise that probe spawns its own `ffmpeg -filters`
     * synchronously inside the first playback launch, right where latency is most visible.
     */
    fun prewarmAsync() {
        daemon({
            runCatching {
                val path = getPath()
                if (path != null) FFmpegCapabilities.hasFilter(path, "scale_vt")
            }.onFailure { e -> logger.warn("Prewarm failed", e) }
        }, "FFmpeg-prewarm").start()
    }

    /**
     * Checks the cache directory for an existing binary, downloads and extracts one if not found,
     * and falls back to the system `FFmpeg` on any failure.
     */
    private fun resolve(): String? {
        if (OsInfo.isAndroid) return resolveAndroid()

        val p = detectPlatform() ?: run {
            logger.warn("No bundled binary URL for this OS / arch; trying system FFmpeg.")
            return findSystemFfmpeg()
        }

        val cacheDir = File("$CACHE_ROOT/${p.key}")
        val binary = File(cacheDir, p.binaryName)

        if (binary.isFile && binary.length() > 0 && binary.canExecute()) {
            logger.info("Using binary: ${binary.absolutePath}.")
            return binary.absolutePath
        }

        return runCatching {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw IOException("Cannot create cache dir: $cacheDir.")
            }
            downloadAndExtract(p, binary)
            if (!binary.isFile || binary.length() == 0L) {
                throw IOException("Extracted binary is missing or empty.")
            }
            Processes.markExecutable(binary.toPath())
            Processes.removeMacQuarantine(binary.toPath())
            logger.info("Ready to work.")
            binary.absolutePath
        }.getOrElse { e ->
            logger.error("Download failed, falling back to system ffmpeg", e)
            findSystemFfmpeg()
        }
    }

    /** Resolves an app-provided Android executable; Android must use a build compiled with MediaCodec. */
    private fun resolveAndroid(): String? {
        val configured = System.getProperty(ANDROID_PATH_PROPERTY)?.trim().orEmpty()
        val binary = if (configured.isNotEmpty()) {
            File(configured).also { androidLibraryDirectory = File(it.parentFile, "lib") }
        } else extractAndroidResource()
        if (binary == null) {
            logger.error("Android FFmpeg is missing; provide -D$ANDROID_PATH_PROPERTY or package the Android ARM64 resource.")
            return null
        }
        if (!binary.isFile || binary.length() == 0L) {
            logger.error("Configured Android FFmpeg does not exist or is empty: ${binary.absolutePath}.")
            return null
        }
        Processes.markExecutable(binary.toPath())
        if (!supportsMediaCodec(binary.absolutePath)) {
            logger.error("Android FFmpeg does not provide the required MediaCodec hardware backend.")
            return null
        }
        logger.info("Using Android FFmpeg with MediaCodec: ${binary.absolutePath}.")
        return binary.absolutePath
    }

    private fun extractAndroidResource(): File? {
        val destination = File("$CACHE_ROOT/android-arm64/ffmpeg")
        val libraryDirectory = File(destination.parentFile, "lib")
        javaClass.getResourceAsStream(ANDROID_RESOURCE)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return null
            val parent = destination.parentFile
            if (!parent.exists() && !parent.mkdirs()) return null
            if (!destination.isFile || destination.length() != bytes.size.toLong()) {
                val temporary = File(parent, "ffmpeg.tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(destination)) {
                    temporary.delete()
                    return null
                }
            }
            extractAndroidLibraries(libraryDirectory)
            androidLibraryDirectory = libraryDirectory
            return destination
        }
        return null
    }

    private fun extractAndroidLibraries(destination: File): Boolean {
        if (!destination.exists() && !destination.mkdirs()) return false
        var extracted = false
        for (name in ANDROID_FFMPEG_LIBRARIES) {
            val resource = "$ANDROID_RESOURCE_LIB_DIR/$name"
            javaClass.getResourceAsStream(resource)?.use { input ->
                val file = File(destination, name)
                val bytes = input.readBytes()
                if (bytes.isEmpty()) return@use
                if (!file.isFile || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
                extracted = true
            }
        }
        return extracted
    }

    /** Adds the extracted Android FFmpeg shared-library directory to a process environment. */
    internal fun configureProcess(processBuilder: ProcessBuilder): ProcessBuilder {
        if (!OsInfo.isAndroid) return processBuilder
        androidLibraryDirectory?.let { dir ->
            val existing = processBuilder.environment()["LD_LIBRARY_PATH"].orEmpty()
            processBuilder.environment()["LD_LIBRARY_PATH"] =
                if (existing.isEmpty()) dir.absolutePath else "${dir.absolutePath}${File.pathSeparator}$existing"
        }
        return processBuilder
    }

    private val ANDROID_FFMPEG_LIBRARIES = listOf(
        "libavcodec.so", "libavdevice.so", "libavfilter.so", "libavformat.so",
        "libavutil.so", "libpostproc.so", "libswresample.so", "libswscale.so",
    )

    /** Android playback is hardware-only; reject binaries that were built without MediaCodec. */
    private fun supportsMediaCodec(binary: String): Boolean = runCatching {
        val process = configureProcess(ProcessBuilder(binary, "-hide_banner", "-hwaccels"))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        val supported = finished && process.exitValue() == 0 && output.lineSequence().any {
            it.trim().equals("mediacodec", ignoreCase = true)
        }
        if (!supported) {
            logger.error("Android FFmpeg MediaCodec probe failed (exit=${if (finished) process.exitValue() else "timeout"}). Output: ${output.take(4096)}")
        }
        supported
    }.getOrElse { e ->
        logger.warn("Could not probe Android FFmpeg MediaCodec support.", e)
        false
    }

    /** Downloads the archive for [p] to a temp file, extracts the binary to [destBinary], and cleans up the temp file. */
    @Throws(IOException::class)
    private fun downloadAndExtract(p: Platform, destBinary: File) {
        logger.info("Downloading ${p.url}...")
        val parent = destBinary.parentFile
        val tempArchive = File(parent, "_download" + if (p.isTarXz) ".tar.xz" else ".zip")
        try {
            downloadWithRedirects(p.url, tempArchive)
            logger.info("Downloaded ${tempArchive.length()} bytes, extracting '${p.entrySuffix}'...")
            if (p.isTarXz) extractFromTarXz(tempArchive, p.entrySuffix, destBinary)
            else extractFromZip(tempArchive, p.entrySuffix, destBinary)
        } finally {
            if (tempArchive.exists() && !tempArchive.delete()) tempArchive.deleteOnExit()
        }
    }

    /** Downloads [url] to [dest], following up to 10 HTTP redirects manually (GitHub releases use multiple hops). */
    @Throws(IOException::class)
    private fun downloadWithRedirects(url: String, dest: File) {
        DreamHttpClient.downloadToFile(
            url,
            dest.toPath(),
            DreamHttpClient.RequestOptions(
                headers = DreamHttpClient.headersOf("User-Agent" to "DreamDisplays-ffmpeg-bootstrap"),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 300_000,
            ),
        )
    }

    /** Extracts the first ZIP entry whose name ends with [suffix] from [archive] to [dest]. */
    @Throws(IOException::class)
    private fun extractFromZip(archive: File, suffix: String, dest: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(suffix)) {
                    BufferedOutputStream(FileOutputStream(dest)).use { out -> zis.transferTo(out) }
                    return
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        throw IOException("'$suffix' not found in ${archive.name}.")
    }

    /** Extracts the first tar.xz entry whose name ends with [suffix] from [archive] to [dest]. */
    @Throws(IOException::class)
    private fun extractFromTarXz(archive: File, suffix: String, dest: File) {
        BufferedInputStream(FileInputStream(archive)).use { fis ->
            XZCompressorInputStream(fis).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.endsWith(suffix)) {
                            BufferedOutputStream(FileOutputStream(dest)).use { out -> tar.transferTo(out) }
                            return
                        }
                        e = tar.nextEntry
                    }
                }
            }
        }
        throw IOException("'$suffix' not found in ${archive.name}.")
    }

    /** Scans well-known system paths for a working `ffmpeg` binary; returns null if none responds with exit 0. */
    private fun findSystemFfmpeg(): String? {
        val candidates = arrayOf("ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")
        for (candidate in candidates) {
            try {
                val p = ProcessBuilder(candidate, "-version").redirectErrorStream(true).start()
                daemon({
                    try {
                        p.inputStream.transferTo(OutputStream.nullOutputStream())
                    } catch (_: Exception) {
                    }
                }, "FFmpeg-version-drain").start()
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    logger.info("Using system ffmpeg: $candidate...")
                    return candidate
                }
                p.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        logger.error("FFmpeg not found (no download succeeded, no system binary).")
        return null
    }

    /** Returns a [Platform] descriptor for the current OS and architecture, or null if no bundled build is available. */
    private fun detectPlatform(): Platform? {
        val isArm = OsInfo.isArm
        return when {
            OsInfo.isAndroid -> null
            OsInfo.isWindows -> if (isArm) null else
                Platform(
                    "windows-x64",
                    "$BTBN_BASE/ffmpeg-master-latest-win64-gpl.zip",
                    "ffmpeg.exe",
                    "/bin/ffmpeg.exe",
                    false
                )

            OsInfo.isMac -> if (isArm)
                Platform("macos-aarch64", "https://www.osxexperts.net/ffmpeg71arm.zip", "ffmpeg", "ffmpeg", false)
            else
                Platform("macos-x64", "https://evermeet.cx/ffmpeg/getrelease/zip", "ffmpeg", "ffmpeg", false)

            else -> if (isArm)
                Platform(
                    "linux-aarch64",
                    "$BTBN_BASE/ffmpeg-master-latest-linuxarm64-gpl.tar.xz",
                    "ffmpeg",
                    "/bin/ffmpeg",
                    true
                )
            else
                Platform(
                    "linux-x64",
                    "$BTBN_BASE/ffmpeg-master-latest-linux64-gpl.tar.xz",
                    "ffmpeg",
                    "/bin/ffmpeg",
                    true
                )
        }
    }

    private data class Platform(
        val key: String,
        val url: String,
        val binaryName: String,
        val entrySuffix: String,
        val isTarXz: Boolean,
    )
}
