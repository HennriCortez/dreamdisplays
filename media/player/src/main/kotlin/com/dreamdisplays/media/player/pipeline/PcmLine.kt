package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.util.OsInfo
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine

/** PCM output shared by desktop Java Sound and Android AudioTrack. */
internal interface PcmLine {
    val bufferSize: Int
    val longFramePosition: Long

    fun available(): Int
    fun open(format: AudioFormat, bufferSize: Int)
    fun start()
    fun stop()
    fun flush()
    fun close()
    fun write(data: ByteArray, offset: Int, length: Int): Int
}

internal fun openPcmLine(format: AudioFormat, bufferSize: Int): PcmLine? =
    if (OsInfo.isAndroid) AndroidPcmLine.open(format, bufferSize) else DesktopPcmLine.open(format, bufferSize)

private class DesktopPcmLine private constructor(private val line: SourceDataLine) : PcmLine {
    override val bufferSize: Int get() = line.bufferSize
    override val longFramePosition: Long get() = line.longFramePosition

    override fun available(): Int = line.available()
    override fun open(format: AudioFormat, bufferSize: Int) = line.open(format, bufferSize)
    override fun start() = line.start()
    override fun stop() = line.stop()
    override fun flush() = line.flush()
    override fun close() = line.close()
    override fun write(data: ByteArray, offset: Int, length: Int): Int = line.write(data, offset, length)

    companion object {
        fun open(format: AudioFormat, bufferSize: Int): PcmLine? = runCatching {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) return null
            DesktopPcmLine(AudioSystem.getLine(info) as SourceDataLine).also { it.open(format, bufferSize) }
        }.getOrNull()
    }
}

private class AndroidPcmLine private constructor(
    private val track: Any,
    private val getBufferSize: java.lang.reflect.Method,
    private val getAvailable: java.lang.reflect.Method,
    private val getPlaybackHead: java.lang.reflect.Method,
    private val play: java.lang.reflect.Method,
    private val pause: java.lang.reflect.Method,
    private val flushMethod: java.lang.reflect.Method,
    private val stopMethod: java.lang.reflect.Method,
    private val release: java.lang.reflect.Method,
    private val writeMethod: java.lang.reflect.Method,
) : PcmLine {
    override val bufferSize: Int get() = getBufferSize.invoke(track) as Int
    override val longFramePosition: Long get() = (getPlaybackHead.invoke(track) as Int).toLong() and 0xffffffffL

    @Volatile
    private var writtenBytes = 0L

    private fun queuedBytes(): Int {
        val playedBytes = longFramePosition * AudioSink.BYTES_PER_FRAME
        return (writtenBytes - playedBytes).coerceIn(0L, bufferSize.toLong()).toInt()
    }

    override fun available(): Int = (bufferSize - queuedBytes()).coerceAtLeast(0)

    override fun open(format: AudioFormat, bufferSize: Int) {
        writtenBytes = 0L
    }

    override fun start() { play.invoke(track) }
    override fun stop() { pause.invoke(track) }
    override fun flush() { flushMethod.invoke(track); writtenBytes = 0L }
    override fun close() { runCatching { stopMethod.invoke(track) }; release.invoke(track) }
    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        val written = writeMethod.invoke(track, data, offset, length, 0) as Int
        if (written > 0) writtenBytes += written.toLong()
        return written
    }

    companion object {
        private const val ENCODING_PCM_16BIT = 2
        private const val MODE_STREAM = 1
        private const val CHANNEL_OUT_STEREO = 12

        fun open(format: AudioFormat, bufferSize: Int): PcmLine? = runCatching {
            val type = Class.forName("android.media.AudioTrack")
            val getMinBufferSize = type.getMethod("getMinBufferSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val minBuffer = getMinBufferSize.invoke(null, format.sampleRate.toInt(), CHANNEL_OUT_STEREO, ENCODING_PCM_16BIT) as Int
            require(minBuffer > 0) { "AudioTrack returned invalid buffer size: $minBuffer" }
            val constructor = type.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val track = constructor.newInstance(3, format.sampleRate.toInt(), CHANNEL_OUT_STEREO, ENCODING_PCM_16BIT, maxOf(minBuffer, bufferSize), MODE_STREAM)
            AndroidPcmLine(
                track,
                type.getMethod("getBufferSizeInBytes"),
                type.getMethod("getBufferSizeInBytes"),
                type.getMethod("getPlaybackHeadPosition"),
                type.getMethod("play"),
                type.getMethod("pause"),
                type.getMethod("flush"),
                type.getMethod("stop"),
                type.getMethod("release"),
                type.getMethod("write", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            )
        }.getOrNull()
    }
}
