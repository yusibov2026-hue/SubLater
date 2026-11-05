package com.fromsub.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class VideoBounds(
    val leftRatio: Float = 0f,
    val topRatio: Float = 0f,
    val widthRatio: Float = 1f,
    val heightRatio: Float = 0f,
    val isFullscreen: Boolean = false,
    val isVisible: Boolean = false
)

@OptIn(UnstableApi::class)
class ExoPlayerManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentVideoUri: Uri? = null

    val isVideoReady = mutableStateOf(false)
    val resizeModeState = mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
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
                            cachedVideoWidth = videoSize.width
                            cachedVideoHeight = videoSize.height
                        }
                    }
                })
            }
    }

    private val positionPoller = object : Runnable {
        override fun run() {
            syncStateOnMainThread()
            mainHandler.postDelayed(this, 16L)
        }
    }

    init {
        mainHandler.post(positionPoller)
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

    fun setMediaUri(uri: Uri) {
        currentVideoUri = uri
        mainHandler.post {
            try {
                val mediaItem = MediaItem.fromUri(uri)
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                isVideoReady.value = true
                syncStateOnMainThread()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка загрузки видео: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
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
        val visible = widthCss >= 10f && heightCss >= 10f && (topCss + heightCss) > 0f && topCss < viewportH
        mainHandler.post {
            videoBoundsState.value = VideoBounds(
                leftRatio = (leftCss / viewportW).coerceIn(-1f, 2f),
                topRatio = (topCss / viewportH).coerceIn(-3f, 3f),
                widthRatio = (widthCss / viewportW).coerceIn(0.05f, 1f),
                heightRatio = (heightCss / viewportH).coerceIn(0.05f, 1f),
                isFullscreen = isFullscreen,
                isVisible = visible || isFullscreen
            )
        }
    }

    @JavascriptInterface
    fun getCurrentFrameBase64(): String {
        val uri = currentVideoUri ?: return ""
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val timeUs = cachedPositionMs.coerceAtLeast(0L) * 1000L
            val rawBitmap = retriever.getFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime() ?: return ""

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

    fun release() {
        mainHandler.removeCallbacks(positionPoller)
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
        exoPlayer.release()
    }
}
