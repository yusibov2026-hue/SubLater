package com.fromsub.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class VideoBounds(
    val leftRatio: Float = 0f,
    val topRatio: Float = 0f,
    val widthRatio: Float = 1f,
    val heightRatio: Float = 0f,
    val isFullscreen: Boolean = false,
    val isVisible: Boolean = false
)

data class AudioChunkMeta(
    val index: Int,
    val file: File,
    val startSec: Double,
    val endSec: Double,
    val cutoffSec: Double,
    val nextCutoffSec: Double
)

data class TranscribedSegment(
    val start: Double,
    val end: Double,
    val text: String
)

data class TranscribedWord(
    val word: String,
    val start: Double,
    val end: Double
)

data class ChunkTranscriptionResult(
    val chunk: AudioChunkMeta,
    val segments: List<TranscribedSegment>,
    val words: List<TranscribedWord>
)

/**
 * Асинхронный кодировщик одного 5-минутного чанка M4A (AAC LC, строго 16000 Гц, Mono, 64 kbps),
 * использующий MediaCodec в неблокирующем режиме CALLBACK (MediaCodec.Callback) + MediaMuxer
 * на выделенном HandlerThread без блокировки основного потока.
 */
private class AsyncM4aChunkMuxer(
    val outputFile: File,
    private val sampleRate: Int = 16000,
    private val bitRate: Int = 64000
) {
    private val encoderThread = HandlerThread("SubLaterM4aEncoder").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)
    private val encoder: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer: MediaMuxer
    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    private var currentPcmBlock: ByteArray? = null
    private var currentPcmOffset: Int = 0

    private val availableInputIndices = ConcurrentLinkedQueue<Int>()
    private val inputEosRequested = AtomicBoolean(false)
    private val inputEosSent = AtomicBoolean(false)
    private val outputDoneLatch = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private val lock = Object()

    private var trackIndex = -1
    private var muxerStarted = false

    @Volatile
    var totalSamplesWritten: Long = 0L
        private set

    init {
        if (outputFile.exists()) outputFile.delete()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        }

        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                synchronized(lock) {
                    if (closed.get()) return
                    availableInputIndices.offer(index)
                    feedAvailableEncoderInputsLocked(codec)
                }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                synchronized(lock) {
                    if (closed.get()) return
                    try {
                        val outBuf = codec.getOutputBuffer(index)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && muxerStarted && outBuf != null) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDoneLatch.countDown()
                        }
                    } catch (_: Exception) {
                        outputDoneLatch.countDown()
                    }
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                synchronized(lock) {
                    if (closed.get() || muxerStarted) return
                    try {
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                outputDoneLatch.countDown()
            }
        }, encoderHandler)

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
    }

    fun enqueuePcm16Mono(pcmBytes: ByteArray, length: Int) {
        if (length <= 0 || closed.get()) return
        val copy = ByteArray(length)
        System.arraycopy(pcmBytes, 0, copy, 0, length)
        synchronized(lock) {
            pcmQueue.offer(copy)
            feedAvailableEncoderInputsLocked(encoder)
        }
    }

    private fun feedAvailableEncoderInputsLocked(codec: MediaCodec) {
        while (availableInputIndices.isNotEmpty() && !inputEosSent.get()) {
            val inIdx = availableInputIndices.peek() ?: break
            val inBuf = try {
                codec.getInputBuffer(inIdx)
            } catch (_: Exception) {
                null
            } ?: break

            inBuf.clear()
            var bytesFilled = 0

            while (bytesFilled < inBuf.remaining()) {
                if (currentPcmBlock == null || currentPcmOffset >= (currentPcmBlock?.size ?: 0)) {
                    currentPcmBlock = pcmQueue.poll()
                    currentPcmOffset = 0
                }
                val block = currentPcmBlock ?: break
                val toCopy = minOf(block.size - currentPcmOffset, inBuf.remaining())
                inBuf.put(block, currentPcmOffset, toCopy)
                currentPcmOffset += toCopy
                bytesFilled += toCopy
            }

            if (bytesFilled > 0) {
                availableInputIndices.poll()
                val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate.toLong()
                totalSamplesWritten += (bytesFilled / 2).toLong()
                val isEosNow = inputEosRequested.get() && pcmQueue.isEmpty() &&
                    (currentPcmBlock == null || currentPcmOffset >= (currentPcmBlock?.size ?: 0))
                val flags = if (isEosNow) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                codec.queueInputBuffer(inIdx, 0, bytesFilled, ptsUs, flags)
                if (isEosNow) {
                    inputEosSent.set(true)
                }
            } else if (inputEosRequested.get()) {
                availableInputIndices.poll()
                val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate.toLong()
                codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEosSent.set(true)
            } else {
                break
            }
        }
    }

    fun finishAndClose() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            closed.set(false)
            inputEosRequested.set(true)
            try {
                feedAvailableEncoderInputsLocked(encoder)
            } catch (_: Exception) {
            }
        }
        try {
            outputDoneLatch.await(4, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        synchronized(lock) {
            closed.set(true)
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { encoderThread.quitSafely() } catch (_: Exception) {}
        }
    }
}

