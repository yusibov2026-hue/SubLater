package com.fromsub.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.ceil

data class VideoBounds(
    val leftRatio: Float = 0f,
    val topRatio: Float = 0f,
    val widthRatio: Float = 1f,
    val heightRatio: Float = 0f,
    val isFullscreen: Boolean = false,
    val isVisible: Boolean = false
)

private data class EmbeddedSubtitleSegment(
    val start: Double,
    var end: Double,
    val text: String
)

@OptIn(UnstableApi::class)
class ExoPlayerManager(
    private val context: Context,
    private val activity: MainActivity? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var volumePercent: Int = 100
    private var boostPercent: Int = 100
    private var currentVideoUri: Uri? = null
    private var currentMimeHint: String? = null
    private var webViewRef: WebView? = null

    @Volatile
    private var cancelAudioExtraction: Boolean = false

    @Volatile
    private var selectedAudioOrderIndex: Int = 0

    private var capturePlayer: ExoPlayer? = null
    @Volatile
    private var captureSink: StreamAudioCaptureSink? = null

    val isVideoReady = mutableStateOf(false)
    val resizeModeState = mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    val videoBoundsState = mutableStateOf(VideoBounds())
    val hostSurfaceColorState = mutableIntStateOf(0xFF161922.toInt())

    @Volatile
    private var cachedPositionMs: Long = 0L

    @Volatile
    private var cachedDurationMs: Long = 0L

    @Volatile
    private var cachedIsPlaying: Boolean = false

    @Volatile
    private var cachedVideoWidth: Int = 0

    @Volatile
    private var cachedVideoHeight: Int = 0

    init {
        SubtitleForegroundService.onCancelRequested = {
            cancelNativeAudioExtraction()
            postJs("if (window.__cancelFromNotification) window.__cancelFromNotification();")
        }
    }

    val exoPlayer: ExoPlayer by lazy {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        cachedIsPlaying = isPlaying
                        syncStateOnMainThread()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            isVideoReady.value = true
                        }
                        syncStateOnMainThread()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            val par = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                            var effW = (videoSize.width * par).toInt().coerceAtLeast(1)
                            var effH = videoSize.height
                            if (videoSize.unappliedRotationDegrees % 180 != 0) {
                                val tmp = effW
                                effW = effH
                                effH = tmp
                            }
                            cachedVideoWidth = effW
                            cachedVideoHeight = effH
                            postJs("if (window.__onNativeVideoSizeChanged) window.__onNativeVideoSizeChanged($effW, $effH);")
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        postJs("if (window.__onNativeAudioTracksUpdated) { window.__onNativeAudioTracksUpdated(); }")
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

    private fun syncStateOnMainThread() {
        try {
            cachedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration
            cachedDurationMs = if (dur != C.TIME_UNSET && dur > 0L) dur else 0L
            cachedIsPlaying = exoPlayer.isPlaying
        } catch (_: Exception) {
        }
    }

    fun storeMediaUri(uri: Uri, mimeHint: String? = null) {
        currentVideoUri = uri
        currentMimeHint = mimeHint
        selectedAudioOrderIndex = 0
        cachedVideoWidth = 0
        cachedVideoHeight = 0
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
    fun startForegroundService(statusText: String = "Generating subtitles...") {
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
    fun updateForegroundProgress(statusText: String, progress: Int) {
        try {
            val intent = Intent(context, SubtitleForegroundService::class.java).apply {
                action = SubtitleForegroundService.ACTION_START_OR_UPDATE
                putExtra(SubtitleForegroundService.EXTRA_STATUS_TEXT, statusText)
                putExtra(SubtitleForegroundService.EXTRA_PROGRESS, progress.coerceIn(-1, 100))
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
        }
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
        cachedVideoWidth = 0
        cachedVideoHeight = 0
        // Подсказка типа по расширению: у сетевых ссылок без имени файла ExoPlayer иначе гадает
        val mimeHint = currentMimeHint
        if (mimeHint.isNullOrBlank() && (uri.scheme == "http" || uri.scheme == "https")) {
            currentMimeHint = mimeFromUri(uri)
        }
        mainHandler.post {
            try {
                val mime = currentMimeHint
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .apply {
                        if (!mime.isNullOrBlank() && mime != "video/*" && mime != "*/*") {
                            setMimeType(mime)
                        }
                    }
                    .build()
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                isVideoReady.value = true
                syncStateOnMainThread()
            } catch (e: Exception) {
                Toast.makeText(context, "Video load error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun mimeFromUri(uri: Uri): String {
        val path = uri.lastPathSegment ?: uri.toString()
        return when (path.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "ts", "m2ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            else -> ""
        }
    }

    @JavascriptInterface
    fun setImmersiveFullscreen(enabled: Boolean) {
        activity?.applyImmersiveMode(enabled)
    }

    @JavascriptInterface
    fun setStatusBarColor(colorHex: String) {
        val color = try {
            android.graphics.Color.parseColor(colorHex)
        } catch (_: Exception) {
            return
        }
        activity?.applyStatusBarColor(color)
    }

    @JavascriptInterface
    fun setNativeSurfaceColor(colorHex: String) {
        val color = try {
            android.graphics.Color.parseColor(colorHex)
        } catch (_: Exception) {
            return
        }
        hostSurfaceColorState.intValue = color
        activity?.applyStatusBarColor(color)
    }

    @JavascriptInterface
    fun isSystemDarkMode(): Boolean {
        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightFlags == Configuration.UI_MODE_NIGHT_YES
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
                exoPlayer.prepare()
            } else if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0L)
            }
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
                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0L)
                }
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
    fun setVolume(volumePercentValue: Int) {
        volumePercent = volumePercentValue.coerceIn(0, 200)
        applyAudioGain()
    }

    @JavascriptInterface
    fun setBoost(boostPercentValue: Int) {
        boostPercent = boostPercentValue.coerceIn(100, 200)
        applyAudioGain()
    }

    private fun applyAudioGain() {
        val volume = volumePercent
        val boost = boostPercent
        mainHandler.post {
            val effective = (volume * boost / 100).coerceAtMost(400)
            if (effective <= 100) {
                exoPlayer.volume = effective / 100f
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
                        // Компрессор + лимитер внутри LoudnessEnhancer: громче без клиппинга
                        val gainMb = (2000.0 * Math.log10(effective / 100.0)).toInt().coerceAtMost(2000)
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
    fun getAudioTracksJson(): String {
        val result = JSONArray()
        return try {
            val uri = currentVideoUri ?: return "[]"
            if (uri.scheme == "http" || uri.scheme == "https") {
                val latch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    try {
                        var orderIdx = 0
                        for (group in exoPlayer.currentTracks.groups) {
                            if (group.type == C.TRACK_TYPE_AUDIO) {
                                for (t in 0 until group.length) {
                                    val fmt = group.getTrackFormat(t)
                                    val lang = fmt.language?.takeIf { it.isNotBlank() && it != "und" } ?: ""
                                    val label = fmt.label?.takeIf { it.isNotBlank() } ?: ""
                                    val ch = if (fmt.channelCount > 0) "${fmt.channelCount}ch" else ""
                                    val title = buildString {
                                        append("Track ${orderIdx + 1}")
                                        if (label.isNotEmpty()) append(" • $label")
                                        if (lang.isNotEmpty()) append(" (${lang.uppercase()})")
                                        if (ch.isNotEmpty()) append(" [$ch]")
                                    }
                                    result.put(JSONObject().apply {
                                        put("index", orderIdx)
                                        put("title", title)
                                        put("lang", lang)
                                        put("selected", group.isTrackSelected(t) || orderIdx == selectedAudioOrderIndex)
                                    })
                                    orderIdx++
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                return result.toString()
            }

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                var orderIdx = 0
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        val lang = try { format.getString(MediaFormat.KEY_LANGUAGE) ?: "" } catch (_: Exception) { "" }
                        val cleanLang = if (lang.isNotBlank() && lang != "und") lang.uppercase() else ""
                        val channels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 0 }
                        val codecShort = mime.substringAfter("audio/").uppercase()
                        val title = buildString {
                            append("Track ${orderIdx + 1}")
                            if (cleanLang.isNotEmpty()) append(" ($cleanLang)")
                            append(" • $codecShort")
                            if (channels > 0) append(" ${channels}ch")
                        }
                        result.put(JSONObject().apply {
                            put("index", orderIdx)
                            put("extractorTrack", i)
                            put("title", title)
                            put("lang", cleanLang)
                            put("selected", orderIdx == selectedAudioOrderIndex)
                        })
                        orderIdx++
                    }
                }
            } finally {
                try { extractor.release() } catch (_: Exception) {}
            }
            result.toString()
        } catch (_: Exception) {
            "[]"
        }
    }

    @JavascriptInterface
    fun selectAudioTrack(orderIndex: Int): Boolean {
        val safeIdx = orderIndex.coerceAtLeast(0)
        selectedAudioOrderIndex = safeIdx
        mainHandler.post {
            try {
                var currentAudioIdx = 0
                for (group in exoPlayer.currentTracks.groups) {
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        for (t in 0 until group.length) {
                            if (currentAudioIdx == safeIdx) {
                                val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(t))
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(override)
                                    .build()
                                return@post
                            }
                            currentAudioIdx++
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return true
    }

    @JavascriptInterface
    fun extractEmbeddedSubtitleSegmentsJson(): String {
        val uri = currentVideoUri ?: return "[]"
        if (uri.scheme == "http" || uri.scheme == "https") return "[]"

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var subTrackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase() ?: continue
                if (
                    mime.startsWith("text/") ||
                    mime.contains("subrip") ||
                    mime.contains("ssa") ||
                    mime.contains("ass") ||
                    mime.contains("vtt")
                ) {
                    subTrackIdx = i
                    break
                }
            }
            if (subTrackIdx < 0) return "[]"

            extractor.selectTrack(subTrackIdx)
            val buf = ByteBuffer.allocate(65536)
            val segments = mutableListOf<EmbeddedSubtitleSegment>()

            var guard = 0
            while (guard < 5000) {
                guard++
                buf.clear()
                val sampleSize = extractor.readSampleData(buf, 0)
                if (sampleSize < 0) break
                if (sampleSize > 0) {
                    val startSec = extractor.sampleTime.coerceAtLeast(0L).toDouble() / 1_000_000.0
                    val bytes = ByteArray(sampleSize)
                    buf.position(0)
                    buf.get(bytes, 0, sampleSize)
                    val cleaned = cleanEmbeddedSubtitleText(String(bytes, Charsets.UTF_8))
                    if (cleaned.isNotEmpty()) {
                        if (segments.isNotEmpty()) {
                            val prev = segments[segments.lastIndex]
                            val trimmedEnd = (startSec - 0.05).coerceAtLeast(prev.start + 0.4)
                            if (trimmedEnd < prev.end) {
                                prev.end = trimmedEnd
                            }
                        }
                        segments.add(EmbeddedSubtitleSegment(startSec, startSec + 3.0, cleaned))
                    }
                }
                if (!extractor.advance()) break
            }

            if (segments.size < 5) return "[]"
            val arr = JSONArray()
            for (s in segments) {
                arr.put(JSONObject().apply {
                    put("start", s.start)
                    put("end", s.end)
                    put("text", s.text)
                })
            }
            arr.toString()
        } catch (_: Exception) {
            "[]"
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun cleanEmbeddedSubtitleText(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        if (text.startsWith("Dialogue:", ignoreCase = true)) {
            val parts = text.split(",", limit = 10)
            if (parts.size == 10) text = parts[9]
        } else {
            val mkvAssMatch = EMBEDDED_MKV_ASS_REGEX.matchEntire(text)
            if (mkvAssMatch != null) {
                text = mkvAssMatch.groupValues[1]
            }
        }

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val hasSrtTiming = lines.any { EMBEDDED_SRT_TIME_REGEX.matches(it.trim()) }
        val filtered = if (hasSrtTiming) {
            lines.filter { line ->
                val t = line.trim()
                t.isNotEmpty() && !EMBEDDED_SRT_TIME_REGEX.matches(t) && !EMBEDDED_INT_LINE_REGEX.matches(t)
            }
        } else {
            lines
        }

        text = filtered.joinToString(" ")
            .replace("\\N", " ")
            .replace("\\n", " ")
            .replace(EMBEDDED_ASS_TAG_REGEX, "")
            .replace(EMBEDDED_HTML_TAG_REGEX, "")
            .replace(EMBEDDED_MULTI_SPACE_REGEX, " ")
            .trim()
        return text
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
        return try {
            retriever.setDataSource(context, uri)
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
            try {
                retriever.release()
            } catch (_: Exception) {
            }
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
                val chooser = Intent.createChooser(viewIntent, "Open with...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open menu: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    "Saved to Downloads: $safeName",
                    Toast.LENGTH_LONG
                ).show()
            }
            true
        } catch (e: Exception) {
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Save error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            false
        }
    }

    @JavascriptInterface
    fun shareSubtitleFile(fileName: String, content: String, format: String): Boolean {
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
            val cacheFolder = File(context.cacheDir, "shared_subs")
            if (!cacheFolder.exists()) cacheFolder.mkdirs()
            val shareFile = File(cacheFolder, safeName)
            FileOutputStream(shareFile).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )
            } catch (_: Exception) {
                null
            }

            mainHandler.post {
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        if (uri != null) {
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        putExtra(Intent.EXTRA_TEXT, content)
                        putExtra(Intent.EXTRA_SUBJECT, safeName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(shareIntent, "Share subtitles").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not open share menu: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            true
        } catch (e: Exception) {
            mainHandler.post {
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, content)
                        putExtra(Intent.EXTRA_SUBJECT, safeName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(shareIntent, "Share subtitles").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                } catch (err: Exception) {
                    Toast.makeText(context, "Share error: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
            false
        }
    }

    /* ==================== АСИНХРОННОЕ НАТИВНОЕ ИЗВЛЕЧЕНИЕ 16кГц MONO WAV ПО 60 СЕКУНД ==================== */

    @JavascriptInterface
    fun cancelNativeAudioExtraction() {
        cancelAudioExtraction = true
        captureSink?.abort()
        mainHandler.post { releaseCapturePlayer() }
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

    /* ==================== МОСТ ПОТОКОВОЙ ОТДАЧИ ЧАНКОВ БЕЗ BASE64 ====================
     * JS запрашивает https://<CHUNK_HOST>/chunk?path=<abs>, WebViewClient перехватывает
     * запрос и отдаёт WAV потоком прямо из файла: без Base64 (+33% объёма),
     * без копии строки в JNI и без посимвольной сборки типизированного массива в JS.
     */

    /** true, если URL надо отдать из кэша чанков. Вызывается из WebViewClient. */
    fun isChunkStreamUrl(url: String?): Boolean {
        return url != null && url.startsWith("$CHUNK_STREAM_SCHEME$CHUNK_HOST/")
    }

    /** Собирает потоковый ответ на перехваченный запрос чанка. */
    fun buildChunkStreamResponse(url: String): WebResourceResponse? {
        return try {
            val uri = Uri.parse(url)
            val encodedPath = uri.getQueryParameter("path") ?: return null
            val file = File(encodedPath)
            if (!file.exists() || !file.canRead()) return null
            // Отдаём только из нашей папки чанков — никакого чтения произвольных файлов.
            if (file.parentFile?.name != "sublater_chunks") return null

            val headers = HashMap<String, String>()
            headers["Access-Control-Allow-Origin"] = "*"
            headers["Cache-Control"] = "no-store"
            headers["Content-Type"] = "audio/wav"
            headers["Content-Length"] = file.length().toString()

            WebResourceResponse(
                "audio/wav",
                null,
                file.length().toInt().coerceAtLeast(0),
                "OK",
                headers,
                FileInputStream(file)
            )
        } catch (_: Exception) {
            null
        }
    }

    @JavascriptInterface
    fun deleteChunkFile(filePath: String): Boolean {
        return try {
            val f = File(filePath)
            if (f.exists() && f.parentFile?.name == "sublater_chunks") {
                f.delete()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun writeWavHeader(raf: RandomAccessFile, pcmDataBytes: Int, sampleRate: Int = 16000, channels: Int = 1) {
        val byteRate = sampleRate * channels * 2
        val totalDataLen = pcmDataBytes + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * 2).toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmDataBytes)
        raf.seek(0L)
        raf.write(header.array())
    }

    private fun postJs(jsCode: String) {
        mainHandler.post {
            try {
                webViewRef?.evaluateJavascript(jsCode, null)
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun startNativeAudioExtraction(): Boolean = startNativeAudioExtractionForUri("", 0.0, false)

    @JavascriptInterface
    fun startNativeAudioExtractionFrom(startSec: Double): Boolean = startNativeAudioExtractionForUri("", startSec, false)

    @JavascriptInterface
    fun startNativeAudioExtractionMode(startSec: Double, batchMode: Boolean): Boolean =
        startNativeAudioExtractionForUri("", startSec, batchMode)

    fun startNativeAudioExtractionForUri(uriString: String, startSec: Double = 0.0, batchMode: Boolean = false): Boolean {
        cancelAudioExtraction = false
        val targetUri = if (uriString.isNotBlank() && !uriString.startsWith("blob:")) {
            Uri.parse(uriString)
        } else {
            currentVideoUri ?: return false
        }

        // Максимально быстрое извлечение аудио: динамические чанки (16 кГц во всех режимах) + до 4 параллельных потоков декодирования
        val targetSampleRate = 16000
        val streamChunkDurationSec = if (batchMode) 180 else 60
        val streamOverlapSec = if (batchMode) 4 else 1

        // Сетевой поток (HLS/DASH/прогрессивный HTTP) нельзя открыть через
        // MediaExtractor как файл. Его разбирает отдельный ExoPlayer, а
        // StreamAudioCaptureSink получает уже декодированный PCM.
        if (targetUri.scheme == "http" || targetUri.scheme == "https") {
            return startStreamAudioCapture(targetUri, startSec, targetSampleRate, streamChunkDurationSec, streamOverlapSec)
        }

        Thread({
            try {
                extractParallelOrSequentialWavChunksAsync(targetUri, startSec, targetSampleRate, batchMode)
            } catch (e: Exception) {
                val errorMsg = JSONObject.quote(e.message ?: "Audio extraction error")
                postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($errorMsg); }")
            }
        }, "SubLater-AudioExtractor").start()

        return true
    }

    private fun startStreamAudioCapture(
        uri: Uri,
        startSec: Double = 0.0,
        targetSampleRate: Int = 16000,
        chunkDurationSec: Int = 60,
        overlapSec: Int = 1
    ): Boolean {
        cancelAudioExtraction = false
        captureSink?.abort()
        val previous = capturePlayer
        capturePlayer = null
        val safeStartSec = if (startSec >= 30.0) startSec else 0.0

        mainHandler.post {
            try { previous?.release() } catch (_: Exception) {}
            try {
                val sink = StreamAudioCaptureSink(
                    context,
                    onChunk = { chunkObj, isLast ->
                        val json = chunkObj.toString()
                        postJs("if (window.__onNativeChunkReady) { window.__onNativeChunkReady($json, $isLast); }")
                    },
                    onDone = { count ->
                        postJs("if (window.__onNativeChunkDone) { window.__onNativeChunkDone($count); }")
                        mainHandler.post { releaseCapturePlayer() }
                    },
                    onError = { message ->
                        val quoted = JSONObject.quote(message)
                        postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($quoted); }")
                        mainHandler.post { releaseCapturePlayer() }
                    },
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

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val quoted = JSONObject.quote(error.message ?: "Stream read error")
                        postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($quoted); }")
                        mainHandler.post { releaseCapturePlayer() }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        applyAudioTrackOverride(player, selectedAudioOrderIndex)
                    }
                })

                val mime = currentMimeHint
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .apply {
                        if (!mime.isNullOrBlank() && mime != "video/*" && mime != "*/*") {
                            setMimeType(mime)
                        }
                    }
                    .build()
                player.setMediaItem(mediaItem)
                // Второй плеер нужен только для декодирования в чанки: он не должен
                // забирать аудиофокус у основного плеера и глушить звук видео.
                player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
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
                val quoted = JSONObject.quote(e.message ?: "Failed to open stream")
                postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($quoted); }")
                releaseCapturePlayer()
            }
        }
        return true
    }

    private fun releaseCapturePlayer() {
        val player = capturePlayer ?: return
        capturePlayer = null
        captureSink = null
        try { player.release() } catch (_: Exception) {}
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
        return try {
            extractor.setDataSource(context, uri, null)
            val candidates = mutableListOf<MediaFormat>()
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) candidates.add(fmt)
            }
            val chosen = if (candidates.isNotEmpty()) {
                candidates.getOrElse(selectedAudioOrderIndex.coerceIn(0, candidates.lastIndex)) { candidates.first() }
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
        uri: Uri,
        startSec: Double,
        targetSampleRate: Int,
        batchMode: Boolean
    ) {
        val safeStartSec = if (startSec >= 30.0) startSec else 0.0
        val totalDurSec = probeAudioDurationSec(uri)
        val remainingSec = (totalDurSec - safeStartSec).coerceAtLeast(0.0)
        val fallbackChunkDur = if (batchMode) 180 else 60
        val overlapSec = if (batchMode) 2 else 1

        if (totalDurSec <= 0.0 || remainingSec <= (if (batchMode) 50.0 else 70.0)) {
            extractFixedWavChunksAsync(uri, startSec, targetSampleRate, fallbackChunkDur, overlapSec)
            return
        }

        val chunkDurationSec = if (batchMode) {
            if (remainingSec <= 720.0) {
                ceil(remainingSec / 4.0).toInt().coerceIn(45, 180)
            } else {
                180
            }
        } else {
            60
        }

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
            extractFixedWavChunksAsync(uri, startSec, targetSampleRate, fallbackChunkDur, overlapSec)
            return
        }

        val chunkDir = File(context.cacheDir, "sublater_chunks")
        if (!chunkDir.exists()) chunkDir.mkdirs()
        chunkDir.listFiles()?.forEach { it.delete() }

        val threadCount = windows.size.coerceIn(1, 4)
        val executor = Executors.newFixedThreadPool(threadCount)
        val emittedCount = AtomicInteger(0)
        val fatalError = AtomicReference<String?>(null)

        try {
            windows.forEachIndexed { idx, (winIdx, winNomStart, winNomEnd) ->
                val isLastWin = idx == windows.lastIndex
                executor.execute {
                    if (cancelAudioExtraction) return@execute
                    try {
                        val chunkJson = decodeSingleWindowToWav(
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
                        if (chunkJson != null && !cancelAudioExtraction) {
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
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                if (cancelAudioExtraction) {
                    executor.shutdownNow()
                    break
                }
            }
        } finally {
            if (!executor.isTerminated) executor.shutdownNow()
        }

        if (cancelAudioExtraction) return

        val totalEmitted = emittedCount.get()
        if (totalEmitted == 0) {
            extractFixedWavChunksAsync(uri, startSec, targetSampleRate, fallbackChunkDur, overlapSec)
            return
        }
        postJs("if (window.__onNativeChunkDone) { window.__onNativeChunkDone($totalEmitted); }")
    }

    private fun decodeSingleWindowToWav(
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
        val outFile = File(chunkDir, "chunk_${winIndex}.wav")
        var outStream: BufferedOutputStream? = null

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

            val chosen = audioCandidates.getOrElse(selectedAudioOrderIndex.coerceIn(0, audioCandidates.lastIndex)) {
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

            val localCodec = MediaCodec.createDecoderByType(mimeType)
            codec = localCodec
            localCodec.configure(audioFormat, null, null, 0)
            localCodec.start()

            val stream = BufferedOutputStream(FileOutputStream(outFile), 131072)
            outStream = stream
            stream.write(ByteArray(44))

            val ioBuf = ByteArray(65536)
            var ioUsed = 0
            var pcmBytes = 0
            var writtenSamples = 0L
            var droppedPreSamples = 0L
            var actualChunkStartSec = seekLandedSec
            var reachedWindowEnd = false

            fun flushIo() {
                if (ioUsed > 0) {
                    stream.write(ioBuf, 0, ioUsed)
                    pcmBytes += ioUsed
                    ioUsed = 0
                }
            }

            fun pushWindowSample(sample: Short) {
                if (reachedWindowEnd) return
                val sampleTimeSec = seekLandedSec + (droppedPreSamples + writtenSamples).toDouble() / targetSampleRate.toDouble()
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
                val lo = (sample.toInt() and 0xFF).toByte()
                val hi = ((sample.toInt() ushr 8) and 0xFF).toByte()
                ioBuf[ioUsed++] = lo
                ioBuf[ioUsed++] = hi
                if (ioUsed >= ioBuf.size - 2) {
                    flushIo()
                }
                writtenSamples++
            }

            var srcSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else 44100
            var srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 2
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resamplePos = 0.0
            var resampleStep = srcSampleRate.toDouble() / targetSampleRate.toDouble()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && !reachedWindowEnd && !cancelAudioExtraction) {
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
                            srcChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        resampleStep = srcSampleRate.toDouble() / targetSampleRate.toDouble()
                    } else if (outIndex >= 0) {
                        val outBuf = localCodec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0 && !reachedWindowEnd) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)

                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val floatBuf = outBuf.asFloatBuffer()
                                val totalFrames = floatBuf.remaining() / srcChannels
                                while (resamplePos < totalFrames && !reachedWindowEnd) {
                                    val baseIdx = resamplePos.toInt() * srcChannels
                                    var sum = 0f
                                    for (ch in 0 until srcChannels) sum += floatBuf.get(baseIdx + ch)
                                    val monoShort = ((sum / srcChannels).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                    pushWindowSample(monoShort)
                                    resamplePos += resampleStep
                                }
                                resamplePos = (resamplePos - totalFrames.toDouble()).coerceAtLeast(0.0)
                            } else {
                                val shortBuf = outBuf.asShortBuffer()
                                val totalFrames = shortBuf.remaining() / srcChannels
                                while (resamplePos < totalFrames && !reachedWindowEnd) {
                                    val baseIdx = resamplePos.toInt() * srcChannels
                                    var sum = 0
                                    for (ch in 0 until srcChannels) sum += shortBuf.get(baseIdx + ch).toInt()
                                    val monoShort = (sum / srcChannels).coerceIn(-32768, 32767).toShort()
                                    pushWindowSample(monoShort)
                                    resamplePos += resampleStep
                                }
                                resamplePos = (resamplePos - totalFrames.toDouble()).coerceAtLeast(0.0)
                            }
                        }
                        localCodec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            break
                        }
                    }
                    if (reachedWindowEnd || cancelAudioExtraction) break
                    outIndex = localCodec.dequeueOutputBuffer(bufferInfo, 0L)
                }
            }

            flushIo()
            stream.flush()
            stream.close()
            outStream = null

            if (cancelAudioExtraction || pcmBytes <= 0) {
                try { outFile.delete() } catch (_: Exception) {}
                return null
            }

            RandomAccessFile(outFile, "rw").use { raf ->
                writeWavHeader(raf, pcmBytes, targetSampleRate, 1)
            }
            val durationSec = (pcmBytes / 2).toDouble() / targetSampleRate.toDouble()
            val endSec = actualChunkStartSec + durationSec
            val cutoffSec = if (winIndex == 0) actualChunkStartSec else winNomStartSec
            val nextCutoffSec = if (isLastWindow) (endSec + 100.0) else winNomEndSec

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
                put("isLast", false)
            }
        } catch (e: Throwable) {
            try { outFile.delete() } catch (_: Exception) {}
            throw e
        } finally {
            try { outStream?.close() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun extractFixedWavChunksAsync(
        uri: Uri,
        startSec: Double = 0.0,
        targetSampleRate: Int = 16000,
        chunkDurationSec: Int = 60,
        overlapSec: Int = 1
    ) {
        var count = 0
        extractWavChunksInternal(uri, startSec, targetSampleRate, chunkDurationSec, overlapSec) { chunkJson, isLast ->
            count++
            val jsonStr = chunkJson.toString()
            postJs("if (window.__onNativeChunkReady) { window.__onNativeChunkReady($jsonStr, $isLast); }")
        }
        if (!cancelAudioExtraction) {
            postJs("if (window.__onNativeChunkDone) { window.__onNativeChunkDone($count); }")
        }
    }

    private fun extractWavChunksInternal(
        uri: Uri,
        requestedStartSec: Double = 0.0,
        targetSampleRate: Int = 16000,
        chunkDurationSec: Int = 60,
        overlapSec: Int = 1,
        onChunkReady: (JSONObject, Boolean) -> Unit
    ) {
        val chunkDir = File(context.cacheDir, "sublater_chunks")
        if (!chunkDir.exists()) chunkDir.mkdirs()
        chunkDir.listFiles()?.forEach { it.delete() }

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

        if (audioCandidates.isNotEmpty()) {
            val chosen = audioCandidates.getOrElse(selectedAudioOrderIndex.coerceIn(0, audioCandidates.lastIndex)) {
                audioCandidates.first()
            }
            audioTrackIndex = chosen.first
            audioFormat = chosen.second
            mimeType = chosen.third
        }

        if (audioTrackIndex < 0 || audioFormat == null) {
            extractor.release()
            val err = JSONObject.quote("No audio track found in file")
            postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($err); }")
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

        val codec = MediaCodec.createDecoderByType(mimeType)
        codec.configure(audioFormat, null, null, 0)
        codec.start()

        var chunkIndex = 0
        var currentChunkStartSample = 0L
        var totalWrittenMonoSamples = 0L

        var currentFile = File(chunkDir, "chunk_0.wav")
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

        fun finalizeChunk(isLast: Boolean) {
            flushIoBuf()
            currentOut.flush()
            currentOut.close()
            if (currentChunkPcmBytes > 0) {
                RandomAccessFile(currentFile, "rw").use { raf ->
                    writeWavHeader(raf, currentChunkPcmBytes, targetSampleRate, 1)
                }
                val startSec = baseStartSec + currentChunkStartSample.toDouble() / targetSampleRate.toDouble()
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
                    put("isLast", isLast)
                }
                onChunkReady(chunkObj, isLast)
            } else {
                currentFile.delete()
                if (isLast && chunkIndex == 0) {
                    val err = JSONObject.quote("Failed to decode audio data")
                    postJs("if (window.__onNativeChunkError) { window.__onNativeChunkError($err); }")
                }
            }
        }

        fun rotateNextChunk() {
            finalizeChunk(isLast = false)
            chunkIndex++
            currentFile = File(chunkDir, "chunk_${chunkIndex}.wav")
            currentOut = BufferedOutputStream(FileOutputStream(currentFile), 131072)
            currentOut.write(ByteArray(44))
            currentChunkPcmBytes = 0

            val overlapSamples = (overlapFilled / 2).toLong()
            currentChunkStartSample = (totalWrittenMonoSamples - overlapSamples).coerceAtLeast(0L)
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
            }
        }

        fun pushMonoSample(sample: Short) {
            val lo = (sample.toInt() and 0xFF).toByte()
            val hi = ((sample.toInt() ushr 8) and 0xFF).toByte()
            ioChunkBuf[ioChunkBufUsed++] = lo
            ioChunkBuf[ioChunkBufUsed++] = hi
            if (ioChunkBufUsed >= ioChunkBuf.size - 2) {
                flushIoBuf()
            }
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
        } else {
            44100
        }
        var srcChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        } else {
            2
        }
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var resamplePos = 0.0
        var resampleStep = srcSampleRate.toDouble() / targetSampleRate.toDouble()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone && !cancelAudioExtraction) {
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
                            srcChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        resampleStep = srcSampleRate.toDouble() / targetSampleRate.toDouble()
                    } else if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)
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
                                    pushMonoSample(monoShort)
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
                                    pushMonoSample(monoShort)
                                    resamplePos += resampleStep
                                }
                                resamplePos = (resamplePos - totalFrames.toDouble()).coerceAtLeast(0.0)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            break
                        }
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                }
            }
            if (cancelAudioExtraction) {
                try { currentFile.delete() } catch (_: Exception) {}
            } else {
                finalizeChunk(isLast = true)
            }
        } finally {
            try { currentOut.close() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
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
        SubtitleForegroundService.onCancelRequested = null
        releaseCapturePlayer()
        stopForegroundService()
        mainHandler.removeCallbacks(positionPoller)
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
        exoPlayer.release()
    }

    companion object {
        const val CHUNK_STREAM_SCHEME = "https://"
        const val CHUNK_HOST = "sublater-chunks.local"

        private val EMBEDDED_MKV_ASS_REGEX = Regex(
            """^\d+,\d+,[^,]*,[^,]*,\d+,\d+,\d+,[^,]*,(.*)$""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val EMBEDDED_SRT_TIME_REGEX = Regex("""^\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*.*$""")
        private val EMBEDDED_INT_LINE_REGEX = Regex("""^\d+$""")
        private val EMBEDDED_ASS_TAG_REGEX = Regex("""\{\\[^}]*\}""")
        private val EMBEDDED_HTML_TAG_REGEX = Regex("""<[^>]+>""")
        private val EMBEDDED_MULTI_SPACE_REGEX = Regex("""\s+""")
    }
}
