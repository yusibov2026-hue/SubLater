package com.fromsub.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

@OptIn(UnstableApi::class)
class AudioChunkExtractor(
    private val context: Context,
    private val mainHandler: Handler,
    private val postJs: (String) -> Unit,
    private val currentUriProvider: () -> Uri?,
    private val currentMimeProvider: () -> String?,
    private val selectedAudioIndexProvider: () -> Int
) {

    private val activeSessionId = AtomicLong(0L)
    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var activeExecutor: ExecutorService? = null

    private var capturePlayer: ExoPlayer? = null

    @Volatile
    private var captureSink: StreamAudioCaptureSink? = null

    @Volatile
    private var chunkPermits: Semaphore? = null

    @Volatile
    private var extractionRunning = false

    fun isActive(): Boolean = extractionRunning || activeExecutor != null || capturePlayer != null

    private fun isSessionActive(sessionId: Long): Boolean {
        return !cancelRequested.get() && activeSessionId.get() == sessionId
    }

    private fun acquireChunkPermit(sessionId: Long): Boolean {
        val sem = chunkPermits ?: return isSessionActive(sessionId)
        while (isSessionActive(sessionId)) {
            try {
                if (sem.tryAcquire(250, TimeUnit.MILLISECONDS)) {
                    if (isSessionActive(sessionId)) return true
                    sem.release()
                    return false
                }
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun releaseAllChunkPermits() {
        val sem = chunkPermits
        chunkPermits = null
        sem?.release(64)
    }

    fun startExtraction(uriString: String, startSec: Double = 0.0, batchMode: Boolean = false): Boolean {
        val targetUri = if (uriString.isNotBlank() && !uriString.startsWith("blob:")) {
            Uri.parse(uriString)
        } else {
            currentUriProvider() ?: return false
        }

        val sessionId = activeSessionId.incrementAndGet()
        cancelRequested.set(false)
        stopActiveWorkersOnly()
        chunkPermits = Semaphore(if (batchMode) 6 else 12)
        extractionRunning = true

        // Механизм 1.0.24 ("All at once"): 12 кГц моно, нарезка по 5 минут (300 с) с перекрытием 6 с
        val targetSampleRate = if (batchMode) 12000 else 16000
        val streamChunkDurationSec = if (batchMode) 300 else 60
        val streamOverlapSec = if (batchMode) 6 else 1

        if (targetUri.scheme == "http" || targetUri.scheme == "https") {
            return startStreamAudioCapture(
                sessionId = sessionId,
                uri = targetUri,
                startSec = startSec,
                targetSampleRate = targetSampleRate,
                chunkDurationSec = streamChunkDurationSec,
                overlapSec = streamOverlapSec
            )
        }

        Thread({
            try {
                if (!isSessionActive(sessionId)) return@Thread
                extractParallelOrSequentialWavChunksAsync(
                    sessionId = sessionId,
                    uri = targetUri,
                    startSec = startSec,
                    targetSampleRate = targetSampleRate,
                    batchMode = batchMode
                )
            } catch (e: Exception) {
                if (isSessionActive(sessionId)) {
                    val errorMsg = JSONObject.quote(e.message ?: "Audio extraction error")
                    postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($errorMsg); }")
                }
            } finally {
                if (activeSessionId.get() == sessionId) {
                    extractionRunning = false
                }
            }
        }, "SubLyter-AudioExtractor-$sessionId").start()

        return true
    }

    fun cancel() {
        cancelRequested.set(true)
        activeSessionId.incrementAndGet()
        stopActiveWorkersOnly()
    }

    private fun stopActiveWorkersOnly() {
        extractionRunning = false
        releaseAllChunkPermits()
        try { captureSink?.abort() } catch (_: Exception) {}
        try { activeExecutor?.shutdownNow() } catch (_: Exception) {}
        activeExecutor = null
        mainHandler.post { releaseCapturePlayer() }
    }

    fun clearAudioCache(): Boolean {
        return try {
            val chunkDir = ChunkCachePaths.chunkDir(context)
            if (chunkDir.exists()) {
                chunkDir.listFiles()?.forEach { it.delete() }
            }
            chunkPermits?.release(64)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun readChunkBase64(filePath: String): String {
        return try {
            val f = ChunkCachePaths.resolveSafeChunkFile(context, filePath) ?: return ""
            if (!f.exists() || !f.canRead()) return ""
            Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    fun deleteChunkFile(filePath: String): Boolean {
        return try {
            val f = ChunkCachePaths.resolveSafeChunkFile(context, filePath) ?: return false
            val deleted = if (f.exists()) f.delete() else false
            if (f.name.startsWith("chunk_")) {
                chunkPermits?.release()
            }
            deleted
        } catch (_: Exception) {
            false
        }
    }

    fun isChunkStreamUrl(url: String?): Boolean {
        return url != null && url.startsWith("$CHUNK_STREAM_SCHEME$CHUNK_HOST/")
    }

    fun buildChunkStreamResponse(url: String): WebResourceResponse? {
        return try {
            val uri = Uri.parse(url)
            val encodedPath = uri.getQueryParameter("path")
            val file = ChunkCachePaths.resolveSafeChunkFile(context, encodedPath) ?: return null
            if (!file.exists() || !file.canRead()) return null

            val headers = HashMap<String, String>()
            headers["Access-Control-Allow-Origin"] = "*"
            headers["Cache-Control"] = "no-store"
            headers["Content-Type"] = "audio/wav"
            headers["Content-Length"] = file.length().toString()

            WebResourceResponse(
                "audio/wav",
                null,
                200,
                "OK",
                headers,
                FileInputStream(file)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun startStreamAudioCapture(
        sessionId: Long,
        uri: Uri,
        startSec: Double,
        targetSampleRate: Int,
        chunkDurationSec: Int,
        overlapSec: Int
    ): Boolean {
        val safeStartSec = if (startSec >= 30.0) startSec else 0.0
        val mime = currentMimeProvider()
        val selectedAudioIdx = selectedAudioIndexProvider()

        mainHandler.post {
            releaseCapturePlayer()
            if (!isSessionActive(sessionId)) return@post
            try {
                val sink = StreamAudioCaptureSink(
                    context,
                    onChunk = { chunkObj, isLast ->
                        if (isSessionActive(sessionId)) {
                            val json = chunkObj.toString()
                            postJs("if (window.__onNativeChunkReady) { window.__onNativeChunkReady($json, $isLast); }")
                        }
                    },
                    onDone = { count ->
                        if (activeSessionId.get() == sessionId) {
                            extractionRunning = false
                        }
                        if (isSessionActive(sessionId)) {
                            postJs("if (window.__onNativeChunkDone) { window.__onNativeChunkDone($count); }")
                        }
                        mainHandler.post { releaseCapturePlayer() }
                    },
                    onError = { message ->
                        if (activeSessionId.get() == sessionId) {
                            extractionRunning = false
                        }
                        if (isSessionActive(sessionId)) {
                            val quoted = JSONObject.quote(message)
                            postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($quoted); }")
                        }
                        mainHandler.post { releaseCapturePlayer() }
                    },
                    sessionId = sessionId,
                    baseStartSec = safeStartSec,
                    targetSampleRate = targetSampleRate,
                    chunkDurationSec = chunkDurationSec,
                    overlapSec = overlapSec
                )
                captureSink = sink

                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 180_000, 400, 800)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
                val renderersFactory = StreamAudioRenderersFactory(context, sink)
                val player = ExoPlayer.Builder(context, renderersFactory)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context))
                    .build()
                capturePlayer = player

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            mainHandler.post { releaseCapturePlayer() }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (isSessionActive(sessionId)) {
                            val quoted = JSONObject.quote(error.message ?: "Stream read error")
                            postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($quoted); }")
                        }
                        mainHandler.post { releaseCapturePlayer() }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        applyAudioTrackOverride(player, selectedAudioIdx)
                    }
                })

                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .apply {
                        if (!mime.isNullOrBlank() && mime != "video/*" && mime != "*/*") {
                            setMimeType(mime)
                        }
                    }
                    .build()
                player.setMediaItem(mediaItem)
                player.setAudioAttributes(AudioAttributes.DEFAULT, false)
                player.volume = 0f
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
                if (safeStartSec > 0.0) {
                    player.seekTo((safeStartSec * 1000.0).toLong())
                }
                player.prepare()
                player.playWhenReady = true
            } catch (e: Exception) {
                if (activeSessionId.get() == sessionId) {
                    extractionRunning = false
                }
                if (isSessionActive(sessionId)) {
                    val quoted = JSONObject.quote(e.message ?: "Failed to open stream")
                    postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($quoted); }")
                }
                releaseCapturePlayer()
            }
        }
        return true
    }

    private fun releaseCapturePlayer() {
        val player = capturePlayer
        capturePlayer = null
        captureSink = null
        if (player != null) {
            try { player.release() } catch (_: Exception) {}
        }
        if (activeExecutor == null) {
            extractionRunning = false
        }
    }

    private fun applyAudioTrackOverride(player: ExoPlayer, orderIndex: Int) {
        if (orderIndex <= 0) return
        try {
            var current = 0
            for (group in player.currentTracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (t in 0 until group.length) {
                        if (current == orderIndex) {
                            val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(t))
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(override)
                                .build()
                            return
                        }
                        current++
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun probeAudioDurationSec(uri: Uri): Double {
        val extractor = MediaExtractor()
        val selectedAudioIdx = selectedAudioIndexProvider()
        return try {
            extractor.setDataSource(context, uri, null)
            val candidates = mutableListOf<MediaFormat>()
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) candidates.add(fmt)
            }
            val chosen = if (candidates.isNotEmpty()) {
                candidates.getOrElse(selectedAudioIdx.coerceIn(0, candidates.lastIndex)) { candidates.first() }
            } else null
            val durUs = if (chosen != null && chosen.containsKey(MediaFormat.KEY_DURATION)) {
                chosen.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            if (durUs > 0L) durUs.toDouble() / 1_000_000.0 else 0.0
        } catch (_: Exception) {
            0.0
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun extractParallelOrSequentialWavChunksAsync(
        sessionId: Long,
        uri: Uri,
        startSec: Double,
        targetSampleRate: Int,
        batchMode: Boolean
    ) {
        val safeStartSec = if (startSec >= 30.0) startSec else 0.0
        val totalDurSec = probeAudioDurationSec(uri)
        val remainingSec = (totalDurSec - safeStartSec).coerceAtLeast(0.0)
        val fallbackChunkDur = if (batchMode) 300 else 60
        val overlapSec = if (batchMode) 6 else 1

        if (totalDurSec <= 0.0 || remainingSec <= (if (batchMode) 305.0 else 70.0)) {
            extractFixedWavChunksAsync(sessionId, uri, startSec, targetSampleRate, fallbackChunkDur, overlapSec)
            return
        }

        val chunkDurationSec = if (batchMode) 300 else 60

        val windows = mutableListOf<Triple<Int, Double, Double>>()
        var curStart = safeStartSec
        var wIdx = 0
        while (curStart < totalDurSec - 0.25) {
            val nominalEnd = (curStart + chunkDurationSec).coerceAtMost(totalDurSec)
            windows.add(Triple(wIdx, curStart, nominalEnd))
            curStart = nominalEnd
            wIdx++
        }

        if (windows.size <= 1) {
            extractFixedWavChunksAsync(sessionId, uri, startSec, targetSampleRate, fallbackChunkDur, overlapSec)
            return
        }

        val chunkDir = ChunkCachePaths.chunkDir(context)
        if (!chunkDir.exists()) chunkDir.mkdirs()
        chunkDir.listFiles()?.forEach {
            if (!it.name.startsWith("chunk_s${sessionId}_")) it.delete()
        }

        val threadCount = windows.size.coerceIn(1, 4)
        val executor = Executors.newFixedThreadPool(threadCount)
        activeExecutor = executor
        val emittedCount = AtomicInteger(0)
        val fatalError = AtomicReference<String?>(null)

        try {
            windows.forEachIndexed { idx, (winIdx, winNomStart, winNomEnd) ->
                val isLastWin = idx == windows.lastIndex
                executor.execute {
                    if (!isSessionActive(sessionId)) return@execute
                    try {
                        val chunkJson = decodeSingleWindowToWav(
                            sessionId = sessionId,
                            uri = uri,
                            chunkDir = chunkDir,
                            winIndex = winIdx,
                            winNomStartSec = winNomStart,
                            winNomEndSec = winNomEnd,
                            isLastWindow = isLastWin,
                            baseStartFloorSec = safeStartSec,
                            targetSampleRate = targetSampleRate,
                            overlapSec = overlapSec
                        )
                        if (chunkJson != null && isSessionActive(sessionId)) {
                            emittedCount.incrementAndGet()
                            val jsonStr = chunkJson.toString()
                            postJs("if (window.__onNativeChunkReady) { window.__onNativeChunkReady($jsonStr, false); }")
                        }
                    } catch (t: Throwable) {
                        fatalError.compareAndSet(null, t.message ?: "Parallel audio decode failed")
                    }
                }
            }
            executor.shutdown()
            while (!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                if (!isSessionActive(sessionId)) {
                    executor.shutdownNow()
                    break
                }
            }
        } finally {
            if (!executor.isTerminated) executor.shutdownNow()
            if (activeExecutor === executor) activeExecutor = null
        }

        if (!isSessionActive(sessionId)) return

        val totalEmitted = emittedCount.get()
        if (fatalError.get() != null || totalEmitted == 0) {
            // Не глотаем ошибку параллельного окна: если не все окна декодировались и ничего не отдано, откатываемся на линейный путь
            if (totalEmitted == 0) {
                extractFixedWavChunksAsync(sessionId, uri, startSec, targetSampleRate, fallbackChunkDur, overlapSec)
                return
            }
            val err = JSONObject.quote(fatalError.get() ?: "Parallel audio decode incomplete")
            postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($err); }")
            return
        }
        postJs("if (window.__onNativeChunkDone) { window.__onNativeChunkDone($totalEmitted); }")
    }

    private fun decodeSingleWindowToWav(
        sessionId: Long,
        uri: Uri,
        chunkDir: File,
        winIndex: Int,
        winNomStartSec: Double,
        winNomEndSec: Double,
        isLastWindow: Boolean,
        baseStartFloorSec: Double,
        targetSampleRate: Int,
        overlapSec: Int
    ): JSONObject? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val outFile = File(chunkDir, "chunk_s${sessionId}_${winIndex}.wav")
        val selectedAudioIdx = selectedAudioIndexProvider()
        var permitAcquired = false

        try {
            extractor.setDataSource(context, uri, null)
            val audioCandidates = mutableListOf<Triple<Int, MediaFormat, String>>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioCandidates.add(Triple(i, format, mime))
                }
            }
            if (audioCandidates.isEmpty()) return null

            val chosen = audioCandidates.getOrElse(selectedAudioIdx.coerceIn(0, audioCandidates.lastIndex)) {
                audioCandidates.first()
            }
            val audioTrackIndex = chosen.first
            val audioFormat = chosen.second
            val mimeType = chosen.third

            extractor.selectTrack(audioTrackIndex)
            val skipUntilSec = if (winIndex == 0) winNomStartSec else (winNomStartSec - overlapSec).coerceAtLeast(baseStartFloorSec)
            if (skipUntilSec > 0.0) {
                extractor.seekTo((skipUntilSec * 1_000_000.0).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val firstSampleUs = extractor.sampleTime
            if (firstSampleUs < 0L && winIndex > 0) return null
            val seekLandedSec = if (firstSampleUs >= 0L) firstSampleUs.toDouble() / 1_000_000.0 else skipUntilSec
            val stopAtSec = if (isLastWindow) Double.MAX_VALUE else (winNomEndSec + overlapSec.toDouble() * 0.5 + 0.5)
            val extractorStopUs = if (isLastWindow) Long.MAX_VALUE else ((stopAtSec + 1.0) * 1_000_000.0).toLong()

            if (!acquireChunkPermit(sessionId)) return null
            permitAcquired = true

            val localCodec = MediaCodec.createDecoderByType(mimeType)
            codec = localCodec
            localCodec.configure(audioFormat, null, null, 0)
            localCodec.start()

            var pcmBytes = 0
            var actualChunkStartSec = seekLandedSec
            var chunkPeak = 0

            BufferedOutputStream(FileOutputStream(outFile), 131072).use { stream ->
                stream.write(ByteArray(44))

                val ioBuf = ByteArray(65536)
                var ioUsed = 0
                var writtenSamples = 0L
                var droppedPreSamples = 0L
                var reachedWindowEnd = false
                var currentBufStartSec = -1.0
                var currentBufOutIdx = 0

                fun flushIo() {
                    if (ioUsed > 0) {
                        stream.write(ioBuf, 0, ioUsed)
                        pcmBytes += ioUsed
                        ioUsed = 0
                    }
                }

                fun pushWindowSample(sample: Short) {
                    if (reachedWindowEnd) return
                    val sampleTimeSec = if (currentBufStartSec >= 0.0) {
                        currentBufStartSec + currentBufOutIdx.toDouble() / targetSampleRate.toDouble()
                    } else {
                        seekLandedSec + (droppedPreSamples + writtenSamples).toDouble() / targetSampleRate.toDouble()
                    }
                    currentBufOutIdx++
                    if (winIndex > 0 && sampleTimeSec < skipUntilSec) {
                        droppedPreSamples++
                        return
                    }
                    if (writtenSamples == 0L) {
                        actualChunkStartSec = sampleTimeSec
                    }
                    if (!isLastWindow && sampleTimeSec > stopAtSec) {
                        reachedWindowEnd = true
                        return
                    }
                    val absVal = if (sample < 0) -sample.toInt() else sample.toInt()
                    if (absVal > chunkPeak) chunkPeak = absVal
                    ioBuf[ioUsed++] = (sample.toInt() and 0xFF).toByte()
                    ioBuf[ioUsed++] = ((sample.toInt() ushr 8) and 0xFF).toByte()
                    if (ioUsed >= ioBuf.size - 2) flushIo()
                    writtenSamples++
                }

                var srcSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                } else 44100
                var srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                } else 2
                var channelWeights = FloatArray(srcChannels) { ch -> WavChunkWriter.channelWeight(ch, srcChannels) }
                var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                var resamplePos = 0.0
                var resampleStep = srcSampleRate.toDouble() / targetSampleRate.toDouble()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone && !reachedWindowEnd && isSessionActive(sessionId)) {
                    if (!inputDone) {
                        val inIndex = localCodec.dequeueInputBuffer(2000L)
                        if (inIndex >= 0) {
                            val inBuf = localCodec.getInputBuffer(inIndex)
                            if (inBuf != null) {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                val sampleTimeUs = extractor.sampleTime
                                if (sampleSize < 0 || (!isLastWindow && sampleTimeUs >= 0L && sampleTimeUs > extractorStopUs)) {
                                    localCodec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    localCodec.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    var outIndex = localCodec.dequeueOutputBuffer(bufferInfo, 2000L)
                    while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFmt = localCodec.outputFormat
                            if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                srcSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                            }
                            if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                val nextCh = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                                if (nextCh != srcChannels) {
                                    srcChannels = nextCh
                                    channelWeights = FloatArray(srcChannels) { ch -> WavChunkWriter.channelWeight(ch, srcChannels) }
                                }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                            resampleStep = srcSampleRate.toDouble() / targetSampleRate.toDouble()
                        } else if (outIndex >= 0) {
                            val outBuf = localCodec.getOutputBuffer(outIndex)
                            if (outBuf != null && bufferInfo.size > 0 && !reachedWindowEnd) {
                                currentBufStartSec = if (bufferInfo.presentationTimeUs >= 0L) {
                                    bufferInfo.presentationTimeUs.toDouble() / 1_000_000.0
                                } else {
                                    -1.0
                                }
                                currentBufOutIdx = 0
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                resamplePos = consumeDecodedPcm(
                                    outBuf = outBuf,
                                    pcmEncoding = pcmEncoding,
                                    srcChannels = srcChannels,
                                    channelWeights = channelWeights,
                                    resamplePos = resamplePos,
                                    resampleStep = resampleStep,
                                    stopCheck = { reachedWindowEnd || !isSessionActive(sessionId) },
                                    onSample = ::pushWindowSample
                                )
                            }
                            localCodec.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                                break
                            }
                        }
                        if (reachedWindowEnd || !isSessionActive(sessionId)) break
                        outIndex = localCodec.dequeueOutputBuffer(bufferInfo, 0L)
                    }
                }

                flushIo()
                stream.flush()
            }

            if (!isSessionActive(sessionId) || pcmBytes <= 0) {
                try { outFile.delete() } catch (_: Exception) {}
                return null
            }

            RandomAccessFile(outFile, "rw").use { raf ->
                WavChunkWriter.writeWavHeader(raf, pcmBytes, targetSampleRate, 1)
            }
            val durationSec = (pcmBytes / 2).toDouble() / targetSampleRate.toDouble()
            val endSec = actualChunkStartSec + durationSec
            val cutoffSec = if (winIndex == 0) actualChunkStartSec else winNomStartSec
            val nextCutoffSec = if (isLastWindow) (endSec + 100.0) else winNomEndSec

            permitAcquired = false
            return JSONObject().apply {
                put("index", winIndex)
                put("path", outFile.absolutePath)
                put("fileUrl", "file://${outFile.absolutePath}")
                put("fileName", outFile.name)
                put("pos", actualChunkStartSec)
                put("startSec", actualChunkStartSec)
                put("chunkEnd", endSec)
                put("endSec", endSec)
                put("cutoff", cutoffSec)
                put("cutoffSec", cutoffSec)
                put("nextCutoff", nextCutoffSec)
                put("nextCutoffSec", nextCutoffSec)
                put("peak", chunkPeak)
                put("isLast", false)
            }
        } catch (e: Throwable) {
            try { outFile.delete() } catch (_: Exception) {}
            throw e
        } finally {
            if (permitAcquired) {
                chunkPermits?.release()
            }
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun extractFixedWavChunksAsync(
        sessionId: Long,
        uri: Uri,
        startSec: Double,
        targetSampleRate: Int,
        chunkDurationSec: Int,
        overlapSec: Int
    ) {
        var count = 0
        extractWavChunksInternal(sessionId, uri, startSec, targetSampleRate, chunkDurationSec, overlapSec) { chunkJson, isLast ->
            if (!isSessionActive(sessionId)) return@extractWavChunksInternal
            count++
            val jsonStr = chunkJson.toString()
            postJs("if (window.__onNativeChunkReady) { window.__onNativeChunkReady($jsonStr, $isLast); }")
        }
        if (isSessionActive(sessionId)) {
            postJs("if (window.__onNativeChunkDone) { window.__onNativeChunkDone($count); }")
        }
    }

    private fun extractWavChunksInternal(
        sessionId: Long,
        uri: Uri,
        requestedStartSec: Double,
        targetSampleRate: Int,
        chunkDurationSec: Int,
        overlapSec: Int,
        onChunkReady: (JSONObject, Boolean) -> Unit
    ) {
        val chunkDir = ChunkCachePaths.chunkDir(context)
        if (!chunkDir.exists()) chunkDir.mkdirs()
        chunkDir.listFiles()?.forEach {
            if (!it.name.startsWith("chunk_s${sessionId}_")) it.delete()
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null
        var mimeType = ""
        val audioCandidates = mutableListOf<Triple<Int, MediaFormat, String>>()

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioCandidates.add(Triple(i, format, mime))
            }
        }

        val selectedAudioIdx = selectedAudioIndexProvider()
        if (audioCandidates.isNotEmpty()) {
            val chosen = audioCandidates.getOrElse(selectedAudioIdx.coerceIn(0, audioCandidates.lastIndex)) {
                audioCandidates.first()
            }
            audioTrackIndex = chosen.first
            audioFormat = chosen.second
            mimeType = chosen.third
        }

        if (audioTrackIndex < 0 || audioFormat == null) {
            extractor.release()
            if (isSessionActive(sessionId)) {
                val err = JSONObject.quote("No audio track found in file")
                postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($err); }")
            }
            return
        }

        extractor.selectTrack(audioTrackIndex)
        val safeStartSec = if (requestedStartSec >= 30.0) requestedStartSec else 0.0
        var baseStartSec = 0.0
        if (safeStartSec > 0.0) {
            extractor.seekTo((safeStartSec * 1_000_000.0).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val firstSampleUs = extractor.sampleTime
            baseStartSec = if (firstSampleUs >= 0L) firstSampleUs.toDouble() / 1_000_000.0 else safeStartSec
        }

        val maxChunkBytes = chunkDurationSec * targetSampleRate * 2
        val overlapBytesCount = overlapSec * targetSampleRate * 2
        val overlapRing = ByteArray(overlapBytesCount)
        var overlapFilled = 0
        var overlapWritePos = 0

        if (!acquireChunkPermit(sessionId)) {
            extractor.release()
            return
        }
        var currentPermitHeld = true

        val codec = MediaCodec.createDecoderByType(mimeType)
        codec.configure(audioFormat, null, null, 0)
        codec.start()

        var chunkIndex = 0
        var currentChunkStartSample = 0L
        var totalWrittenMonoSamples = 0L
        var currentChunkStartSec = baseStartSec
        var currentChunkPtsAnchored = false
        var lastEmittedSamplePtsSec = baseStartSec
        var currentBufStartSec = -1.0
        var currentBufOutIdx = 0
        var chunkPeak = 0

        var currentFile = File(chunkDir, "chunk_s${sessionId}_0.wav")
        var currentOut = BufferedOutputStream(FileOutputStream(currentFile), 131072)
        currentOut.write(ByteArray(44))
        var currentChunkPcmBytes = 0

        val ioChunkBuf = ByteArray(65536)
        var ioChunkBufUsed = 0

        fun flushIoBuf() {
            if (ioChunkBufUsed > 0) {
                currentOut.write(ioChunkBuf, 0, ioChunkBufUsed)
                currentChunkPcmBytes += ioChunkBufUsed
                ioChunkBufUsed = 0
            }
        }

        fun maxPeakInOverlapRing(): Int {
            if (overlapFilled <= 0) return 0
            var peak = 0
            val start = if (overlapFilled < overlapBytesCount) 0 else overlapWritePos
            var i = 0
            while (i + 1 < overlapFilled) {
                val lo = overlapRing[(start + i) % overlapBytesCount].toInt() and 0xFF
                val hi = overlapRing[(start + i + 1) % overlapBytesCount].toInt()
                val sample = ((hi shl 8) or lo).toShort()
                val absVal = if (sample < 0) -sample.toInt() else sample.toInt()
                if (absVal > peak) peak = absVal
                i += 2
            }
            return peak
        }

        fun finalizeChunk(isLast: Boolean) {
            flushIoBuf()
            currentOut.flush()
            currentOut.close()
            if (currentChunkPcmBytes > 0 && isSessionActive(sessionId)) {
                RandomAccessFile(currentFile, "rw").use { raf ->
                    WavChunkWriter.writeWavHeader(raf, currentChunkPcmBytes, targetSampleRate, 1)
                }
                val startSec = if (currentChunkPtsAnchored) {
                    currentChunkStartSec
                } else {
                    baseStartSec + currentChunkStartSample.toDouble() / targetSampleRate.toDouble()
                }
                val durationSec = (currentChunkPcmBytes / 2).toDouble() / targetSampleRate.toDouble()
                val endSec = startSec + durationSec
                val cutoffSec = if (chunkIndex == 0) baseStartSec else (startSec + overlapSec / 2.0)
                val nextCutoffSec = if (isLast) (endSec + 100.0) else (endSec - overlapSec / 2.0)

                val chunkObj = JSONObject().apply {
                    put("index", chunkIndex)
                    put("path", currentFile.absolutePath)
                    put("fileUrl", "file://${currentFile.absolutePath}")
                    put("fileName", currentFile.name)
                    put("pos", startSec)
                    put("startSec", startSec)
                    put("chunkEnd", endSec)
                    put("endSec", endSec)
                    put("cutoff", cutoffSec)
                    put("cutoffSec", cutoffSec)
                    put("nextCutoff", nextCutoffSec)
                    put("nextCutoffSec", nextCutoffSec)
                    put("peak", chunkPeak)
                    put("isLast", isLast)
                }
                currentPermitHeld = false
                onChunkReady(chunkObj, isLast)
            } else {
                try { currentFile.delete() } catch (_: Exception) {}
                if (currentPermitHeld) {
                    currentPermitHeld = false
                    chunkPermits?.release()
                }
                if (isLast && chunkIndex == 0 && isSessionActive(sessionId)) {
                    val err = JSONObject.quote("Failed to decode audio data")
                    postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($err); }")
                }
            }
        }

        fun rotateNextChunk() {
            finalizeChunk(isLast = false)
            if (!isSessionActive(sessionId)) return
            if (!acquireChunkPermit(sessionId)) return
            currentPermitHeld = true
            chunkPeak = 0
            chunkIndex++
            currentFile = File(chunkDir, "chunk_s${sessionId}_${chunkIndex}.wav")
            currentOut = BufferedOutputStream(FileOutputStream(currentFile), 131072)
            currentOut.write(ByteArray(44))
            currentChunkPcmBytes = 0

            val overlapSamples = (overlapFilled / 2).toLong()
            currentChunkStartSample = (totalWrittenMonoSamples - overlapSamples).coerceAtLeast(0L)
            if (currentChunkPtsAnchored) {
                currentChunkStartSec = (lastEmittedSamplePtsSec - overlapSamples.toDouble() / targetSampleRate.toDouble()).coerceAtLeast(0.0)
            }
            if (overlapFilled > 0) {
                if (overlapFilled < overlapBytesCount) {
                    currentOut.write(overlapRing, 0, overlapFilled)
                } else {
                    val tailLen = overlapBytesCount - overlapWritePos
                    currentOut.write(overlapRing, overlapWritePos, tailLen)
                    if (overlapWritePos > 0) {
                        currentOut.write(overlapRing, 0, overlapWritePos)
                    }
                }
                currentChunkPcmBytes += overlapFilled
                chunkPeak = maxPeakInOverlapRing()
            }
        }

        fun pushMonoSample(sample: Short) {
            val samplePts = if (currentBufStartSec >= 0.0) {
                currentBufStartSec + currentBufOutIdx.toDouble() / targetSampleRate.toDouble()
            } else {
                baseStartSec + totalWrittenMonoSamples.toDouble() / targetSampleRate.toDouble()
            }
            currentBufOutIdx++
            if (totalWrittenMonoSamples == 0L && currentBufStartSec >= 0.0) {
                currentChunkStartSec = samplePts
                currentChunkPtsAnchored = true
            } else if (currentBufStartSec >= 0.0) {
                currentChunkPtsAnchored = true
            }
            lastEmittedSamplePtsSec = samplePts

            val absVal = if (sample < 0) -sample.toInt() else sample.toInt()
            if (absVal > chunkPeak) chunkPeak = absVal

            val lo = (sample.toInt() and 0xFF).toByte()
            val hi = ((sample.toInt() ushr 8) and 0xFF).toByte()
            ioChunkBuf[ioChunkBufUsed++] = lo
            ioChunkBuf[ioChunkBufUsed++] = hi
            if (ioChunkBufUsed >= ioChunkBuf.size - 2) flushIoBuf()
            overlapRing[overlapWritePos] = lo
            overlapRing[(overlapWritePos + 1) % overlapBytesCount] = hi
            overlapWritePos = (overlapWritePos + 2) % overlapBytesCount
            if (overlapFilled < overlapBytesCount) overlapFilled += 2
            totalWrittenMonoSamples++
            if (currentChunkPcmBytes + ioChunkBufUsed >= maxChunkBytes) {
                rotateNextChunk()
            }
        }

        var srcSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
        } else 44100
        var srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        } else 2
        var channelWeights = FloatArray(srcChannels) { ch -> WavChunkWriter.channelWeight(ch, srcChannels) }
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var resamplePos = 0.0
        var resampleStep = srcSampleRate.toDouble() / targetSampleRate.toDouble()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone && isSessionActive(sessionId)) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(2000L)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 2000L)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFmt = codec.outputFormat
                        if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        }
                        if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            val nextCh = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            if (nextCh != srcChannels) {
                                srcChannels = nextCh
                                channelWeights = FloatArray(srcChannels) { ch -> WavChunkWriter.channelWeight(ch, srcChannels) }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        resampleStep = srcSampleRate.toDouble() / targetSampleRate.toDouble()
                    } else if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            currentBufStartSec = if (bufferInfo.presentationTimeUs >= 0L) {
                                bufferInfo.presentationTimeUs.toDouble() / 1_000_000.0
                            } else {
                                -1.0
                            }
                            currentBufOutIdx = 0
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            resamplePos = consumeDecodedPcm(
                                outBuf = outBuf,
                                pcmEncoding = pcmEncoding,
                                srcChannels = srcChannels,
                                channelWeights = channelWeights,
                                resamplePos = resamplePos,
                                resampleStep = resampleStep,
                                stopCheck = { !isSessionActive(sessionId) },
                                onSample = ::pushMonoSample
                            )
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            break
                        }
                    }
                    if (!isSessionActive(sessionId)) break
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                }
            }
            if (!isSessionActive(sessionId)) {
                try { currentFile.delete() } catch (_: Exception) {}
                if (currentPermitHeld) {
                    currentPermitHeld = false
                    chunkPermits?.release()
                }
            } else {
                finalizeChunk(isLast = true)
            }
        } finally {
            if (currentPermitHeld) {
                currentPermitHeld = false
                chunkPermits?.release()
            }
            try { currentOut.close() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Общий цикл даунмикса и линейного ресемплинга для Float и 16-bit PCM. */
    private inline fun consumeDecodedPcm(
        outBuf: ByteBuffer,
        pcmEncoding: Int,
        srcChannels: Int,
        channelWeights: FloatArray,
        resamplePos: Double,
        resampleStep: Double,
        stopCheck: () -> Boolean,
        onSample: (Short) -> Unit
    ): Double {
        var pos = resamplePos
        var checkCounter = 0
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floatBuf = outBuf.asFloatBuffer()
            val totalFrames = floatBuf.remaining() / srcChannels
            if (totalFrames <= 0) return 0.0
            val maxIdx = totalFrames - 1
            if (srcChannels == 1) {
                while (pos < totalFrames) {
                    if ((checkCounter++ and 0xFF) == 0 && stopCheck()) break
                    val i0 = pos.toInt().coerceIn(0, maxIdx)
                    val i1 = if (i0 < maxIdx) i0 + 1 else maxIdx
                    val frac = (pos - i0).toFloat()
                    val s0 = floatBuf.get(i0)
                    val s1 = floatBuf.get(i1)
                    val interp = s0 + (s1 - s0) * frac
                    onSample((interp.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    pos += resampleStep
                }
            } else if (srcChannels == 2) {
                while (pos < totalFrames) {
                    if ((checkCounter++ and 0xFF) == 0 && stopCheck()) break
                    val i0 = pos.toInt().coerceIn(0, maxIdx)
                    val i1 = if (i0 < maxIdx) i0 + 1 else maxIdx
                    val frac = (pos - i0).toFloat()
                    val b0 = i0 shl 1
                    val b1 = i1 shl 1
                    val s0 = (floatBuf.get(b0) + floatBuf.get(b0 + 1)) * 0.5f
                    val s1 = (floatBuf.get(b1) + floatBuf.get(b1 + 1)) * 0.5f
                    val interp = s0 + (s1 - s0) * frac
                    onSample((interp.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    pos += resampleStep
                }
            } else {
                while (pos < totalFrames) {
                    if ((checkCounter++ and 0xFF) == 0 && stopCheck()) break
                    val i0 = pos.toInt().coerceIn(0, maxIdx)
                    val i1 = if (i0 < maxIdx) i0 + 1 else maxIdx
                    val frac = (pos - i0).toFloat()
                    var s0 = 0f
                    var s1 = 0f
                    val base0 = i0 * srcChannels
                    val base1 = i1 * srcChannels
                    for (ch in 0 until srcChannels) {
                        val w = channelWeights[ch]
                        if (w == 0f) continue
                        s0 += floatBuf.get(base0 + ch) * w
                        s1 += floatBuf.get(base1 + ch) * w
                    }
                    val interp = s0 + (s1 - s0) * frac
                    onSample((interp.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    pos += resampleStep
                }
            }
            return (pos - totalFrames.toDouble()).coerceAtLeast(0.0)
        } else {
            val shortBuf = outBuf.asShortBuffer()
            val totalFrames = shortBuf.remaining() / srcChannels
            if (totalFrames <= 0) return 0.0
            val maxIdx = totalFrames - 1
            if (srcChannels == 1) {
                while (pos < totalFrames) {
                    if ((checkCounter++ and 0xFF) == 0 && stopCheck()) break
                    val i0 = pos.toInt().coerceIn(0, maxIdx)
                    val i1 = if (i0 < maxIdx) i0 + 1 else maxIdx
                    val frac = (pos - i0).toFloat()
                    val s0 = shortBuf.get(i0).toFloat()
                    val s1 = shortBuf.get(i1).toFloat()
                    val interp = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
                    onSample(interp)
                    pos += resampleStep
                }
            } else if (srcChannels == 2) {
                while (pos < totalFrames) {
                    if ((checkCounter++ and 0xFF) == 0 && stopCheck()) break
                    val i0 = pos.toInt().coerceIn(0, maxIdx)
                    val i1 = if (i0 < maxIdx) i0 + 1 else maxIdx
                    val frac = (pos - i0).toFloat()
                    val b0 = i0 shl 1
                    val b1 = i1 shl 1
                    val s0 = (shortBuf.get(b0).toInt() + shortBuf.get(b0 + 1).toInt()) * 0.5f
                    val s1 = (shortBuf.get(b1).toInt() + shortBuf.get(b1 + 1).toInt()) * 0.5f
                    val interp = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
                    onSample(interp)
                    pos += resampleStep
                }
            } else {
                while (pos < totalFrames) {
                    if ((checkCounter++ and 0xFF) == 0 && stopCheck()) break
                    val i0 = pos.toInt().coerceIn(0, maxIdx)
                    val i1 = if (i0 < maxIdx) i0 + 1 else maxIdx
                    val frac = (pos - i0).toFloat()
                    var s0 = 0f
                    var s1 = 0f
                    val base0 = i0 * srcChannels
                    val base1 = i1 * srcChannels
                    for (ch in 0 until srcChannels) {
                        val w = channelWeights[ch]
                        if (w == 0f) continue
                        s0 += shortBuf.get(base0 + ch).toFloat() * w
                        s1 += shortBuf.get(base1 + ch).toFloat() * w
                    }
                    val interp = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
                    onSample(interp)
                    pos += resampleStep
                }
            }
            return (pos - totalFrames.toDouble()).coerceAtLeast(0.0)
        }
    }

    fun release() {
        cancel()
    }

    companion object {
        const val CHUNK_STREAM_SCHEME = "https://"
        const val CHUNK_HOST = "sublater-chunks.local"
    }
}