@OptIn(UnstableApi::class)
class ExoPlayerManager(
    private val context: Context,
    private val activity: MainActivity? = null
) {

    companion object {
        /** Строго 16000 Гц (16 кГц) — частота, на которой обучен Whisper для максимальной точности */
        const val WHISPER_SAMPLE_RATE_HZ = 16000
        const val WHISPER_CHANNELS_MONO = 1
        const val WHISPER_AAC_BITRATE = 64000
        const val CHUNK_DURATION_SEC = 300 // Нарезка ровно по 5 минут (300 секунд)
        const val CHUNK_OVERLAP_SEC = 6    // 6 секунд перекрытия для сохранения слов на стыках
        const val PARALLEL_GROQ_CONCURRENCY = 4 // Параллельная отправка 4 чанков одновременно
        const val CHUNK_TIMEOUT_MS = 45_000L    // Таймаут 45 секунд на один чанк
        const val MAX_CHUNK_RETRIES = 2         // До 2 повторных попыток при таймауте/сбое

        private val HALLUCINATION_REGEX = Regex(
            "(субтитры\\s+(сделал|создал|добавил|подготовил|писал)|редактор\\s+субтитров|корректор\\s+|dimatorzok|продолжение\\s+следует|amara\\.org|подписывайтесь\\s+на\\s+канал|ставь(те)?\\s+лайк)",
            RegexOption.IGNORE_CASE
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeSubtitleJob: Job? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentVideoUri: Uri? = null
    private var currentDisplayName: String = ""
    private var isExternalIntentMedia: Boolean = false

    @Volatile
    private var webViewRef: WebView? = null

    @Volatile
    private var cancelAudioExtraction: Boolean = false

    @Volatile
    private var activeFFmpegSessionId: Long? = null

    // Сессионные параметры Groq, передаваемые один раз при старте из JS
    @Volatile
    private var sessionGroqApiKey: String = ""

    @Volatile
    private var sessionAudioLanguage: String = "auto"

    private val activeKeyIndex = AtomicInteger(0)

    val isVideoReady = mutableStateOf(false)
    val resizeModeState = mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    val videoZoomState = mutableFloatStateOf(1.0f)
    val videoBoundsState = mutableStateOf(VideoBounds())

    @Volatile
    private var cachedPositionMs: Long = 0L

    @Volatile
    private var cachedDurationMs: Long = 0L

    @Volatile
    private var cachedIsPlaying: Boolean = false

    @Volatile
    private var cachedVideoWidth: Int = 1920

    @Volatile
    private var cachedVideoHeight: Int = 1080

    val exoPlayer: ExoPlayer by lazy {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .build()
                volume = 1.0f

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        cachedIsPlaying = isPlaying
                        syncStateOnMainThread()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            isVideoReady.value = true
                            if (volume <= 0f) volume = 1.0f
                        }
                        syncStateOnMainThread()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        syncStateOnMainThread()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        syncStateOnMainThread()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            cachedVideoWidth = videoSize.width
                            cachedVideoHeight = videoSize.height
                        }
                    }
                })
            }
    }

    private val positionPoller = object : Runnable {
        override fun run() {
            if (videoBoundsState.value.isVisible) {
                syncStateOnMainThread()
            }
            mainHandler.postDelayed(this, 60L)
        }
    }

    init {
        ensureCleanUserKeystore()
        sessionGroqApiKey = getApiKey()
        mainHandler.post(positionPoller)
    }

    fun attachWebView(webView: WebView) {
        this.webViewRef = webView
    }

    fun detachWebView() {
        this.webViewRef = null
    }

    /**
     * Переключает аппаратное ускорение WebView (LAYER_TYPE_SOFTWARE на время обработки длинных видео).
     */
    @JavascriptInterface
    fun setWebViewHardwareAcceleration(enabled: Boolean) {
        activity?.setWebViewHardwareAcceleration(enabled)
        mainHandler.post {
            val wv = webViewRef ?: return@post
            val targetLayer = if (enabled) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_SOFTWARE
            if (wv.layerType != targetLayer) {
                wv.setLayerType(targetLayer, null)
            }
        }
    }

    private fun evalJsOnWebView(script: String) {
        mainHandler.post {
            try {
                webViewRef?.evaluateJavascript(script, null)
            } catch (_: Exception) {
            }
        }
    }

    fun notifyExternalMediaIfPresent() {
        val uri = currentVideoUri ?: return
        if (!isExternalIntentMedia) return
        val safeUri = JSONObject.quote(uri.toString())
        val safeName = JSONObject.quote(currentDisplayName.ifBlank { "external_video.mp4" })
        evalJsOnWebView("window.__onExternalMediaOpened && window.__onExternalMediaOpened($safeUri, $safeName);")
    }

    private fun syncStateOnMainThread() {
        try {
            cachedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration
            cachedDurationMs = if (dur != C.TIME_UNSET && dur > 0L) dur else 0L
            cachedIsPlaying = exoPlayer.isPlaying
        } catch (_: Exception) {
        }
    }

    fun storeMediaUri(uri: Uri, displayName: String = "", isExternalIntent: Boolean = false) {
        currentVideoUri = uri
        if (displayName.isNotBlank()) {
            currentDisplayName = displayName
        } else if (currentDisplayName.isBlank()) {
            currentDisplayName = uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
        }
        isExternalIntentMedia = isExternalIntent
    }

    fun setMediaUri(uri: Uri) {
        storeMediaUri(uri)
        prepareStoredVideo()
    }

    @JavascriptInterface
    fun getStoredMediaUri(): String {
        return currentVideoUri?.toString() ?: ""
    }

    @JavascriptInterface
    fun getStoredMediaName(): String {
        return currentDisplayName
    }

    @JavascriptInterface
    fun getExternalMediaInfoJson(): String {
        val uri = currentVideoUri ?: return "{}"
        return JSONObject().apply {
            put("hasMedia", true)
            put("isExternalIntent", isExternalIntentMedia)
            put("uri", uri.toString())
            put("name", currentDisplayName.ifBlank { "video.mp4" })
        }.toString()
    }

    @JavascriptInterface
    fun startForegroundService(statusText: String = "Генерация субтитров...") {
        try {
            val intent = Intent(context, SubtitleForegroundService::class.java).apply {
                action = SubtitleForegroundService.ACTION_START_OR_UPDATE
                putExtra(SubtitleForegroundService.EXTRA_STATUS_TEXT, statusText)
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun updateForegroundNotification(statusText: String) {
        startForegroundService(statusText)
    }

    @JavascriptInterface
    fun stopForegroundService() {
        try {
            val intent = Intent(context, SubtitleForegroundService::class.java).apply {
                action = SubtitleForegroundService.ACTION_STOP
            }
            context.startService(intent)
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun prepareStoredVideo() {
        val uri = currentVideoUri ?: return
        mainHandler.post {
            try {
                val mediaItem = MediaItem.fromUri(uri)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.volume = 1.0f
                isVideoReady.value = true
                syncStateOnMainThread()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка загрузки видео: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun setImmersiveFullscreen(enabled: Boolean) {
        activity?.applyImmersiveMode(enabled)
    }

    @JavascriptInterface
    fun toggleLandscape(landscape: Boolean) {
        activity?.applyLandscapeOrientation(landscape)
    }

    @JavascriptInterface
    fun getCurrentTime(): Double {
        return cachedPositionMs / 1000.0
    }

    @JavascriptInterface
    fun getCurrentTimeMs(): Long {
        return cachedPositionMs
    }

    @JavascriptInterface
    fun getDuration(): Double {
        return cachedDurationMs / 1000.0
    }

    @JavascriptInterface
    fun isPlaying(): Boolean {
        return cachedIsPlaying
    }

    @JavascriptInterface
    fun isPaused(): Boolean {
        return !cachedIsPlaying
    }

    @JavascriptInterface
    fun getVideoWidth(): Int = cachedVideoWidth

    @JavascriptInterface
    fun getVideoHeight(): Int = cachedVideoHeight

    @JavascriptInterface
    fun hasNativeVideo(): Boolean = currentVideoUri != null

    @JavascriptInterface
    fun play() {
        cachedIsPlaying = true
        mainHandler.post {
            if (exoPlayer.playbackState == Player.STATE_IDLE && currentVideoUri != null) {
                exoPlayer.setMediaItem(MediaItem.fromUri(currentVideoUri!!))
                exoPlayer.prepare()
            } else if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0L)
            }
            if (exoPlayer.volume <= 0f) exoPlayer.volume = 1.0f
            exoPlayer.play()
            syncStateOnMainThread()
        }
    }

    @JavascriptInterface
    fun pause() {
        cachedIsPlaying = false
        mainHandler.post {
            exoPlayer.pause()
            syncStateOnMainThread()
        }
    }

    @JavascriptInterface
    fun togglePlay(): Boolean {
        val nextPlay = !cachedIsPlaying
        cachedIsPlaying = nextPlay
        mainHandler.post {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                if (exoPlayer.playbackState == Player.STATE_IDLE && currentVideoUri != null) {
                    exoPlayer.setMediaItem(MediaItem.fromUri(currentVideoUri!!))
                    exoPlayer.prepare()
                } else if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0L)
                }
                if (exoPlayer.volume <= 0f) exoPlayer.volume = 1.0f
                exoPlayer.play()
            }
            syncStateOnMainThread()
        }
        return nextPlay
    }

    @JavascriptInterface
    fun seekTo(seconds: Double) {
        val targetMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)
        cachedPositionMs = targetMs
        mainHandler.post {
            exoPlayer.seekTo(targetMs)
            syncStateOnMainThread()
        }
    }

    @JavascriptInterface
    fun seekBy(deltaSeconds: Double) {
        mainHandler.post {
            val dur = if (exoPlayer.duration != C.TIME_UNSET && exoPlayer.duration > 0) {
                exoPlayer.duration
            } else {
                Long.MAX_VALUE
            }
            val nextMs = (exoPlayer.currentPosition + (deltaSeconds * 1000.0).toLong())
                .coerceIn(0L, dur)
            cachedPositionMs = nextMs
            exoPlayer.seekTo(nextMs)
            syncStateOnMainThread()
        }
    }

    @JavascriptInterface
    fun setVolume(volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 200)
        mainHandler.post {
            if (clamped <= 100) {
                exoPlayer.volume = clamped / 100f
                try {
                    loudnessEnhancer?.enabled = false
                } catch (_: Exception) {
                }
            } else {
                exoPlayer.volume = 1.0f
                try {
                    val sessionId = exoPlayer.audioSessionId
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
                        if (loudnessEnhancer == null) {
                            loudnessEnhancer = LoudnessEnhancer(sessionId)
                        }
                        val gainMb = ((clamped - 100) * 15).coerceAtMost(1500)
                        loudnessEnhancer?.setTargetGain(gainMb)
                        loudnessEnhancer?.enabled = true
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    @JavascriptInterface
    fun setSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(0.25f, 3.0f)
        mainHandler.post {
            exoPlayer.playbackParameters = PlaybackParameters(safeSpeed)
        }
    }

    @JavascriptInterface
    fun setResizeMode(mode: String) {
        mainHandler.post {
            resizeModeState.intValue = when (mode.lowercase()) {
                "cover" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
    }

    @JavascriptInterface
    fun setVideoZoom(zoomPercent: Int) {
        val clamped = zoomPercent.coerceIn(50, 300)
        mainHandler.post {
            videoZoomState.floatValue = clamped / 100f
        }
    }

    @JavascriptInterface
    fun setPlayerVisibility(visible: Boolean) {
        mainHandler.post {
            videoBoundsState.value = videoBoundsState.value.copy(isVisible = visible)
        }
    }

    @JavascriptInterface
    fun setPlayerBounds(
        leftCss: Float,
        topCss: Float,
        widthCss: Float,
        heightCss: Float,
        viewportW: Float,
        viewportH: Float,
        isFullscreen: Boolean
    ) {
        if (viewportW <= 0f || viewportH <= 0f) return
        mainHandler.post {
            videoBoundsState.value = VideoBounds(
                leftRatio = (leftCss / viewportW).coerceIn(-1f, 2f),
                topRatio = (topCss / viewportH).coerceIn(-3f, 3f),
                widthRatio = (widthCss / viewportW).coerceIn(0.05f, 1f),
                heightRatio = (heightCss / viewportH).coerceIn(0.05f, 1f),
                isFullscreen = isFullscreen,
                isVisible = videoBoundsState.value.isVisible
            )
        }
    }

    @JavascriptInterface
    fun getCurrentFrameBase64(): String {
        val uri = currentVideoUri ?: return ""
        val retriever = MediaMetadataRetriever()
        var pfd: ParcelFileDescriptor? = null
        return try {
            if (uri.scheme == "content" || uri.scheme == "file") {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    retriever.setDataSource(pfd.fileDescriptor)
                } else {
                    retriever.setDataSource(context, uri)
                }
            } else {
                retriever.setDataSource(uri.toString(), HashMap())
            }
            val timeUs = if (cachedPositionMs > 100L) cachedPositionMs * 1000L else 1000000L
            val rawBitmap = retriever.getFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0L) ?: retriever.getFrameAtTime() ?: return ""

            val maxDim = 512
            val scale = minOf(1f, maxDim.toFloat() / maxOf(rawBitmap.width, rawBitmap.height).coerceAtLeast(1))
            val scaledW = (rawBitmap.width * scale).toInt().coerceAtLeast(1)
            val scaledH = (rawBitmap.height * scale).toInt().coerceAtLeast(1)
            val scaledBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(rawBitmap, scaledW, scaledH, true)
            } else {
                rawBitmap
            }

            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            if (scaledBitmap != rawBitmap) {
                scaledBitmap.recycle()
            }
            rawBitmap.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun openWithExternalApp() {
        val uri = currentVideoUri ?: return
        mainHandler.post {
            try {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, context.contentResolver.getType(uri) ?: "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(viewIntent, "Открыть с помощью...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось открыть меню: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun saveSubtitleFile(fileName: String, content: String, format: String): Boolean {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._\\-а-яА-ЯёЁ ]"), "_").ifBlank {
            "subtitles.${format.ifBlank { "vtt" }}"
        }
        val mimeType = when (format.lowercase()) {
            "vtt" -> "text/vtt"
            "srt" -> "application/x-subrip"
            "ass" -> "text/x-ssa"
            else -> "text/plain"
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val outFile = File(downloadsDir, safeName)
                FileOutputStream(outFile).use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Сохранено в Загрузки: $safeName",
                    Toast.LENGTH_LONG
                ).show()
            }
            true
        } catch (e: Exception) {
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Ошибка сохранения: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            false
        }
    }

    /* ==================== ПОЛНОСТЬЮ НАТИВНЫЙ КОНВЕЙЕР КОТЛИН (ИЗВЛЕЧЕНИЕ 16 КГЦ M4A + ПАРАЛЛЕЛЬНАЯ ОТПРАВКА В GROQ):
       1. JS вызывает configureGroqSession(apiKey, audioLang) один раз при старте.
       2. JS вызывает generateSubtitlesNative(...) для запуска полного цикла в Kotlin.
       3. Kotlin нарезает аудио в M4A (AAC, 16000 Гц, Mono) по 5 минут (FFmpegKit или асинхронный MediaCodec.Callback).
       4. Kotlin через Coroutines (Semaphore(4)) и HttpURLConnection параллельно отправляет M4A-файлы напрямую в Groq
          без передачи тяжелых Base64-строк через WebView.
       5. Kotlin собирает все ответы (слова и сегменты с таймкодами) в единый JSON и один раз возвращает его в JS! ==================== */

    /**
     * Принимает ключ Groq и язык аудио из JavaScript один раз при старте (или при изменении настроек).
     */
    @JavascriptInterface
    fun configureGroqSession(apiKey: String, audioLanguage: String) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isNotEmpty()) {
            sessionGroqApiKey = cleanKey
            saveApiKey(cleanKey)
        } else if (sessionGroqApiKey.isEmpty()) {
            sessionGroqApiKey = getApiKey()
        }
        val cleanLang = audioLanguage.trim().lowercase()
        sessionAudioLanguage = if (cleanLang.isNotEmpty()) cleanLang else "auto"
    }

    @JavascriptInterface
    fun cancelNativeSubtitles() {
        cancelAudioExtraction = true
        activeSubtitleJob?.cancel()
        activeFFmpegSessionId?.let { sessionId ->
            try {
                val ffmpegKitClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
                ffmpegKitClass.getMethod("cancel", Long::class.javaPrimitiveType).invoke(null, sessionId)
            } catch (_: Exception) {
            }
        }
        stopForegroundService()
        setWebViewHardwareAcceleration(true)
    }

    @JavascriptInterface
    fun cancelNativeAudioExtraction() {
        cancelNativeSubtitles()
    }

    @JavascriptInterface
    fun clearAudioCache(): Boolean {
        return try {
            val chunkDir = File(context.cacheDir, "sublater_chunks")
            if (chunkDir.exists()) {
                chunkDir.listFiles()?.forEach { it.delete() }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Главная функция, вызываемая из JavaScript:
     * выполняет извлечение аудио (16 кГц, M4A, по 5 минут), параллельную отправку чанков в Groq
     * через Kotlin Coroutines + HttpURLConnection и возвращает один собранный JSON с таймкодами в JS.
     */
    @JavascriptInterface
    fun generateSubtitlesNative(
        apiKey: String,
        model: String,
        audioLanguage: String,
        customPrompt: String,
        filterHallucinations: Boolean
    ) {
        configureGroqSession(apiKey, audioLanguage)
        val keys = parseApiKeys(sessionGroqApiKey.ifBlank { getApiKey() })
        if (keys.isEmpty()) {
            evalJsOnWebView("window.__onNativeSubtitlesError && window.__onNativeSubtitlesError('Укажите API ключ Groq в настройках (⚙️)!');")
            return
        }

        val targetUri = currentVideoUri
        if (targetUri == null) {
            evalJsOnWebView("window.__onNativeSubtitlesError && window.__onNativeSubtitlesError('Видеофайл не выбран!');")
            return
        }

        activeSubtitleJob?.cancel()
        cancelAudioExtraction = false
        setWebViewHardwareAcceleration(false)
        startForegroundService("Подготовка аудио 16 кГц (M4A)...")

        activeSubtitleJob = ioScope.launch {
            try {
                val reportProgress: (Int, String) -> Unit = { pct, msg ->
                    updateForegroundNotification(msg)
                    val safeMsg = JSONObject.quote(msg)
                    evalJsOnWebView("window.__onNativeSubtitlesProgress && window.__onNativeSubtitlesProgress($pct, $safeMsg);")
                }

                // Шаг 1: Извлечение и нарезка аудио по 5 минут в M4A (AAC, 16000 Гц, Mono)
                val chunks = extractHybridAudioToM4a(targetUri) { extractPct, extractMsg ->
                    val scaledPct = 5 + ((extractPct * 20) / 100)
                    reportProgress(scaledPct.coerceIn(5, 25), extractMsg)
                }

                ensureActive()
                if (chunks.isEmpty()) {
                    throw IllegalStateException("Не удалось извлечь аудиодорожку из видео.")
                }

                // Формируем контекстный промпт (название фильма/сериала из имени файла + словарь имен/терминов)
                val fullContextPrompt = buildContextualPrompt(currentDisplayName, customPrompt)
                val resolvedLanguage = resolveExplicitAudioLanguage(audioLanguage.ifBlank { sessionAudioLanguage }, currentDisplayName, customPrompt)
                val cleanModel = model.ifBlank { "whisper-large-v3-turbo" }

                val totalChunks = chunks.size
                val completedCounter = AtomicInteger(0)
                val semaphore = Semaphore(PARALLEL_GROQ_CONCURRENCY)

                reportProgress(
                    26,
                    "🚀 Параллельная отправка M4A в Groq (${minOf(PARALLEL_GROQ_CONCURRENCY, totalChunks)} потока, частей: $totalChunks)..."
                )

                // Шаг 2: Параллельная отправка M4A-чанков напрямую из Kotlin в Groq через Coroutines (по 4 чанка одновременно)
                val results: List<ChunkTranscriptionResult> = coroutineScope {
                    chunks.map { chunk ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                ensureActive()
                                val res = sendChunkToGroqWithRetry(
                                    chunk = chunk,
                                    keys = keys,
                                    model = cleanModel,
                                    prompt = fullContextPrompt,
                                    language = resolvedLanguage,
                                    filterHallucinations = filterHallucinations,
                                    onStatus = { retryMsg ->
                                        reportProgress(
                                            26 + ((completedCounter.get() * 68) / maxOf(1, totalChunks)),
                                            retryMsg
                                        )
                                    }
                                )
                                val doneNow = completedCounter.incrementAndGet()
                                val pct = 26 + ((doneNow * 68) / maxOf(1, totalChunks))
                                reportProgress(
                                    pct.coerceIn(26, 95),
                                    "🚀 Распознано в Groq: $doneNow из $totalChunks (${chunk.startSec.toInt()}–${chunk.endSec.toInt()} с)..."
                                )
                                res
                            }
                        }
                    }.awaitAll()
                }

                // Шаг 3: Сборка всех ответов от Groq в один большой JSON-объект (сегменты и слова с таймкодами)
                val mergedSegments = JSONArray()
                val mergedWords = JSONArray()

                for (item in results.sortedBy { it.chunk.index }) {
                    val cPos = item.chunk.startSec
                    val cCutoff = item.chunk.cutoffSec
                    val cNextCutoff = item.chunk.nextCutoffSec

                    for (seg in item.segments) {
                        val absMid = (seg.start + seg.end) / 2.0 + cPos
                        if (absMid < cCutoff || absMid >= cNextCutoff) continue
                        mergedSegments.put(JSONObject().apply {
                            put("start", seg.start + cPos)
                            put("end", seg.end + cPos)
                            put("text", seg.text)
                        })
                    }

                    for (w in item.words) {
                        val absMid = (w.start + w.end) / 2.0 + cPos
                        if (absMid < cCutoff || absMid >= cNextCutoff) continue
                        mergedWords.put(JSONObject().apply {
                            put("word", w.word)
                            put("start", w.start + cPos)
                            put("end", w.end + cPos)
                        })
                    }
                }

                if (mergedSegments.length() == 0) {
                    throw IllegalStateException("В аудиодорожке не удалось распознать речь.")
                }

                val finalPayload = JSONObject().apply {
                    put("segments", mergedSegments)
                    put("words", mergedWords)
                    put("language", resolvedLanguage)
                    put("fileName", currentDisplayName)
                }.toString()

                val escapedPayload = JSONObject.quote(finalPayload)
                evalJsOnWebView("window.__onNativeSubtitlesDone && window.__onNativeSubtitlesDone($escapedPayload);")
            } catch (e: CancellationException) {
                evalJsOnWebView("window.__onNativeSubtitlesError && window.__onNativeSubtitlesError('Отменено пользователем.');")
            } catch (e: InterruptedException) {
                evalJsOnWebView("window.__onNativeSubtitlesError && window.__onNativeSubtitlesError('Отменено пользователем.');")
            } catch (e: Throwable) {
                val safeErr = JSONObject.quote(e.message ?: "Ошибка генерации субтитров")
                evalJsOnWebView("window.__onNativeSubtitlesError && window.__onNativeSubtitlesError($safeErr);")
            } finally {
                clearAudioCache()
                stopForegroundService()
                setWebViewHardwareAcceleration(true)
            }
        }
    }

    private fun parseApiKeys(rawKeys: String): List<String> {
        return rawKeys.split(Regex("[\\s,;]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun getNextApiKey(keys: List<String>, rotate: Boolean = false): String {
        if (keys.isEmpty()) return ""
        val idx = if (rotate && keys.size > 1) {
            activeKeyIndex.incrementAndGet()
        } else {
            activeKeyIndex.get()
        }
        return keys[Math.floorMod(idx, keys.size)]
    }

    private fun buildContextualPrompt(rawFileName: String, customTerms: String): String {
        val withoutExt = rawFileName.replace(Regex("\\.[^/.]+$"), "")
            .replace(Regex("[._]+"), " ")
        val cleanTitle = withoutExt
            .replace(
                Regex(
                    "\\b(2160p|1080p|720p|480p|4k|uhd|web[-\\s]?dl|webrip|bdrip|bluray|hdrip|dvdrip|x264|x265|h264|h265|hevc|avc|aac|ac3|eac3|dts|10bit|hdr|proper|repack)\\b",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )
            .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .ifBlank { withoutExt.trim() }

        val parts = mutableListOf<String>()
        if (cleanTitle.isNotBlank()) {
            parts.add("Фильм/сериал: $cleanTitle.")
        }
        val terms = customTerms.trim()
        if (terms.isNotBlank()) {
            parts.add("Ключевые термины и имена персонажей: $terms.")
        }
        return parts.joinToString(" ").take(800)
    }

    private fun resolveExplicitAudioLanguage(selectedLang: String, rawFileName: String, customTerms: String): String {
        val cleanSelected = selectedLang.trim().lowercase()
        if (cleanSelected.isNotEmpty() && cleanSelected != "auto") {
            return cleanSelected
        }
        val lowerName = rawFileName.lowercase()
        if (Regex("\\b(rus|ru|russian|рус|дубляж|озвучка)\\b").containsMatchIn(lowerName) ||
            Regex("[а-яё]").containsMatchIn(lowerName)
        ) {
            return "ru"
        }
        if (Regex("\\b(eng|en|english)\\b").containsMatchIn(lowerName)) return "en"
        if (Regex("\\b(tur|tr|turkish)\\b").containsMatchIn(lowerName)) return "tr"
        if (Regex("\\b(aze|az|azerbaijani)\\b").containsMatchIn(lowerName)) return "az"
        if (Regex("[а-яё]", RegexOption.IGNORE_CASE).containsMatchIn(customTerms)) return "ru"
        return ""
    }

    private fun isWhisperHallucination(segObj: JSONObject, filterEnabled: Boolean): Boolean {
        val text = segObj.optString("text", "").trim()
        if (text.isEmpty()) return true
        if (!filterEnabled) return false
        if (HALLUCINATION_REGEX.containsMatchIn(text)) return true
        val noSpeechProb = segObj.optDouble("no_speech_prob", 0.0)
        val avgLogprob = segObj.optDouble("avg_logprob", 0.0)
        if (noSpeechProb > 0.85 && (avgLogprob < -0.8 || text.length < 16)) return true
        val compressionRatio = segObj.optDouble("compression_ratio", 1.0)
        if (compressionRatio > 2.6) return true
        return false
    }

    /**
     * Отправляет один M4A-чанк напрямую в Groq API через HttpURLConnection с таймаутом 45 секунд
     * и до 2 повторных попыток (плюс ротация ключей при HTTP 429).
     */
    private suspend fun sendChunkToGroqWithRetry(
        chunk: AudioChunkMeta,
        keys: List<String>,
        model: String,
        prompt: String,
        language: String,
        filterHallucinations: Boolean,
        onStatus: (String) -> Unit
    ): ChunkTranscriptionResult {
        var lastError: Throwable? = null

        for (attempt in 0..MAX_CHUNK_RETRIES) {
            coroutineScope { ensureActive() }
            try {
                return withTimeout(CHUNK_TIMEOUT_MS) {
                    executeGroqMultipartRequest(
                        chunk = chunk,
                        keys = keys,
                        model = model,
                        prompt = prompt,
                        language = language,
                        filterHallucinations = filterHallucinations,
                        onStatus = onStatus
                    )
                }
            } catch (e: CancellationException) {
                if (cancelAudioExtraction) throw e
                // Сработал 45-секундный withTimeout для данного чанка
                lastError = IllegalStateException("Таймаут чанка ${chunk.file.name} (>45 сек)")
                if (attempt < MAX_CHUNK_RETRIES) {
                    onStatus("⏱ Таймаут ${chunk.file.name} (>45 с). Повторная отправка (${attempt + 1}/$MAX_CHUNK_RETRIES)...")
                    delay(800L)
                }
            } catch (e: Throwable) {
                if (cancelAudioExtraction) throw CancellationException("Отменено пользователем.")
                lastError = e
                if (attempt < MAX_CHUNK_RETRIES) {
                    onStatus("🔄 Повтор отправки ${chunk.file.name} (${attempt + 1}/$MAX_CHUNK_RETRIES)...")
                    delay(1200L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("Не удалось отправить ${chunk.file.name} в Groq")
    }

    private suspend fun executeGroqMultipartRequest(
        chunk: AudioChunkMeta,
        keys: List<String>,
        model: String,
        prompt: String,
        language: String,
        filterHallucinations: Boolean,
        onStatus: (String) -> Unit
    ): ChunkTranscriptionResult {
        var keyRotations = 0
        val maxKeyRotations = maxOf(1, keys.size)

        while (true) {
            coroutineScope { ensureActive() }
            val apiKey = getNextApiKey(keys, rotate = false)
            val boundary = "----SubLaterGroqBoundary" + UUID.randomUUID().toString().replace("-", "")
            val url = URL("https://api.groq.com/openai/v1/audio/transcriptions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = 20_000
                readTimeout = 45_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setChunkedStreamingMode(65536)
            }

            try {
                DataOutputStream(BufferedOutputStream(conn.outputStream, 65536)).use { out ->
                    fun writeFormField(name: String, value: String) {
                        out.writeBytes("--$boundary\r\n")
                        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                        out.write(value.toByteArray(Charsets.UTF_8))
                        out.writeBytes("\r\n")
                    }

                    writeFormField("model", model)
                    writeFormField("temperature", "0")
                    writeFormField("response_format", "verbose_json")
                    writeFormField("timestamp_granularities[]", "word")
                    writeFormField("timestamp_granularities[]", "segment")
                    if (prompt.isNotBlank()) {
                        writeFormField("prompt", prompt)
                    }
                    if (language.isNotBlank() && language != "auto") {
                        writeFormField("language", language)
                    }

                    // Стримим бинарный M4A-файл напрямую с диска в сокет без Base64 и без загрузки в ОЗУ
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${chunk.file.name}\"\r\n")
                    out.writeBytes("Content-Type: audio/mp4\r\n\r\n")

                    FileInputStream(chunk.file).use { fileIn ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        while (fileIn.read(buffer).also { read = it } != -1) {
                            if (cancelAudioExtraction) throw CancellationException("Отменено пользователем.")
                            out.write(buffer, 0, read)
                        }
                    }

                    out.writeBytes("\r\n--$boundary--\r\n")
                    out.flush()
                }

                val statusCode = conn.responseCode
                if (statusCode == 429) {
                    if (keys.size > 1 && keyRotations < maxKeyRotations) {
                        keyRotations++
                        getNextApiKey(keys, rotate = true)
                        onStatus("🔄 Лимит на ключе. Переключение на следующий ключ Groq...")
                        continue
                    }
                    val retryAfterSec = conn.getHeaderField("retry-after")?.toLongOrNull()?.coerceIn(1L, 25L) ?: 4L
                    onStatus("⏳ Лимит запросов Groq (429). Ожидание ${retryAfterSec} с...")
                    delay(retryAfterSec * 1000L)
                    throw IllegalStateException("HTTP 429 Rate Limit")
                }

                if (statusCode !in 200..299) {
                    val errText = try {
                        conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    if (statusCode == 401) {
                        throw IllegalStateException("Неверный API-ключ Groq (HTTP 401).")
                    }
                    throw IllegalStateException("Ошибка Groq HTTP $statusCode: ${errText.take(160)}")
                }

                val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(responseBody)

                val segmentsList = mutableListOf<TranscribedSegment>()
                val rawSegments = json.optJSONArray("segments")
                if (rawSegments != null) {
                    for (i in 0 until rawSegments.length()) {
                        val sObj = rawSegments.optJSONObject(i) ?: continue
                        if (isWhisperHallucination(sObj, filterHallucinations)) continue
                        val start = sObj.optDouble("start", 0.0)
                        val end = sObj.optDouble("end", 0.0)
                        val text = sObj.optString("text", "").trim()
                        if (text.isNotEmpty() && end > start) {
                            segmentsList.add(TranscribedSegment(start = start, end = end, text = text))
                        }
                    }
                }

                val wordsList = mutableListOf<TranscribedWord>()
                val rawWords = json.optJSONArray("words")
                if (rawWords != null) {
                    for (i in 0 until rawWords.length()) {
                        val wObj = rawWords.optJSONObject(i) ?: continue
                        val wordText = wObj.optString("word", "").trim()
                        val start = wObj.optDouble("start", Double.NaN)
                        var end = wObj.optDouble("end", Double.NaN)
                        if (wordText.isNotEmpty() && !start.isNaN() && !end.isNaN()) {
                            if (end <= start) end = start + 0.08
                            wordsList.add(TranscribedWord(word = wordText, start = start, end = end))
                        }
                    }
                }

                return ChunkTranscriptionResult(
                    chunk = chunk,
                    segments = segmentsList,
                    words = wordsList
                )
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Полностью переписанная функция извлечения и нарезки аудио в формат M4A (AAC, строго 16000 Гц, Mono)
     * чанками по 5 минут (300 секунд):
     * 1) Сначала пытается выполнить сверхбыструю нарезку через FFmpeg (ffmpeg-kit).
     * 2) Если FFmpeg недоступен, использует асинхронный MediaCodec в режиме CALLBACK + MediaMuxer.
     */
    private fun extractHybridAudioToM4a(
        uri: Uri,
        onProgress: (Int, String) -> Unit
    ): List<AudioChunkMeta> {
        val chunkDir = File(context.cacheDir, "sublater_chunks")
        if (!chunkDir.exists()) chunkDir.mkdirs()
        chunkDir.listFiles()?.forEach { it.delete() }

        // 1. Попытка извлечения и нарезки по 5 минут через FFmpeg (ffmpeg-kit) в M4A (AAC, 16000 Гц, Mono)
        val ffmpegChunks = tryExtractWithFFmpegKit(uri, chunkDir, onProgress)
        if (!ffmpegChunks.isNullOrEmpty()) {
            return ffmpegChunks
        }

        if (cancelAudioExtraction) {
            throw InterruptedException("Отменено пользователем.")
        }

        // 2. Фоллбэк: асинхронный MediaCodec в режиме CALLBACK + MediaMuxer (строго 16000 Гц, Mono, M4A, чанки по 5 минут)
        return extractWithAsyncMediaCodecCallback(uri, chunkDir, onProgress)
    }

    private fun tryExtractWithFFmpegKit(
        uri: Uri,
        chunkDir: File,
        onProgress: (Int, String) -> Unit
    ): List<AudioChunkMeta>? {
        var tempInputFile: File? = null
        return try {
            val ffmpegKitClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            val ffmpegKitConfigClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig")
            val returnCodeClass = Class.forName("com.arthenica.ffmpegkit.ReturnCode")

            onProgress(5, "⚡ FFmpeg: подготовка аудио 16 кГц (M4A)...")

            val inputPath: String = when (uri.scheme) {
                "file" -> uri.path ?: return null
                "http", "https" -> uri.toString()
                "content" -> {
                    val safParam = try {
                        ffmpegKitConfigClass
                            .getMethod("getSafParameterForRead", Context::class.java, Uri::class.java)
                            .invoke(null, context, uri) as? String
                    } catch (_: Exception) {
                        null
                    }
                    if (!safParam.isNullOrBlank()) {
                        safParam
                    } else {
                        val tmp = File(chunkDir, "ffmpeg_input_temp.bin")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tmp).use { output ->
                                val buf = ByteArray(131072)
                                var r: Int
                                while (input.read(buf).also { r = it } != -1) {
                                    if (cancelAudioExtraction) throw InterruptedException("Отменено пользователем.")
                                    output.write(buf, 0, r)
                                }
                            }
                        }
                        if (!tmp.exists() || tmp.length() == 0L) return null
                        tempInputFile = tmp
                        tmp.absolutePath
                    }
                }
                else -> return null
            }

            val totalDurationSec = resolveMediaDurationSec(uri).takeIf { it > 0.0 }
                ?: (cachedDurationMs / 1000.0).takeIf { it > 0.0 }
                ?: 0.0

            val chunkDuration = CHUNK_DURATION_SEC.toDouble()
            val overlap = CHUNK_OVERLAP_SEC.toDouble()
            val advance = chunkDuration - overlap
            val resultList = mutableListOf<AudioChunkMeta>()

            if (totalDurationSec > chunkDuration + 1.0) {
                var posSec = 0.0
                var chunkIndex = 0
                val estimatedTotalChunks = maxOf(1, Math.ceil((totalDurationSec - overlap) / advance).toInt())

                while (posSec < totalDurationSec - 0.2) {
                    if (cancelAudioExtraction) throw InterruptedException("Отменено пользователем.")
                    val endSec = minOf(posSec + chunkDuration, totalDurationSec)
                    val curDur = endSec - posSec
                    if (curDur < 0.5) break

                    val outFile = File(chunkDir, "chunk_${chunkIndex}.m4a")
                    if (outFile.exists()) outFile.delete()

                    val cmd = "-y -ss $posSec -t $curDur -i \"$inputPath\" -vn -ac $WHISPER_CHANNELS_MONO -ar $WHISPER_SAMPLE_RATE_HZ -c:a aac -b:a $WHISPER_AAC_BITRATE -movflags +faststart \"${outFile.absolutePath}\""
                    val session = ffmpegKitClass.getMethod("execute", String::class.java).invoke(null, cmd) ?: return null
                    activeFFmpegSessionId = try {
                        session.javaClass.getMethod("getSessionId").invoke(session) as? Long
                    } catch (_: Exception) {
                        null
                    }
                    val returnCode = session.javaClass.getMethod("getReturnCode").invoke(session)
                    val isSuccess = returnCodeClass.getMethod("isValueSuccess", returnCodeClass).invoke(null, returnCode) as? Boolean ?: false

                    if (!isSuccess || !outFile.exists() || outFile.length() <= 128L) {
                        outFile.delete()
                        return null
                    }

                    val isLast = endSec >= totalDurationSec - 0.5
                    val cutoffSec = if (chunkIndex == 0) 0.0 else (posSec + overlap / 2.0)
                    val nextCutoffSec = if (isLast) (endSec + 100.0) else (endSec - overlap / 2.0)

                    resultList.add(
                        AudioChunkMeta(
                            index = chunkIndex,
                            file = outFile,
                            startSec = posSec,
                            endSec = endSec,
                            cutoffSec = cutoffSec,
                            nextCutoffSec = nextCutoffSec
                        )
                    )

                    val pct = (((chunkIndex + 1).toDouble() / estimatedTotalChunks.toDouble()) * 95.0).toInt().coerceIn(5, 99)
                    onProgress(pct, "⚡ FFmpeg 16 кГц (M4A): чанк ${chunkIndex + 1} из $estimatedTotalChunks...")

                    if (isLast) break
                    posSec += advance
                    chunkIndex++
                }
            } else {
                val pattern = File(chunkDir, "chunk_%d.m4a").absolutePath
                val cmd = "-y -i \"$inputPath\" -vn -ac $WHISPER_CHANNELS_MONO -ar $WHISPER_SAMPLE_RATE_HZ -c:a aac -b:a $WHISPER_AAC_BITRATE -f segment -segment_time $CHUNK_DURATION_SEC -reset_timestamps 1 \"$pattern\""
                val session = ffmpegKitClass.getMethod("execute", String::class.java).invoke(null, cmd) ?: return null
                val returnCode = session.javaClass.getMethod("getReturnCode").invoke(session)
                val isSuccess = returnCodeClass.getMethod("isValueSuccess", returnCodeClass).invoke(null, returnCode) as? Boolean ?: false
                if (!isSuccess) return null

                val generatedFiles = chunkDir.listFiles { f -> f.name.startsWith("chunk_") && f.name.endsWith(".m4a") && f.length() > 128L }
                    ?.sortedBy { f -> f.name.removePrefix("chunk_").removeSuffix(".m4a").toIntOrNull() ?: 0 }
                    ?: emptyList()

                if (generatedFiles.isEmpty()) return null

                for ((idx, file) in generatedFiles.withIndex()) {
                    val startSec = idx * chunkDuration
                    val isLast = idx == generatedFiles.lastIndex
                    val endSec = if (isLast && totalDurationSec > startSec) totalDurationSec else (startSec + chunkDuration)
                    resultList.add(
                        AudioChunkMeta(
                            index = idx,
                            file = file,
                            startSec = startSec,
                            endSec = endSec,
                            cutoffSec = startSec,
                            nextCutoffSec = if (isLast) endSec + 100.0 else endSec
                        )
                    )
                }
            }

            resultList
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Throwable) {
            null
        } finally {
            activeFFmpegSessionId = null
            try { tempInputFile?.delete() } catch (_: Exception) {}
        }
    }

    private fun resolveMediaDurationSec(uri: Uri): Double {
        val retriever = MediaMetadataRetriever()
        var pfd: ParcelFileDescriptor? = null
        return try {
            if (uri.scheme == "content" || uri.scheme == "file") {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    retriever.setDataSource(pfd.fileDescriptor)
                } else {
                    retriever.setDataSource(context, uri)
                }
            } else {
                retriever.setDataSource(uri.toString(), HashMap())
            }
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durMs / 1000.0
        } catch (_: Exception) {
            0.0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Асинхронный конвейер декодирования и сжатия аудио через MediaCodec в режиме CALLBACK + MediaMuxer:
     * приводит сэмплы строго к 16000 Гц (16 кГц) Mono и нарезает по 5 минут (300 с) в формат M4A (AAC).
     */
    private fun extractWithAsyncMediaCodecCallback(
        uri: Uri,
        chunkDir: File,
        onProgress: (Int, String) -> Unit
    ): List<AudioChunkMeta> {
        val extractor = MediaExtractor()
        var pfd: ParcelFileDescriptor? = null
        var tempCopiedFile: File? = null
        var decoder: MediaCodec? = null
        var decoderThread: HandlerThread? = null
        var currentWriter: AsyncM4aChunkMuxer? = null

        try {
            var dataSourceSet = false
            if (uri.scheme == "http" || uri.scheme == "https") {
                extractor.setDataSource(uri.toString(), HashMap())
                dataSourceSet = true
            } else {
                try {
                    pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        extractor.setDataSource(pfd.fileDescriptor)
                        dataSourceSet = true
                    }
                } catch (_: Exception) {
                }

                if (!dataSourceSet) {
                    try {
                        extractor.setDataSource(context, uri, null)
                        dataSourceSet = true
                    } catch (_: Exception) {
                    }
                }

                if (!dataSourceSet) {
                    onProgress(2, "Копирование потока внешнего файла...")
                    val tmp = File(chunkDir, "temp_input_media.bin")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmp).use { output ->
                            val buf = ByteArray(131072)
                            var r: Int
                            while (input.read(buf).also { r = it } != -1) {
                                if (cancelAudioExtraction) throw InterruptedException("Отменено пользователем.")
                                output.write(buf, 0, r)
                            }
                        }
                    }
                    if (tmp.exists() && tmp.length() > 0) {
                        tempCopiedFile = tmp
                        extractor.setDataSource(tmp.absolutePath)
                        dataSourceSet = true
                    }
                }
            }

            if (!dataSourceSet) {
                throw IllegalStateException("Не удалось открыть медиафайл для извлечения аудио")
            }

            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            var selectedTrackIndex = -1
            var selectedFormat: MediaFormat? = null
            var selectedDecoderName: String? = null
            var selectedMime = ""

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val foundName = try {
                        codecList.findDecoderForFormat(format)
                    } catch (_: Exception) {
                        null
                    }
                    if (foundName != null) {
                        selectedTrackIndex = i
                        selectedFormat = format
                        selectedDecoderName = foundName
                        selectedMime = mime
                        break
                    } else if (selectedTrackIndex < 0) {
                        selectedTrackIndex = i
                        selectedFormat = format
                        selectedMime = mime
                    }
                }
            }

            if (selectedTrackIndex < 0 || selectedFormat == null) {
                throw IllegalStateException("В видео не найдена поддерживаемая аудиодорожка")
            }

            extractor.selectTrack(selectedTrackIndex)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val durationUs = if (selectedFormat.containsKey(MediaFormat.KEY_DURATION)) {
                selectedFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                (resolveMediaDurationSec(uri) * 1_000_000.0).toLong().coerceAtLeast(cachedDurationMs * 1000L)
            }

            val targetSampleRate = WHISPER_SAMPLE_RATE_HZ // Строго 16000 Гц
            val targetBitRate = WHISPER_AAC_BITRATE       // 64000 бит/с AAC
            val chunkDurationSec = CHUNK_DURATION_SEC     // 300 секунд (5 минут)
            val overlapSec = CHUNK_OVERLAP_SEC           // 6 секунд перекрытия
            val maxChunkSamples = (chunkDurationSec * targetSampleRate).toLong()
            val overlapBytesCount = overlapSec * targetSampleRate * 2
            val overlapRing = ByteArray(overlapBytesCount)
            var overlapFilled = 0
            var overlapWritePos = 0

            val chunkList = mutableListOf<AudioChunkMeta>()
            var chunkIndex = 0
            var currentChunkStartSample = 0L
            var totalWrittenMonoSamples = 0L

            currentWriter = AsyncM4aChunkMuxer(
                File(chunkDir, "chunk_0.m4a"),
                targetSampleRate,
                targetBitRate
            )

            val pcmBatchBuf = ByteArray(32768)
            var pcmBatchUsed = 0

            fun flushBatchToM4a() {
                if (pcmBatchUsed > 0) {
                    currentWriter?.enqueuePcm16Mono(pcmBatchBuf, pcmBatchUsed)
                    pcmBatchUsed = 0
                }
            }

            fun finalizeCurrentChunk(isLast: Boolean) {
                flushBatchToM4a()
                val writer = currentWriter ?: return
                writer.finishAndClose()
                val writtenSamples = writer.totalSamplesWritten
                val outFile = writer.outputFile
                if (writtenSamples > targetSampleRate / 2 && outFile.exists() && outFile.length() > 128L) {
                    val startSec = currentChunkStartSample.toDouble() / targetSampleRate.toDouble()
                    val durationSec = writtenSamples.toDouble() / targetSampleRate.toDouble()
                    val endSec = startSec + durationSec
                    val cutoffSec = if (chunkIndex == 0) 0.0 else (startSec + overlapSec / 2.0)
                    val nextCutoffSec = if (isLast) (endSec + 100.0) else (endSec - overlapSec / 2.0)

                    chunkList.add(
                        AudioChunkMeta(
                            index = chunkIndex,
                            file = outFile,
                            startSec = startSec,
                            endSec = endSec,
                            cutoffSec = cutoffSec,
                            nextCutoffSec = nextCutoffSec
                        )
                    )
                } else {
                    outFile.delete()
                }
            }

            fun rotateNextChunk() {
                finalizeCurrentChunk(isLast = false)
                chunkIndex++
                val nextWriter = AsyncM4aChunkMuxer(
                    File(chunkDir, "chunk_${chunkIndex}.m4a"),
                    targetSampleRate,
                    targetBitRate
                )
                currentWriter = nextWriter
                val overlapSamples = (overlapFilled / 2).toLong()
                currentChunkStartSample = (totalWrittenMonoSamples - overlapSamples).coerceAtLeast(0L)
                if (overlapFilled > 0) {
                    if (overlapFilled < overlapBytesCount) {
                        nextWriter.enqueuePcm16Mono(overlapRing, overlapFilled)
                    } else {
                        val tailLen = overlapBytesCount - overlapWritePos
                        val orderedOverlap = ByteArray(overlapBytesCount)
                        System.arraycopy(overlapRing, overlapWritePos, orderedOverlap, 0, tailLen)
                        if (overlapWritePos > 0) {
                            System.arraycopy(overlapRing, 0, orderedOverlap, tailLen, overlapWritePos)
                        }
                        nextWriter.enqueuePcm16Mono(orderedOverlap, overlapBytesCount)
                    }
                }
            }

            fun pushMono16kSample(sample: Short) {
                val lo = (sample.toInt() and 0xFF).toByte()
                val hi = ((sample.toInt() ushr 8) and 0xFF).toByte()
                pcmBatchBuf[pcmBatchUsed++] = lo
                pcmBatchBuf[pcmBatchUsed++] = hi
                if (pcmBatchUsed >= pcmBatchBuf.size - 2) {
                    flushBatchToM4a()
                }
                overlapRing[overlapWritePos] = lo
                overlapRing[(overlapWritePos + 1) % overlapBytesCount] = hi
                overlapWritePos = (overlapWritePos + 2) % overlapBytesCount
                if (overlapFilled < overlapBytesCount) overlapFilled += 2
                totalWrittenMonoSamples++

                val activeSamples = (currentWriter?.totalSamplesWritten ?: 0L) + (pcmBatchUsed / 2).toLong()
                if (activeSamples >= maxChunkSamples) {
                    rotateNextChunk()
                }
            }

            var srcSampleRate = if (selectedFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                selectedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else {
                48000
            }
            var srcChannels = if (selectedFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                selectedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                2
            }
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resamplePos = 0.0
            var resampleStep = srcSampleRate.toDouble() / targetSampleRate.toDouble()

            var inputDone = false
            var lastPct = -1
            val decodeDoneLatch = CountDownLatch(1)
            val asyncErrorRef = AtomicReference<Throwable?>(null)

            decoderThread = HandlerThread("SubLaterAsyncDecoder").apply { start() }
            val decoderHandler = Handler(decoderThread.looper)

            val mediaDecoder = if (selectedDecoderName != null) {
                MediaCodec.createByCodecName(selectedDecoderName)
            } else {
                MediaCodec.createDecoderByType(selectedMime)
            }
            decoder = mediaDecoder

            mediaDecoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (cancelAudioExtraction) {
                        asyncErrorRef.compareAndSet(null, InterruptedException("Отменено пользователем."))
                        decodeDoneLatch.countDown()
                        return
                    }
                    if (inputDone || index < 0) return
                    try {
                        val inBuf = codec.getInputBuffer(index) ?: return
                        inBuf.clear()
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(index, 0, sampleSize, ptsUs, 0)
                            extractor.advance()

                            if (durationUs > 0L) {
                                val pct = ((ptsUs.toDouble() / durationUs.toDouble()) * 100.0).toInt().coerceIn(0, 99)
                                if (pct >= lastPct + 4) {
                                    lastPct = pct
                                    onProgress(pct, "⚡ Асинхронная нарезка M4A (16 кГц): $pct% (чанк ${chunkIndex + 1})...")
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        asyncErrorRef.compareAndSet(null, t)
                        decodeDoneLatch.countDown()
                    }
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (cancelAudioExtraction) {
                        asyncErrorRef.compareAndSet(null, InterruptedException("Отменено пользователем."))
                        decodeDoneLatch.countDown()
                        return
                    }
                    try {
                        if (index >= 0) {
                            val outBuf = codec.getOutputBuffer(index)
                            if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)

                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val floatBuf = outBuf.asFloatBuffer()
                                    val totalFrames = floatBuf.remaining() / srcChannels
                                    while (resamplePos < totalFrames) {
                                        val baseIdx = resamplePos.toInt() * srcChannels
                                        var sum = 0f
                                        for (ch in 0 until srcChannels) sum += floatBuf.get(baseIdx + ch)
                                        val monoShort = ((sum / srcChannels).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                        pushMono16kSample(monoShort)
                                        resamplePos += resampleStep
                                    }
                                    resamplePos = (resamplePos - totalFrames.toDouble()).coerceAtLeast(0.0)
                                } else {
                                    val shortBuf = outBuf.asShortBuffer()
                                    val totalFrames = shortBuf.remaining() / srcChannels
                                    while (resamplePos < totalFrames) {
                                        val baseIdx = resamplePos.toInt() * srcChannels
                                        var sum = 0
                                        for (ch in 0 until srcChannels) sum += shortBuf.get(baseIdx + ch).toInt()
                                        val monoShort = (sum / srcChannels).coerceIn(-32768, 32767).toShort()
                                        pushMono16kSample(monoShort)
                                        resamplePos += resampleStep
                                    }
                                    resamplePos = (resamplePos - totalFrames.toDouble()).coerceAtLeast(0.0)
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decodeDoneLatch.countDown()
                            }
                        }
                    } catch (t: Throwable) {
                        asyncErrorRef.compareAndSet(null, t)
                        decodeDoneLatch.countDown()
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, newFmt: MediaFormat) {
                    if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                    }
                    if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        srcChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    resampleStep = srcSampleRate.toDouble() / targetSampleRate.toDouble()
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    asyncErrorRef.compareAndSet(null, e)
                    decodeDoneLatch.countDown()
                }
            }, decoderHandler)

            mediaDecoder.configure(selectedFormat, null, null, 0)
            mediaDecoder.start()

            while (!decodeDoneLatch.await(250, TimeUnit.MILLISECONDS)) {
                if (cancelAudioExtraction) {
                    throw InterruptedException("Отменено пользователем.")
                }
            }

            asyncErrorRef.get()?.let { throw it }
            finalizeCurrentChunk(isLast = true)
            currentWriter = null

            return chunkList
        } finally {
            try { currentWriter?.finishAndClose() } catch (_: Exception) {}
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { decoderThread?.quitSafely() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            try { tempCopiedFile?.delete() } catch (_: Exception) {}
        }
    }

    /* ==================== ЗАЩИТА API-КЛЮЧЕЙ ЧЕРЕЗ ENCRYPTEDSHAREDPREFERENCES (ANDROID KEYSTORE) ==================== */
    private val keystoreAlias = "SubLaterHardwareKey_v1"
    private val fallbackPrefsName = "sublater_keystore_vault"
    private val encryptedPrefsFileName = "sublater_encrypted_api_keys"

    private val encryptedPrefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                encryptedPrefsFileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(keystoreAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keystoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun ensureCleanUserKeystore() {
        try {
            val prefs = context.getSharedPreferences(fallbackPrefsName, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("user_only_keystore_v17", false)) {
                prefs.edit()
                    .remove("groq_api_key")
                    .putBoolean("user_only_keystore_v17", true)
                    .apply()
            }
            if (!prefs.getBoolean("migrated_to_esp_v18", false)) {
                val existingGroq = getSecureKeyFallback("groq_api_key")
                val existingGem = getSecureKeyFallback("gemini_api_key")
                if (existingGroq.isNotBlank()) saveApiKey(existingGroq)
                if (existingGem.isNotBlank()) saveGeminiApiKey(existingGem)
                prefs.edit().putBoolean("migrated_to_esp_v18", true).apply()
            }
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun saveApiKey(key: String): Boolean {
        sessionGroqApiKey = key.trim()
        return saveSecureKey("groq_api_key", key)
    }

    @JavascriptInterface
    fun getApiKey(): String {
        return getSecureKey("groq_api_key")
    }

    @JavascriptInterface
    fun saveGeminiApiKey(key: String): Boolean {
        return saveSecureKey("gemini_api_key", key)
    }

    @JavascriptInterface
    fun getGeminiApiKey(): String {
        return getSecureKey("gemini_api_key")
    }

    @JavascriptInterface
    fun saveSecureKey(alias: String, plainValue: String): Boolean {
        val safeAlias = alias.trim()
        val cleanVal = plainValue.trim()
        try {
            val esp = encryptedPrefs
            if (esp != null) {
                if (cleanVal.isEmpty()) {
                    esp.edit().remove(safeAlias).apply()
                } else {
                    esp.edit().putString(safeAlias, cleanVal).apply()
                }
                return true
            }
        } catch (_: Exception) {
        }
        return saveSecureKeyFallback(safeAlias, cleanVal)
    }

    @JavascriptInterface
    fun getSecureKey(alias: String): String {
        val safeAlias = alias.trim()
        try {
            val esp = encryptedPrefs
            if (esp != null) {
                val v = esp.getString(safeAlias, null)
                if (v != null) return v
            }
        } catch (_: Exception) {
        }
        return getSecureKeyFallback(safeAlias)
    }

    private fun saveSecureKeyFallback(safeAlias: String, cleanVal: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences(fallbackPrefsName, Context.MODE_PRIVATE)
            if (cleanVal.isEmpty()) {
                prefs.edit().remove(safeAlias).apply()
                return true
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(cleanVal.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(safeAlias, payload).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getSecureKeyFallback(safeAlias: String): String {
        return try {
            val prefs = context.getSharedPreferences(fallbackPrefsName, Context.MODE_PRIVATE)
            val payload = prefs.getString(safeAlias, null) ?: return ""
            val parts = payload.split(":")
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun hasSecureKey(alias: String): Boolean {
        return getSecureKey(alias).isNotBlank()
    }

    fun release() {
        cancelNativeSubtitles()
        ioScope.cancel()
        mainHandler.removeCallbacks(positionPoller)
        detachWebView()
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
        loudnessEnhancer = null
        exoPlayer.release()
    }
}