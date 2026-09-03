package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.media.player.process.AndroidPluginLibs
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Drop-in replacement for the old AudioTrack / OpenAL wrapper.
 *
 * Backed entirely by [libaaudio_sink.so] loaded from the FFmpegPlugin APK.
 * The native C signatures exported by the FFmpegPlugin APK are loaded through JNA:
 *
 *   long  aaudio_open(int sampleRate, int bufferSizeFrames)
 *   void  aaudio_close(long handle)
 *   int   aaudio_write(long handle, byte[] buf, int offset, int len)
 *   void  aaudio_flush(long handle)
 *   void  aaudio_pause(long handle)
 *   void  aaudio_resume(long handle)
 *   long  aaudio_frames_written(long handle)
 *   int   aaudio_buffer_size(long handle)          // in frames
 *   long  aaudio_get_timestamp(long handle)        // wall-clock nanos of last played frame
 *
 * [AudioSink] continues to call the same Kotlin API it always did — nothing
 * in that file needs to change.
 */
internal class AudioTrackWrapper private constructor(private val handle: Pointer, private val api: AAudioApi) {
    private val released = AtomicBoolean(false)
    private val nativeLock = ReentrantReadWriteLock()

    // ── AudioSink-facing API (mirrors the old AudioTrack surface) ────────────

    /**
     * Number of frames the hardware/AAudio buffer can hold.
     * Replaces `AudioTrack.bufferSizeInFrames`.
     */
    val bufferSizeInFrames: Int
        get() = nativeLock.readLock().withLock {
            if (released.get()) 0 else api.aaudio_buffer_size(handle)
        }

    /** Total frames written to the sink, used as the low-overhead playback clock. */
    val playbackHeadPosition: Int
        get() = nativeLock.readLock().withLock {
            if (released.get()) 0 else (api.aaudio_frames_written(handle) and 0x7FFF_FFFFL).toInt()
        }

    /**
     * Wall-clock nanoseconds of the most recently rendered frame, or -1 if
     * unavailable.  AudioSink does not call this directly but it is available
     * for callers who want a more accurate clock than the frame-count estimate.
     */
    val timestampNanos: Long
        get() = nativeLock.readLock().withLock {
            if (released.get()) -1L
            else {
                val position = longArrayOf(0L)
                val nanos = longArrayOf(0L)
                if (api.aaudio_get_timestamp(handle, position, nanos) == 0) nanos[0] else -1L
            }
        }

    /** Start / resume playback.  Replaces `AudioTrack.play()`. */
    fun play() { nativeLock.readLock().withLock { if (!released.get()) api.aaudio_resume(handle) } }

    /** Pause playback without discarding buffered audio.  Replaces `AudioTrack.stop()` (soft-pause). */
    fun pause() { nativeLock.readLock().withLock { if (!released.get()) api.aaudio_pause(handle) } }

    /**
     * Discard all buffered audio immediately.
     * Replaces `AudioTrack.flush()`.
     * After a flush the frame counter resets — callers must reset their own
     * wrap-state too (AudioSink already does this before every flush call).
     */
    fun flush() { nativeLock.readLock().withLock { if (!released.get()) api.aaudio_flush(handle) } }

    /**
     * Write up to [length] bytes from [audioData] starting at [offsetInBytes].
     * Returns the number of bytes consumed (≥ 0) or a negative error code.
     * Replaces `AudioTrack.write(byte[], int, int)`.
     */
    fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int = nativeLock.readLock().withLock {
        if (released.get() || sizeInBytes <= 0) 0 else {
            val data = if (offsetInBytes == 0 && sizeInBytes == audioData.size) audioData
            else audioData.copyOfRange(offsetInBytes, offsetInBytes + sizeInBytes)
            api.aaudio_write(handle, data, data.size / AudioSink.BYTES_PER_FRAME, 100_000_000L)
                .coerceAtLeast(0) * AudioSink.BYTES_PER_FRAME
        }
    }

    /**
     * Stop playback and release all native resources.
     * Replaces `AudioTrack.release()`.  Must not be called more than once.
     */
    fun release() {
        nativeLock.writeLock().withLock {
            if (released.compareAndSet(false, true)) api.aaudio_close(handle)
        }
    }

    // ── Static factory ───────────────────────────────────────────────────────

    companion object {
        private val logger = LoggerFactory.getLogger(AudioTrackWrapper::class.java)

        private val api: AAudioApi? by lazy {
            runCatching {
                val path = AndroidPluginLibs.aaudioSinkPath()
                    ?: error("POJAV_FFMPEG_PATH sibling libaaudio_sink.so was not found")
                Native.load(path, AAudioApi::class.java)
            }.onSuccess { logger.info("[AudioTrackWrapper] Loaded AAudio C API from FFmpeg plugin.") }
                .onFailure { logger.error("[AudioTrackWrapper] Failed to load libaaudio_sink.so: ${it.message}") }
                .getOrNull()
        }

        /**
         * Returns the minimum sensible buffer size in bytes for the given
         * sample rate, or a safe default if the native layer cannot tell us.
         *
         * AudioSink calls this before [open] to pick a buffer size.
         */
        fun getMinBufferSize(sampleRate: Int): Int {
            // AAudio manages its own internal buffer; we just give AudioSink a
            // reasonable lower bound (equivalent to ~100 ms of stereo 16-bit PCM).
            val minBytes = sampleRate * AudioSink.BYTES_PER_FRAME / 10   // 100 ms
            return minBytes.coerceAtLeast(4096)
        }

        /**
         * Opens a new AAudio stream at [sampleRate] Hz, stereo, signed 16-bit.
         * [bufferSizeBytes] is the requested buffer size in bytes; converted to
         * frames for the native call.
         *
         * Returns null if the native layer fails to open the stream.
         */
        fun open(sampleRate: Int, bufferSizeBytes: Int): AudioTrackWrapper? {
            val nativeApi = api ?: return null
            val handle = nativeApi.aaudio_open(sampleRate, 2)
            if (handle == null || Pointer.nativeValue(handle) == 0L) {
                logger.warn("[AudioTrackWrapper] aaudio_open returned null handle.")
                return null
            }
            logger.debug("[AudioTrackWrapper] aaudio_open ok — handle=$handle, requestedBytes=$bufferSizeBytes")
            return AudioTrackWrapper(handle, nativeApi)
        }

        private interface AAudioApi : Library {
            fun aaudio_open(sampleRate: Int, channelCount: Int): Pointer?
            fun aaudio_close(handle: Pointer?)
            fun aaudio_write(handle: Pointer?, data: ByteArray, frames: Int, timeoutNs: Long): Int
            fun aaudio_get_timestamp(handle: Pointer?, position: LongArray, nanos: LongArray): Int
            fun aaudio_frames_written(handle: Pointer?): Long
            fun aaudio_pause(handle: Pointer?): Int
            fun aaudio_resume(handle: Pointer?): Int
            fun aaudio_flush(handle: Pointer?): Int
            fun aaudio_buffer_size(handle: Pointer?): Int
        }
    }
}