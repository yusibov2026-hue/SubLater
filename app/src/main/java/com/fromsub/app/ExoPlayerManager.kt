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
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.concurrent.Executors
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

/**
 * Синхронный аппаратный кодировщик в сжатый AAC (M4A, 16000 Гц, Mono, 64 kbps) без колбэков и без риска Deadlock.
 * Уменьшает размер чанка в 8 раз по сравнению с WAV (~2.3 МБ на 5 минут вместо 19 МБ).
 */
private class SyncM4aChunkWriter(
    val outputFile: File,
    private val sampleRate: Int = 16000,
    private val bitRate: Int = 64000
) {
    private val encoder: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    var totalSamplesWritten: Long = 0L
        private set

    init {
        if (outputFile.exists()) outputFile.delete()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun writePcmShorts(pcmBytes: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            drainOutput(endOfStream = false)
            val inIdx = encoder.dequeueInputBuffer(5000L)
            if (inIdx >= 0) {
                val inBuf = encoder.getInputBuffer(inIdx)
                if (inBuf != null) {
                    inBuf.clear()
                    val toCopy = minOf(length - offset, inBuf.remaining())
                    inBuf.put(pcmBytes, offset, toCopy)
                    val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate.toLong()
                    encoder.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0)
                    totalSamplesWritten += (toCopy / 2).toLong()
                    offset += toCopy
                } else {
                    break
                }
            } else {
                drainOutput(endOfStream = false)
            }
        }
        drainOutput(endOfStream = false)
    }

    private fun drainOutput(endOfStream: Boolean) {
        if (endOfStream) {
            val inIdx = encoder.dequeueInputBuffer(5000L)
            if (inIdx >= 0) {
                val ptsUs = (totalSamplesWritten * 1_000_000L) / sampleRate.toLong()
                encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
        var tries = 0
        val maxTries = if (endOfStream) 40 else 15
        while (tries < maxTries) {
            val outIdx = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 2000L else 0L)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
                tries++
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            } else if (outIdx >= 0) {
                val outBuf = encoder.getOutputBuffer(outIdx)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0 && muxerStarted && outBuf != null) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                }
                encoder.releaseOutputBuffer(outIdx, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    fun close() {
        try { drainOutput(endOfStream = true) } catch (_: Exception) {}
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
    }
}

@OptIn(UnstableApi::class)
class ExoPlayerManager(
    private val context: Context,
    private val activity: MainActivity? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentVideoUri: Uri? = null
    private var currentDisplayName: String = ""
    private var isExternalIntentMedia: Boolean = false
    private var webViewRef: WebView? = null

    @Volatile
    private var cancelAudioExtraction: Boolean = false

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
            mainHandler.postDelayed(this, 30L)
        }
    }

    init {
        ensureCleanUserKeystore()
        mainHandler.post(positionPoller)
    }

    fun attachWebView(webView: WebView) {
        this.webViewRef = webView
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

    /* ==================== ГИБРИДНЫЙ ПОДХОД ИЗВЛЕЧЕНИЯ АУДИО (КАК НА ФОТО 1):
       1. Если звук простой (AAC) — аппаратно через MediaExtractor + MediaMuxer за ~2 секунды сразу в .m4a!
       2. Если звук сложный (DTS / AC3 / E-AC3 / Opus) — декодируем и кодируем сразу в сжатый AAC (.m4a, 16кГц Mono 64kbps), а не в тяжёлый WAV! ==================== */

    @JavascriptInterface
    fun cancelNativeAudioExtraction() {
        cancelAudioExtraction = true
        stopForegroundService()
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

    @JavascriptInterface
    fun readChunkBase64(filePath: String): String {
        return try {
            val f = File(filePath)
            if (!f.exists() || !f.canRead()) return ""
            Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun startNativeAudioExtraction(uriString: String = "") {
        cancelAudioExtraction = false
        startForegroundService("Генерация субтитров: быстрое гибридное извлечение M4A...")
        audioExecutor.execute {
            try {
                val targetUri = if (uriString.isNotBlank() && !uriString.startsWith("blob:")) {
                    Uri.parse(uriString)
                } else {
                    currentVideoUri
                }

                if (targetUri == null) {
                    evalJsOnWebView("window.__onNativeAudioError && window.__onNativeAudioError('Не найден URI видеофайла');")
                    return@execute
                }

                val resultJson = extractHybridAudioToM4a(targetUri) { pct, msg ->
                    updateForegroundNotification(msg)
                    val safeMsg = msg.replace("'", "\\'")
                    evalJsOnWebView("window.__onNativeAudioProgress && window.__onNativeAudioProgress($pct, '$safeMsg');")
                }

                val escapedJson = JSONObject.quote(resultJson)
                evalJsOnWebView("window.__onNativeAudioDone && window.__onNativeAudioDone($escapedJson);")
            } catch (e: Exception) {
                val safeErr = (e.message ?: "Ошибка гибридного извлечения аудио").replace("'", "\\'")
                evalJsOnWebView("window.__onNativeAudioError && window.__onNativeAudioError('$safeErr');")
            }
        }
    }

    private fun extractHybridAudioToM4a(
        uri: Uri,
        onProgress: (Int, String) -> Unit
    ): String {
        val chunkDir = File(context.cacheDir, "sublater_chunks")
        if (!chunkDir.exists()) chunkDir.mkdirs()
        chunkDir.listFiles()?.forEach { it.delete() }

        val extractor = MediaExtractor()
        var pfd: ParcelFileDescriptor? = null
        var tempCopiedFile: File? = null

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

            // Шаг 1 Гибридного подхода: проверяем, есть ли в фильме простая AAC-дорожка (audio/mp4a-latm)
            var aacTrackIndex = -1
            var aacFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    aacTrackIndex = i
                    aacFormat = fmt
                    break
                }
            }

            // ВЕТКА 1 (Простой звук AAC): Аппаратное копирование фреймов напрямую в .m4a за ~2 секунды без декодирования в WAV!
            if (aacTrackIndex >= 0 && aacFormat != null) {
                try {
                    val fastAacResult = remuxSimpleAacIn2Seconds(extractor, aacTrackIndex, aacFormat, chunkDir, onProgress)
                    if (fastAacResult.length() > 0) {
                        return fastAacResult.toString()
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (_: Exception) {
                    // Если прямой ремукс не подошёл для нестандартного контейнера, переходим к Ветке 2
                }
            }

            // ВЕТКА 2 (Сложный звук DTS / AC3 / E-AC3 / Opus / Vorbis): Декодируем и кодируем сразу в сжатый AAC (M4A 64 kbps), а не в WAV!
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
                throw IllegalStateException("В фильме не найдена поддерживаемая аудиодорожка")
            }

            extractor.selectTrack(selectedTrackIndex)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val durationUs = if (selectedFormat.containsKey(MediaFormat.KEY_DURATION)) {
                selectedFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                cachedDurationMs * 1000L
            }

            val targetSampleRate = 16000
            val targetBitRate = 64000 // Сжатый AAC (M4A) 64 kbps (~2.3 МБ на 5 минут вместо 19 МБ WAV!)
            val chunkDurationSec = 300 // 5 минут на кусок
            val overlapSec = 6         // 6 секунд перекрытия
            val maxChunkSamples = (chunkDurationSec * targetSampleRate).toLong()
            val overlapBytesCount = overlapSec * targetSampleRate * 2
            val overlapRing = ByteArray(overlapBytesCount)
            var overlapFilled = 0
            var overlapWritePos = 0

            val decoder = if (selectedDecoderName != null) {
                MediaCodec.createByCodecName(selectedDecoderName)
            } else {
                MediaCodec.createDecoderByType(selectedMime)
            }
            decoder.configure(selectedFormat, null, null, 0)
            decoder.start()

            val chunkArray = JSONArray()
            var chunkIndex = 0
            var currentChunkStartSample = 0L
            var totalWrittenMonoSamples = 0L

            var currentWriter = SyncM4aChunkWriter(
                File(chunkDir, "chunk_0.m4a"),
                targetSampleRate,
                targetBitRate
            )

            val pcmBatchBuf = ByteArray(32768)
            var pcmBatchUsed = 0

            fun flushBatchToM4a() {
                if (pcmBatchUsed > 0) {
                    currentWriter.writePcmShorts(pcmBatchBuf, pcmBatchUsed)
                    pcmBatchUsed = 0
                }
            }

            fun finalizeM4aChunk(isLast: Boolean) {
                flushBatchToM4a()
                currentWriter.close()
                val writtenSamples = currentWriter.totalSamplesWritten
                val outFile = currentWriter.outputFile
                if (writtenSamples > targetSampleRate / 2 && outFile.exists() && outFile.length() > 128) {
                    val startSec = currentChunkStartSample.toDouble() / targetSampleRate.toDouble()
                    val durationSec = writtenSamples.toDouble() / targetSampleRate.toDouble()
                    val endSec = startSec + durationSec
                    val cutoffSec = if (chunkIndex == 0) 0.0 else (startSec + overlapSec / 2.0)
                    val nextCutoffSec = if (isLast) (endSec + 100.0) else (endSec - overlapSec / 2.0)

                    val chunkMetaObj = JSONObject().apply {
                        put("index", chunkIndex)
                        put("path", outFile.absolutePath)
                        put("fileName", outFile.name)
                        put("mimeType", "audio/mp4")
                        put("startSec", startSec)
                        put("endSec", endSec)
                        put("cutoffSec", cutoffSec)
                        put("nextCutoffSec", nextCutoffSec)
                    }
                    chunkArray.put(chunkMetaObj)
                    evalJsOnWebView("window.__onNativeAudioChunk && window.__onNativeAudioChunk(" + JSONObject.quote(chunkMetaObj.toString()) + ");")
                } else {
                    outFile.delete()
                }
            }

            fun rotateNextM4aChunk() {
                finalizeM4aChunk(isLast = false)
                chunkIndex++
                currentWriter = SyncM4aChunkWriter(
                    File(chunkDir, "chunk_${chunkIndex}.m4a"),
                    targetSampleRate,
                    targetBitRate
                )
                val overlapSamples = (overlapFilled / 2).toLong()
                currentChunkStartSample = (totalWrittenMonoSamples - overlapSamples).coerceAtLeast(0L)
                if (overlapFilled > 0) {
                    if (overlapFilled < overlapBytesCount) {
                        currentWriter.writePcmShorts(overlapRing, overlapFilled)
                    } else {
                        val tailLen = overlapBytesCount - overlapWritePos
                        val orderedOverlap = ByteArray(overlapBytesCount)
                        System.arraycopy(overlapRing, overlapWritePos, orderedOverlap, 0, tailLen)
                        if (overlapWritePos > 0) {
                            System.arraycopy(overlapRing, 0, orderedOverlap, tailLen, overlapWritePos)
                        }
                        currentWriter.writePcmShorts(orderedOverlap, overlapBytesCount)
                    }
                }
            }

            fun pushMonoSampleToM4a(sample: Short) {
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

                if (currentWriter.totalSamplesWritten + (pcmBatchUsed / 2) >= maxChunkSamples) {
                    rotateNextM4aChunk()
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

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastPct = -1

            try {
                while (!outputDone) {
                    if (cancelAudioExtraction) throw InterruptedException("Отменено пользователем.")

                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10000L)
                        if (inIndex >= 0) {
                            val inBuf = decoder.getInputBuffer(inIndex)
                            if (inBuf != null) {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                                    decoder.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, 0)
                                    extractor.advance()

                                    if (durationUs > 0L) {
                                        val pct = ((ptsUs.toDouble() / durationUs.toDouble()) * 100.0).toInt().coerceIn(0, 99)
                                        if (pct >= lastPct + 5) {
                                            lastPct = pct
                                            onProgress(pct, "⚡ Сжатие сложного звука в AAC (M4A): $pct% (часть ${chunkIndex + 1})...")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    var outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000L)
                    while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFmt = decoder.outputFormat
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
                        } else if (outIndex >= 0) {
                            val outBuf = decoder.getOutputBuffer(outIndex)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)

                                if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val floatBuf = outBuf.asFloatBuffer()
                                    val totalFrames = floatBuf.remaining() / srcChannels
                                    while (resamplePos < totalFrames) {
                                        val baseIdx = resamplePos.toInt() * srcChannels
                                        var sum = 0f
                                        for (ch in 0 until srcChannels) sum += floatBuf.get(baseIdx + ch)
                                        val monoShort = ((sum / srcChannels).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                        pushMonoSampleToM4a(monoShort)
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
                                        pushMonoSampleToM4a(monoShort)
                                        resamplePos += resampleStep
                                    }
                                    resamplePos = (resamplePos - totalFrames.toDouble()).coerceAtLeast(0.0)
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                                break
                            }
                        }
                        outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
                    }
                }
                finalizeM4aChunk(isLast = true)
            } finally {
                try { currentWriter.close() } catch (_: Exception) {}
                try { decoder.stop() } catch (_: Exception) {}
                try { decoder.release() } catch (_: Exception) {}
            }

            return chunkArray.toString()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            try { tempCopiedFile?.delete() } catch (_: Exception) {}
        }
    }

    /**
     * ВЕТКА 1 Гибридного подхода: если звук в фильме уже AAC (audio/mp4a-latm),
     * аппаратно копируем фреймы через MediaExtractor + MediaMuxer сразу в .m4a за ~2 секунды!
     */
    private fun remuxSimpleAacIn2Seconds(
        extractor: MediaExtractor,
        trackIndex: Int,
        aacFormat: MediaFormat,
        chunkDir: File,
        onProgress: (Int, String) -> Unit
    ): JSONArray {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val durationUs = if (aacFormat.containsKey(MediaFormat.KEY_DURATION)) {
            aacFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
        } else {
            cachedDurationMs * 1000L
        }

        val chunkDurationSec = 480.0 // 8 минут на чанк (~5-7 МБ в M4A)
        val overlapSec = 6.0         // 6 секунд перекрытия
        val chunkDurationUs = (chunkDurationSec * 1_000_000.0).toLong()
        val overlapUs = (overlapSec * 1_000_000.0).toLong()
        val advanceUs = chunkDurationUs - overlapUs
        val maxChunkBytes = 16 * 1024 * 1024

        val directBuf = ByteBuffer.allocateDirect(262144).order(ByteOrder.LITTLE_ENDIAN)
        val bufferInfo = MediaCodec.BufferInfo()
        val resultArray = JSONArray()

        var chunkIndex = 0
        var targetStartUs = 0L

        while (true) {
            if (cancelAudioExtraction) throw InterruptedException("Отменено пользователем.")
            if (durationUs > 0L && targetStartUs >= durationUs - 200_000L) break

            extractor.seekTo(targetStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val outFile = File(chunkDir, "chunk_${chunkIndex}.m4a")
            if (outFile.exists()) outFile.delete()

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrack = muxer.addTrack(aacFormat)
            muxer.start()

            var actualFirstPtsUs = -1L
            var lastPtsUs = targetStartUs
            var bytesWritten = 0
            var reachedEof = false

            try {
                while (true) {
                    if (cancelAudioExtraction) throw InterruptedException("Отменено пользователем.")
                    directBuf.clear()
                    val sampleSize = extractor.readSampleData(directBuf, 0)
                    if (sampleSize < 0) {
                        reachedEof = true
                        break
                    }

                    val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                    if (actualFirstPtsUs < 0L) actualFirstPtsUs = ptsUs
                    lastPtsUs = ptsUs

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = (ptsUs - actualFirstPtsUs).coerceAtLeast(0L)
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(muxTrack, directBuf, bufferInfo)
                    bytesWritten += sampleSize
                    extractor.advance()

                    val elapsedInChunkUs = ptsUs - actualFirstPtsUs
                    if (elapsedInChunkUs >= chunkDurationUs || bytesWritten >= maxChunkBytes) {
                        break
                    }
                }
            } finally {
                try { muxer.stop() } catch (_: Exception) {}
                try { muxer.release() } catch (_: Exception) {}
            }

            if (bytesWritten > 256 && outFile.exists() && actualFirstPtsUs >= 0L) {
                val startSec = actualFirstPtsUs.toDouble() / 1_000_000.0
                val endSec = maxOf(startSec + 0.5, lastPtsUs.toDouble() / 1_000_000.0)
                val isLast = reachedEof || (durationUs > 0L && lastPtsUs >= durationUs - 500_000L)
                val cutoffSec = if (chunkIndex == 0) 0.0 else (startSec + overlapSec / 2.0)
                val nextCutoffSec = if (isLast) (endSec + 100.0) else (endSec - overlapSec / 2.0)

                resultArray.put(JSONObject().apply {
                    put("index", chunkIndex)
                    put("path", outFile.absolutePath)
                    put("fileName", outFile.name)
                    put("mimeType", "audio/mp4")
                    put("startSec", startSec)
                    put("endSec", endSec)
                    put("cutoffSec", cutoffSec)
                    put("nextCutoffSec", nextCutoffSec)
                })

                if (durationUs > 0L) {
                    val pct = ((lastPtsUs.toDouble() / durationUs.toDouble()) * 100.0).toInt().coerceIn(0, 99)
                    onProgress(pct, "⚡ Аппаратное извлечение AAC (M4A): $pct% (часть ${chunkIndex + 1})...")
                }

                if (isLast) break
                targetStartUs = (actualFirstPtsUs + advanceUs).coerceAtLeast(targetStartUs + 1_000_000L)
                chunkIndex++
            } else {
                outFile.delete()
                break
            }
        }

        return resultArray
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
        cancelAudioExtraction = true
        stopForegroundService()
        mainHandler.removeCallbacks(positionPoller)
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
        exoPlayer.release()
    }
}
