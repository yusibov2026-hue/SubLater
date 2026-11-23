package com.fromsub.app

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Принимает декодированный PCM от ExoPlayer (включая HLS/DASH) и сразу пишет
 * те же 16 кГц mono WAV-чанки по 60 секунд, что и MediaExtractor для файлов.
 * Аудиовыход не используется: буферы считаются принятыми мгновенно, поэтому
 * поток разбирается с максимальной скоростью, а не в реальном времени.
 */
@UnstableApi
class StreamAudioCaptureSink(
    private val context: Context,
    private val onChunk: (JSONObject, Boolean) -> Unit,
    private val onDone: (Int) -> Unit,
    private val onError: (String) -> Unit,
    private val sessionId: Long = 0L,
    private val baseStartSec: Double = 0.0,
    private val targetSampleRate: Int = 16000,
    private val chunkDurationSec: Int = 60,
    private val overlapSec: Int = 1
) : AudioSink {

    private val chunkDir = ChunkCachePaths.chunkDir(context)
    private val ioLock = Any()

    private var listener: AudioSink.Listener? = null
    private var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled = false

    private var sourceSampleRate = 44100
    private var channelCount = 2
    private var pcmEncoding = C.ENCODING_PCM_16BIT

    private var resamplePos = 0.0
    private var leftoverSamples = FloatArray(0)
    private var lastPresentationTimeUs = 0L
    private var configured = false
    private var ended = false
    @Volatile
    private var aborted = false
    private var finished = false

    private val maxChunkBytes = chunkDurationSec * targetSampleRate * 2
    private val overlapBytesCount = overlapSec * targetSampleRate * 2
    private val overlapRing = ByteArray(overlapBytesCount)
    private var overlapFilled = 0
    private var overlapWritePos = 0

    private var chunkIndex = 0
    private var totalWrittenMonoSamples = 0L
    private var currentChunkStartSample = 0L
    private var currentFile: File? = null
    private var currentOut: BufferedOutputStream? = null
    private var currentChunkPcmBytes = 0
    private var emittedChunks = 0
    private val ioChunkBuf = ByteArray(65536)
    private var ioChunkBufUsed = 0
    private val pendingChunks = ArrayDeque<Pair<JSONObject, Boolean>>()
    private var pendingDoneCount: Int? = null
    private var pendingError: String? = null

    fun abort() {
        synchronized(ioLock) {
            aborted = true
            pendingChunks.clear()
            pendingDoneCount = null
            pendingError = null
            try { currentOut?.close() } catch (_: Exception) {}
            currentOut = null
            try { currentFile?.delete() } catch (_: Exception) {}
            currentFile = null
        }
    }

    private fun drainPendingCallbacks() {
        val chunksToEmit: List<Pair<JSONObject, Boolean>>
        val doneCount: Int?
        val errorMsg: String?
        synchronized(ioLock) {
            if (pendingChunks.isEmpty() && pendingDoneCount == null && pendingError == null) return
            chunksToEmit = if (pendingChunks.isEmpty()) emptyList() else pendingChunks.toList().also { pendingChunks.clear() }
            doneCount = pendingDoneCount
            pendingDoneCount = null
            errorMsg = pendingError
            pendingError = null
        }
        for ((chunkObj, isLast) in chunksToEmit) {
            onChunk(chunkObj, isLast)
        }
        if (errorMsg != null) {
            onError(errorMsg)
        } else if (doneCount != null) {
            onDone(doneCount)
        }
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean {
        return getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun getFormatSupport(format: Format): Int {
        val mime = format.sampleMimeType ?: return AudioSink.SINK_FORMAT_UNSUPPORTED
        if (!mime.startsWith("audio/")) return AudioSink.SINK_FORMAT_UNSUPPORTED
        return if (mime == "audio/raw") {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        }
    }

    override fun getCurrentPositionUs(playbackPositionUs: Boolean): Long {
        return if (lastPresentationTimeUs > 0L) lastPresentationTimeUs else 0L
    }

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        synchronized(ioLock) {
            if (aborted) return
            sourceSampleRate = if (format.sampleRate > 0) format.sampleRate else 44100
            channelCount = if (format.channelCount > 0) format.channelCount else 2
            pcmEncoding = if (format.pcmEncoding != Format.NO_VALUE) format.pcmEncoding else C.ENCODING_PCM_16BIT
            resamplePos = 0.0
            leftoverSamples = FloatArray(0)
            configured = true
            ended = false
            finished = false
            if (currentFile == null) {
                if (!chunkDir.exists()) chunkDir.mkdirs()
                chunkDir.listFiles()?.forEach {
                    if (!it.name.startsWith("chunk_s${sessionId}_")) it.delete()
                }
                chunkIndex = 0
                emittedChunks = 0
                totalWrittenMonoSamples = 0L
                currentChunkStartSample = 0L
                overlapFilled = 0
                overlapWritePos = 0
                ioChunkBufUsed = 0
                startNewChunk()
            }
        }
    }

    override fun play() {}

    override fun handleDiscontinuity() {
        resamplePos = 0.0
        leftoverSamples = FloatArray(0)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, flags: Int): Boolean {
        try {
            synchronized(ioLock) {
                if (aborted) return true
                if (presentationTimeUs != 0L && presentationTimeUs != lastPresentationTimeUs) {
                    lastPresentationTimeUs = presentationTimeUs
                }
                if (!configured || buffer.remaining() <= 0) {
                    if ((flags and C.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        finishLocked(true)
                    }
                    return true
                }
                try {
                    processPcmLocked(buffer)
                } catch (e: Exception) {
                    if (!aborted) {
                        aborted = true
                        try { currentOut?.close() } catch (_: Exception) {}
                        currentOut = null
                        try { currentFile?.delete() } catch (_: Exception) {}
                        currentFile = null
                        pendingError = e.message ?: "Audio stream processing error"
                    }
                }
                if ((flags and C.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    finishLocked(true)
                }
                return true
            }
        } finally {
            drainPendingCallbacks()
        }
    }

    override fun playToEndOfStream() {
        synchronized(ioLock) {
            finishLocked(true)
        }
        drainPendingCallbacks()
    }

    override fun isEnded(): Boolean = ended || finished

    override fun hasPendingData(): Boolean = false

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {}

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}

    override fun enableTunnelingV21() {}

    override fun disableTunneling() {}

    override fun setVolume(volume: Float) {}

    override fun pause() {}

    override fun flush() {
        synchronized(ioLock) {
            resamplePos = 0.0
            leftoverSamples = FloatArray(0)
        }
    }

    override fun reset() {
        synchronized(ioLock) {
            try { currentOut?.flush() } catch (_: Exception) {}
        }
    }

    override fun release() {
        synchronized(ioLock) {
            if (aborted) {
                try { currentOut?.close() } catch (_: Exception) {}
                currentOut = null
                try { currentFile?.delete() } catch (_: Exception) {}
                currentFile = null
            } else {
                finishLocked(false)
            }
            listener = null
        }
    }

    private fun processPcmLocked(buffer: ByteBuffer) {
        val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = src.position()
        val bytesPerSample = when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            else -> 2
        }
        val frameSize = channelCount * bytesPerSample
        if (frameSize <= 0) return
        val frames = src.remaining() / frameSize
        if (frames <= 0) return

        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            val frameStart = base + frame * frameSize
            var sum = 0f
            for (ch in 0 until channelCount) {
                val weight = WavChunkWriter.channelWeight(ch, channelCount)
                if (weight == 0f) continue
                val idx = frameStart + ch * bytesPerSample
                sum += readSample(src, idx, bytesPerSample) * weight
            }
            mono[frame] = sum.coerceIn(-1f, 1f)
        }

        val combined = if (leftoverSamples.isEmpty()) {
            mono
        } else {
            val joined = FloatArray(leftoverSamples.size + mono.size)
            System.arraycopy(leftoverSamples, 0, joined, 0, leftoverSamples.size)
            System.arraycopy(mono, 0, joined, leftoverSamples.size, mono.size)
            joined
        }

        val step = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        if (step <= 0.0) return
        var pos = resamplePos
        val out = ShortArray(((combined.size.toDouble() - pos) / step).toInt().coerceAtLeast(0) + 2)
        var outCount = 0
        while (pos + 1.0 < combined.size.toDouble()) {
            val i = floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val sample = combined[i] + (combined[i + 1] - combined[i]) * frac
            out[outCount++] = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            pos += step
        }

        val keepFrom = floor(pos).toInt().coerceIn(0, combined.size - 1)
        leftoverSamples = combined.copyOfRange(keepFrom, combined.size)
        resamplePos = pos - keepFrom

        for (i in 0 until outCount) {
            pushMonoSampleLocked(out[i])
            if (aborted) return
        }
    }

    private fun readSample(buf: ByteBuffer, index: Int, bytesPerSample: Int): Float {
        return when (bytesPerSample) {
            1 -> ((buf.get(index).toInt() and 0xFF) - 128) / 128f
            3 -> {
                val b0 = buf.get(index).toInt() and 0xFF
                val b1 = buf.get(index + 1).toInt() and 0xFF
                val b2 = buf.get(index + 2).toInt()
                val v = (b2 shl 16) or (b1 shl 8) or b0
                (v shr 8) / 8388608f
            }
            4 -> {
                if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
                    buf.getFloat(index).coerceIn(-1f, 1f)
                } else {
                    (buf.getInt(index) / 2147483648f).coerceIn(-1f, 1f)
                }
            }
            else -> (buf.getShort(index) / 32768f).coerceIn(-1f, 1f)
        }
    }

    private fun startNewChunk() {
        val file = File(chunkDir, "chunk_s${sessionId}_${chunkIndex}.wav")
        currentFile = file
        currentOut = BufferedOutputStream(FileOutputStream(file), 131072)
        currentOut?.write(ByteArray(44))
        currentChunkPcmBytes = 0
    }

    private fun flushIoBufLocked() {
        if (ioChunkBufUsed > 0 && !aborted) {
            currentOut?.write(ioChunkBuf, 0, ioChunkBufUsed)
            currentChunkPcmBytes += ioChunkBufUsed
            ioChunkBufUsed = 0
        }
    }

    private fun pushMonoSampleLocked(sample: Short) {
        if (aborted) return
        val lo = (sample.toInt() and 0xFF).toByte()
        val hi = ((sample.toInt() ushr 8) and 0xFF).toByte()
        ioChunkBuf[ioChunkBufUsed++] = lo
        ioChunkBuf[ioChunkBufUsed++] = hi
        if (ioChunkBufUsed >= ioChunkBuf.size - 2) flushIoBufLocked()

        overlapRing[overlapWritePos] = lo
        overlapRing[(overlapWritePos + 1) % overlapBytesCount] = hi
        overlapWritePos = (overlapWritePos + 2) % overlapBytesCount
        if (overlapFilled < overlapBytesCount) overlapFilled += 2
        totalWrittenMonoSamples++

        if (currentChunkPcmBytes + ioChunkBufUsed >= maxChunkBytes) {
            rotateNextChunkLocked()
        }
    }

    private fun rotateNextChunkLocked() {
        finishChunkLocked(false)
        if (aborted) return
        chunkIndex++
        startNewChunk()
        val overlapSamples = overlapFilled / 2
        currentChunkStartSample = (totalWrittenMonoSamples - overlapSamples).coerceAtLeast(0L)
        if (overlapFilled > 0) {
            if (overlapFilled < overlapBytesCount) {
                currentOut?.write(overlapRing, 0, overlapFilled)
            } else {
                val tailLen = overlapBytesCount - overlapWritePos
                currentOut?.write(overlapRing, overlapWritePos, tailLen)
                if (overlapWritePos > 0) currentOut?.write(overlapRing, 0, overlapWritePos)
            }
            currentChunkPcmBytes += overlapFilled
        }
    }

    private fun finishChunkLocked(isLast: Boolean) {
        try {
            flushIoBufLocked()
        } catch (_: Exception) {}
        val file = currentFile ?: return
        try { currentOut?.flush() } catch (_: Exception) {}
        try { currentOut?.close() } catch (_: Exception) {}
        currentOut = null
        currentFile = null
        if (currentChunkPcmBytes <= 0 || aborted) {
            try { file.delete() } catch (_: Exception) {}
            return
        }
        try {
            RandomAccessFile(file, "rw").use { raf ->
                WavChunkWriter.writeWavHeader(raf, currentChunkPcmBytes, targetSampleRate, 1)
            }
        } catch (e: Exception) {
            try { file.delete() } catch (_: Exception) {}
            aborted = true
            pendingError = e.message ?: "Failed to finalize WAV chunk header"
            return
        }
        val startSec = baseStartSec + currentChunkStartSample.toDouble() / targetSampleRate.toDouble()
        val durationSec = (currentChunkPcmBytes / 2).toDouble() / targetSampleRate.toDouble()
        val endSec = startSec + durationSec
        val cutoffSec = if (chunkIndex == 0) baseStartSec else startSec + overlapSec / 2.0
        val nextCutoffSec = if (isLast) endSec + 100.0 else endSec - overlapSec / 2.0
        val chunkObj = JSONObject().apply {
            put("index", chunkIndex)
            put("path", file.absolutePath)
            put("fileUrl", "file://${file.absolutePath}")
            put("fileName", file.name)
            put("pos", startSec)
            put("startSec", startSec)
            put("chunkEnd", endSec)
            put("endSec", endSec)
            put("cutoff", cutoffSec)
            put("cutoffSec", cutoffSec)
            put("nextCutoff", nextCutoffSec)
            put("nextCutoffSec", nextCutoffSec)
            put("isLast", isLast)
        }
        emittedChunks++
        pendingChunks.addLast(chunkObj to isLast)
    }

    private fun finishLocked(isLast: Boolean) {
        if (finished || aborted) return
        finished = true
        ended = true
        finishChunkLocked(isLast)
        if (aborted) return
        if (emittedChunks > 0) {
            pendingDoneCount = emittedChunks
        } else {
            pendingError = "Failed to decode audio data"
        }
    }
}

@UnstableApi
class StreamAudioRenderersFactory(
    context: Context,
    private val sink: AudioSink
) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // Видео для извлечения аудио не нужно.
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        out.add(
            MediaCodecAudioRenderer(
                context,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                sink
            )
        )
    }
}
