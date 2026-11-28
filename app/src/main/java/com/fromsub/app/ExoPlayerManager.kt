package com.fromsub.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class VideoBounds(
    val leftRatio: Float = 0f,
    val topRatio: Float = 0f,
    val widthRatio: Float = 1f,
    val heightRatio: Float = 0f,
    val isFullscreen: Boolean = false,
    val isVisible: Boolean = false
)

@OptIn(UnstableApi::class)
class ExoPlayerManager(
    private val context: Context,
    private val activity: MainActivity? = null
) {

    companion object {
        private const val TAG = "SubLyterPlayer"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "SubLyter-BridgeIO").apply { isDaemon = true }
    }

    val keyVault = SecureKeyVault(context)
    val voiceRecorder = NativeVoiceRecorder(context)

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var volumePercent: Int = 100
    private var boostPercent: Int = 100

    @Volatile
    private var currentVideoUri: Uri? = null

    @Volatile
    private var currentMimeHint: String? = null

    @Volatile
    private var webViewRef: WebView? = null

    @Volatile
    private var selectedAudioOrderIndex: Int = 0

    @Volatile
    private var playerInitialized: Boolean = false

    @Volatile
    private var released: Boolean = false

    @Volatile
    private var fgCancelLabel: String = "Cancel"

    @Volatile
    private var foregroundServiceActive: Boolean = false

    @Volatile
    private var lastFgUpdateMs: Long = 0L

    private var lastFgText: String = ""
    private var lastFgProgress: Int = -2

    val isVideoReady = mutableStateOf(false)
    val isPlayerReadyForAttach = mutableStateOf(false)
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
    private var cachedPlaybackSpeed: Float = 1f

    @Volatile
    private var cachedVideoWidth: Int = 0

    @Volatile
    private var cachedVideoHeight: Int = 0

    @Volatile
    private var pollerRunning: Boolean = false

    val audioChunkExtractor = AudioChunkExtractor(
        context = context,
        mainHandler = mainHandler,
        postJs = ::postJs,
        currentUriProvider = { currentVideoUri },
        currentMimeProvider = { currentMimeHint },
        selectedAudioIndexProvider = { selectedAudioOrderIndex }
    )

    init {
        SubtitleForegroundService.onCancelRequested = {
            cancelNativeAudioExtraction()
            postJs("if (window.__cancelFromNotification) window.__cancelFromNotification();")
        }
    }

    val exoPlayer: ExoPlayer by lazy {
        playerInitialized = true
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 15_000, 1_500, 3_000)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        cachedIsPlaying = isPlaying
                        syncStateOnMainThread()
                        notifyPlaybackStateToJs()
                        updatePositionPollerState()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            isVideoReady.value = true
                        }
                        syncStateOnMainThread()
                        notifyPlaybackStateToJs()
                        updatePositionPollerState()
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        syncStateOnMainThread()
                        notifyPlaybackStateToJs()
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        cachedPlaybackSpeed = playbackParameters.speed
                        syncStateOnMainThread()
                        notifyPlaybackStateToJs()
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
            if (released || !videoBoundsState.value.isVisible || !cachedIsPlaying) {
                pollerRunning = false
                return
            }
            syncStateOnMainThread()
            notifyPlaybackStateToJs()
            mainHandler.postDelayed(this, 120L)
        }
    }

    private fun updatePositionPollerState() {
        mainHandler.post {
            val shouldRun = !released && videoBoundsState.value.isVisible && cachedIsPlaying
            if (shouldRun && !pollerRunning) {
                pollerRunning = true
                mainHandler.post(positionPoller)
            } else if (!shouldRun && pollerRunning) {
                pollerRunning = false
                mainHandler.removeCallbacks(positionPoller)
            }
        }
    }

    fun attachWebView(webView: WebView?) {
        this.webViewRef = webView
    }

    private fun notifyPlaybackStateToJs() {
        val posSec = cachedPositionMs / 1000.0
        val durSec = cachedDurationMs / 1000.0
        val playing = cachedIsPlaying
        val spd = cachedPlaybackSpeed
        postJs("if (window.__onNativePlaybackStateChanged) window.__onNativePlaybackStateChanged($playing, $posSec, $durSec, $spd);")
    }

    private fun postJs(jsCode: String) {
        if (released) return
        mainHandler.post {
            try {
                webViewRef?.evaluateJavascript(jsCode, null)
            } catch (e: Exception) {
                Log.w(TAG, "postJs failed: ${e.message}")
            }
        }
    }

    private fun syncStateOnMainThread() {
        if (!playerInitialized) return
        try {
            cachedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration
            cachedDurationMs = if (dur != C.TIME_UNSET && dur > 0L) dur else 0L
            cachedIsPlaying = exoPlayer.isPlaying
        } catch (_: Exception) {}
    }

    fun storeMediaUri(uri: Uri, mimeHint: String? = null) {
        currentVideoUri = uri
        currentMimeHint = mimeHint
        selectedAudioOrderIndex = 0
        cachedVideoWidth = 0
        cachedVideoHeight = 0
    }

    fun setMediaUri(uriString: String, mimeHint: String? = null): Boolean {
        val clean = uriString.trim()
        if (clean.isEmpty()) return false
        val uri = try {
            Uri.parse(clean)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: ""
        val hint = mimeHint?.takeIf { it.isNotBlank() }
        if (scheme == "http" || scheme == "https") {
            storeMediaUri(uri, hint)
            return true
        }
        val readable = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
        if (!readable) return false
        storeMediaUri(uri, hint)
        return true
    }

    fun getStoredMediaUri(): String = currentVideoUri?.toString() ?: ""

    fun isGenerationActive(): Boolean = foregroundServiceActive || audioChunkExtractor.isActive()

    fun startForegroundService(statusText: String = "Generating subtitles...") {
        foregroundServiceActive = true
        try {
            val intent = Intent(context, SubtitleForegroundService::class.java).apply {
                action = SubtitleForegroundService.ACTION_START_OR_UPDATE
                putExtra(SubtitleForegroundService.EXTRA_STATUS_TEXT, statusText)
                putExtra(SubtitleForegroundService.EXTRA_CANCEL_LABEL, fgCancelLabel)
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {}
    }

    fun startForegroundServiceLocalized(statusText: String, cancelLabel: String) {
        fgCancelLabel = cancelLabel.takeIf { it.isNotBlank() } ?: "Cancel"
        startForegroundService(statusText)
    }

    fun updateForegroundNotification(statusText: String) {
        updateForegroundProgress(statusText, -1)
    }

    fun updateForegroundProgress(statusText: String, progress: Int) {
        val clamped = progress.coerceIn(-1, 100)
        val now = System.currentTimeMillis()
        val isTerminal = clamped >= 100 || clamped == 0
        if (SubtitleForegroundService.isRunning) {
            if (!isTerminal && statusText == lastFgText && clamped == lastFgProgress) return
            if (!isTerminal && now - lastFgUpdateMs < 650L) return
        }
        lastFgUpdateMs = now
        lastFgText = statusText
        lastFgProgress = clamped

        if (SubtitleForegroundService.updateNotificationIfRunning(context, statusText, clamped, fgCancelLabel)) {
            return
        }
        foregroundServiceActive = true
        try {
            val intent = Intent(context, SubtitleForegroundService::class.java).apply {
                action = SubtitleForegroundService.ACTION_START_OR_UPDATE
                putExtra(SubtitleForegroundService.EXTRA_STATUS_TEXT, statusText)
                putExtra(SubtitleForegroundService.EXTRA_PROGRESS, clamped)
                putExtra(SubtitleForegroundService.EXTRA_CANCEL_LABEL, fgCancelLabel)
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {}
    }

    fun stopForegroundService() {
        foregroundServiceActive = false
        lastFgUpdateMs = 0L
        lastFgText = ""
        lastFgProgress = -2
        try {
            val intent = Intent(context, SubtitleForegroundService::class.java).apply {
                action = SubtitleForegroundService.ACTION_STOP
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    fun prepareStoredVideo() {
        val uri = currentVideoUri ?: return
        cachedVideoWidth = 0
        cachedVideoHeight = 0
        val mimeHint = currentMimeHint
        if (mimeHint.isNullOrBlank() && (uri.scheme == "http" || uri.scheme == "https")) {
            currentMimeHint = mimeFromUri(uri)
        }
        mainHandler.post {
            try {
                isPlayerReadyForAttach.value = true
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
                if (cachedPlaybackSpeed != 1f) {
                    exoPlayer.playbackParameters = PlaybackParameters(cachedPlaybackSpeed)
                }
                if (volumePercent != 100 || boostPercent != 100) {
                    applyAudioGain()
                }
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

    fun setImmersiveFullscreen(enabled: Boolean) {
        activity?.applyImmersiveMode(enabled)
    }

    fun setStatusBarColor(colorHex: String) {
        val color = try {
            Color.parseColor(colorHex)
        } catch (_: Exception) {
            return
        }
        activity?.applyStatusBarColor(color)
    }

    fun setNativeSurfaceColor(colorHex: String) {
        val color = try {
            Color.parseColor(colorHex)
        } catch (_: Exception) {
            return
        }
        hostSurfaceColorState.intValue = color
        activity?.applyStatusBarColor(color)
    }

    fun isSystemDarkMode(): Boolean {
        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightFlags == Configuration.UI_MODE_NIGHT_YES
    }

    fun getSystemLanguageTag(): String {
        return try {
            java.util.Locale.getDefault().toLanguageTag()
        } catch (_: Exception) {
            "en"
        }
    }

    fun toggleLandscape(landscape: Boolean) {
        activity?.applyLandscapeOrientation(landscape)
    }

    fun getCurrentTime(): Double = cachedPositionMs / 1000.0
    fun getCurrentTimeMs(): Long = cachedPositionMs
    fun getDuration(): Double = cachedDurationMs / 1000.0
    fun isPlaying(): Boolean = cachedIsPlaying
    fun isPaused(): Boolean = !cachedIsPlaying
    fun getVideoWidth(): Int = cachedVideoWidth
    fun getVideoHeight(): Int = cachedVideoHeight
    fun hasNativeVideo(): Boolean = currentVideoUri != null

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
            updatePositionPollerState()
        }
    }

    fun pause() {
        cachedIsPlaying = false
        if (!playerInitialized) return
        mainHandler.post {
            exoPlayer.pause()
            syncStateOnMainThread()
            updatePositionPollerState()
        }
    }

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
            updatePositionPollerState()
        }
        return nextPlay
    }

    fun seekTo(seconds: Double) {
        val targetMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)
        cachedPositionMs = targetMs
        mainHandler.post {
            exoPlayer.seekTo(targetMs)
            syncStateOnMainThread()
        }
    }

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

    fun setVolume(volumePercentValue: Int) {
        volumePercent = volumePercentValue.coerceIn(0, 200)
        applyAudioGain()
    }

    fun setBoost(boostPercentValue: Int) {
        boostPercent = boostPercentValue.coerceIn(100, 200)
        applyAudioGain()
    }

    private fun applyAudioGain() {
        if (!playerInitialized) return
        val volume = volumePercent
        val boost = boostPercent
        mainHandler.post {
            val effective = (volume * boost / 100).coerceAtMost(400)
            if (effective <= 100) {
                exoPlayer.volume = effective / 100f
                try {
                    loudnessEnhancer?.enabled = false
                } catch (_: Exception) {}
            } else {
                exoPlayer.volume = 1.0f
                try {
                    val sessionId = exoPlayer.audioSessionId
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
                        if (loudnessEnhancer == null || loudnessSessionId != sessionId) {
                            try { loudnessEnhancer?.release() } catch (_: Exception) {}
                            loudnessEnhancer = LoudnessEnhancer(sessionId)
                            loudnessSessionId = sessionId
                        }
                        val gainMb = (2000.0 * Math.log10(effective / 100.0)).toInt().coerceAtMost(2000)
                        loudnessEnhancer?.setTargetGain(gainMb)
                        loudnessEnhancer?.enabled = true
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun setSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(0.25f, 3.0f)
        cachedPlaybackSpeed = safeSpeed
        if (!playerInitialized) return
        mainHandler.post {
            exoPlayer.playbackParameters = PlaybackParameters(safeSpeed)
        }
    }

    fun setResizeMode(mode: String) {
        mainHandler.post {
            resizeModeState.intValue = when (mode.lowercase()) {
                "cover" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
    }

    fun getAudioTracksJson(): String {
        val result = JSONArray()
        return try {
            val uri = currentVideoUri ?: return "[]"
            if (uri.scheme == "http" || uri.scheme == "https") {
                if (!playerInitialized) return "[]"
                val latch = CountDownLatch(1)
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
                latch.await(400, TimeUnit.MILLISECONDS)
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
            } catch (e: Exception) {
                Log.w(TAG, "selectAudioTrack failed: ${e.message}")
            }
        }
        return true
    }

    fun extractEmbeddedSubtitleSegmentsJson(): String {
        return EmbeddedSubtitleExtractor.extractSegmentsJson(context, currentVideoUri)
    }

    fun setPlayerVisibility(visible: Boolean) {
        mainHandler.post {
            val cur = videoBoundsState.value
            if (cur.isVisible != visible) {
                videoBoundsState.value = cur.copy(isVisible = visible)
            }
            updatePositionPollerState()
        }
    }

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
            val cur = videoBoundsState.value
            val next = VideoBounds(
                leftRatio = (leftCss / viewportW).coerceIn(-1f, 2f),
                topRatio = (topCss / viewportH).coerceIn(-3f, 3f),
                widthRatio = (widthCss / viewportW).coerceIn(0.05f, 1f),
                heightRatio = (heightCss / viewportH).coerceIn(0.05f, 1f),
                isFullscreen = isFullscreen,
                isVisible = cur.isVisible
            )
            val changed = cur.isVisible != next.isVisible ||
                cur.isFullscreen != next.isFullscreen ||
                abs(cur.leftRatio - next.leftRatio) >= 0.001f ||
                abs(cur.topRatio - next.topRatio) >= 0.001f ||
                abs(cur.widthRatio - next.widthRatio) >= 0.001f ||
                abs(cur.heightRatio - next.heightRatio) >= 0.001f
            if (changed) {
                videoBoundsState.value = next
            }
        }
    }

    fun getCurrentFrameBase64(): String {
        val uri = currentVideoUri ?: return ""
        val retriever = MediaMetadataRetriever()
        return try {
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                retriever.setDataSource(uri.toString(), emptyMap())
            } else {
                retriever.setDataSource(context, uri)
            }
            val timeUs = if (cachedPositionMs > 100L) cachedPositionMs * 1000L else 1000000L
            val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    512,
                    512
                )
            } else {
                null
            } ?: retriever.getFrameAtTime(
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
            if (scaledBitmap != rawBitmap) scaledBitmap.recycle()
            rawBitmap.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentFrameBase64 failed: ${e.message}")
            ""
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    fun onTrimMemory(level: Int) {
        if (boostPercent <= 100 && volumePercent <= 100) {
            try { loudnessEnhancer?.release() } catch (_: Exception) {}
            loudnessEnhancer = null
            loudnessSessionId = C.AUDIO_SESSION_ID_UNSET
        }
        if (playerInitialized && !cachedIsPlaying && !videoBoundsState.value.isVisible &&
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            mainHandler.post {
                try {
                    if (!cachedIsPlaying && !videoBoundsState.value.isVisible) {
                        exoPlayer.stop()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun requestEmbeddedSubtitlesAsync(reqId: Int) {
        ioExecutor.execute {
            val result = try {
                extractEmbeddedSubtitleSegmentsJson()
            } catch (e: Exception) {
                Log.w(TAG, "requestEmbeddedSubtitlesAsync failed: ${e.message}")
                "[]"
            }
            postJs("if (window.__onNativeAsyncResult) window.__onNativeAsyncResult($reqId, true, ${JSONObject.quote(result)});")
        }
    }

    fun requestAudioTracksAsync(reqId: Int) {
        ioExecutor.execute {
            val result = try {
                getAudioTracksJson()
            } catch (e: Exception) {
                Log.w(TAG, "requestAudioTracksAsync failed: ${e.message}")
                "[]"
            }
            postJs("if (window.__onNativeAsyncResult) window.__onNativeAsyncResult($reqId, true, ${JSONObject.quote(result)});")
        }
    }

    fun requestCurrentFrameAsync(reqId: Int) {
        ioExecutor.execute {
            val result = try {
                getCurrentFrameBase64()
            } catch (e: Exception) {
                Log.w(TAG, "requestCurrentFrameAsync failed: ${e.message}")
                ""
            }
            postJs("if (window.__onNativeAsyncResult) window.__onNativeAsyncResult($reqId, true, ${JSONObject.quote(result)});")
        }
    }

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

    fun saveSubtitleFile(fileName: String, content: String, format: String): Boolean {
        return SubtitleExporter.saveSubtitleFile(context, mainHandler, fileName, content, format)
    }

    fun shareSubtitleFile(fileName: String, content: String, format: String): Boolean {
        return SubtitleExporter.shareSubtitleFile(context, mainHandler, fileName, content, format)
    }

    fun cancelNativeAudioExtraction() {
        audioChunkExtractor.cancel()
        stopForegroundService()
    }

    fun clearAudioCache(): Boolean = audioChunkExtractor.clearAudioCache()
    fun readChunkBase64(filePath: String): String = audioChunkExtractor.readChunkBase64(filePath)
    fun deleteChunkFile(filePath: String): Boolean = audioChunkExtractor.deleteChunkFile(filePath)
    fun isChunkStreamUrl(url: String?): Boolean = audioChunkExtractor.isChunkStreamUrl(url)
    fun buildChunkStreamResponse(url: String): WebResourceResponse? = audioChunkExtractor.buildChunkStreamResponse(url)
    fun startNativeAudioExtractionMode(startSec: Double, batchMode: Boolean): Boolean =
        audioChunkExtractor.startExtraction("", startSec, batchMode)

    fun hasMicPermission(): Boolean = voiceRecorder.hasPermission()
    fun requestMicPermission() { activity?.requestNativeMicPermission() }
    fun startNativeVoiceRecording(): String = voiceRecorder.start()
    fun stopNativeVoiceRecording(): String = voiceRecorder.stop()
    fun cancelNativeVoiceRecording() = voiceRecorder.cancel()

    fun release() {
        released = true
        pollerRunning = false
        SubtitleForegroundService.onCancelRequested = null
        mainHandler.removeCallbacks(positionPoller)
        audioChunkExtractor.release()
        voiceRecorder.cancel()
        stopForegroundService()
        ioExecutor.shutdownNow()
        keyVault.release()
        webViewRef = null
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        if (playerInitialized) {
            try { exoPlayer.release() } catch (_: Exception) {}
        }
    }
}
