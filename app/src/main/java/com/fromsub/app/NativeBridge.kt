package com.fromsub.app

import android.webkit.JavascriptInterface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class NativeBridge(private val manager: ExoPlayerManager) {

    @JavascriptInterface
    fun getStoredMediaUri(): String = manager.getStoredMediaUri()

    @JavascriptInterface
    fun startForegroundService(statusText: String = "Generating subtitles...") =
        manager.startForegroundService(statusText)

    @JavascriptInterface
    fun updateForegroundNotification(statusText: String) =
        manager.updateForegroundNotification(statusText)

    @JavascriptInterface
    fun updateForegroundProgress(statusText: String, progress: Int) =
        manager.updateForegroundProgress(statusText, progress)

    @JavascriptInterface
    fun stopForegroundService() = manager.stopForegroundService()

    @JavascriptInterface
    fun prepareStoredVideo() = manager.prepareStoredVideo()

    @JavascriptInterface
    fun setImmersiveFullscreen(enabled: Boolean) = manager.setImmersiveFullscreen(enabled)

    @JavascriptInterface
    fun setStatusBarColor(colorHex: String) = manager.setStatusBarColor(colorHex)

    @JavascriptInterface
    fun setNativeSurfaceColor(colorHex: String) = manager.setNativeSurfaceColor(colorHex)

    @JavascriptInterface
    fun isSystemDarkMode(): Boolean = manager.isSystemDarkMode()

    @JavascriptInterface
    fun toggleLandscape(landscape: Boolean) = manager.toggleLandscape(landscape)

    @JavascriptInterface
    fun getCurrentTime(): Double = manager.getCurrentTime()

    @JavascriptInterface
    fun getCurrentTimeMs(): Long = manager.getCurrentTimeMs()

    @JavascriptInterface
    fun getDuration(): Double = manager.getDuration()

    @JavascriptInterface
    fun isPlaying(): Boolean = manager.isPlaying()

    @JavascriptInterface
    fun isPaused(): Boolean = manager.isPaused()

    @JavascriptInterface
    fun getVideoWidth(): Int = manager.getVideoWidth()

    @JavascriptInterface
    fun getVideoHeight(): Int = manager.getVideoHeight()

    @JavascriptInterface
    fun hasNativeVideo(): Boolean = manager.hasNativeVideo()

    @JavascriptInterface
    fun play() = manager.play()

    @JavascriptInterface
    fun pause() = manager.pause()

    @JavascriptInterface
    fun togglePlay(): Boolean = manager.togglePlay()

    @JavascriptInterface
    fun seekTo(seconds: Double) = manager.seekTo(seconds)

    @JavascriptInterface
    fun seekBy(deltaSeconds: Double) = manager.seekBy(deltaSeconds)

    @JavascriptInterface
    fun setVolume(volumePercentValue: Int) = manager.setVolume(volumePercentValue)

    @JavascriptInterface
    fun setBoost(boostPercentValue: Int) = manager.setBoost(boostPercentValue)

    @JavascriptInterface
    fun setSpeed(speed: Float) = manager.setSpeed(speed)

    @JavascriptInterface
    fun setResizeMode(mode: String) = manager.setResizeMode(mode)

    @JavascriptInterface
    fun getAudioTracksJson(): String = manager.getAudioTracksJson()

    @JavascriptInterface
    fun selectAudioTrack(orderIndex: Int): Boolean = manager.selectAudioTrack(orderIndex)

    @JavascriptInterface
    fun extractEmbeddedSubtitleSegmentsJson(): String = manager.extractEmbeddedSubtitleSegmentsJson()

    @JavascriptInterface
    fun setPlayerVisibility(visible: Boolean) = manager.setPlayerVisibility(visible)

    @JavascriptInterface
    fun setPlayerBounds(
        leftCss: Float,
        topCss: Float,
        widthCss: Float,
        heightCss: Float,
        viewportW: Float,
        viewportH: Float,
        isFullscreen: Boolean
    ) = manager.setPlayerBounds(leftCss, topCss, widthCss, heightCss, viewportW, viewportH, isFullscreen)

    @JavascriptInterface
    fun getCurrentFrameBase64(): String = manager.getCurrentFrameBase64()

    @JavascriptInterface
    fun requestEmbeddedSubtitlesAsync(reqId: Int) = manager.requestEmbeddedSubtitlesAsync(reqId)

    @JavascriptInterface
    fun requestAudioTracksAsync(reqId: Int) = manager.requestAudioTracksAsync(reqId)

    @JavascriptInterface
    fun requestCurrentFrameAsync(reqId: Int) = manager.requestCurrentFrameAsync(reqId)

    @JavascriptInterface
    fun openWithExternalApp() = manager.openWithExternalApp()

    @JavascriptInterface
    fun saveSubtitleFile(fileName: String, content: String, format: String): Boolean =
        manager.saveSubtitleFile(fileName, content, format)

    @JavascriptInterface
    fun shareSubtitleFile(fileName: String, content: String, format: String): Boolean =
        manager.shareSubtitleFile(fileName, content, format)

    @JavascriptInterface
    fun cancelNativeAudioExtraction() = manager.cancelNativeAudioExtraction()

    @JavascriptInterface
    fun clearAudioCache(): Boolean = manager.clearAudioCache()

    @JavascriptInterface
    fun readChunkBase64(filePath: String): String = manager.readChunkBase64(filePath)

    @JavascriptInterface
    fun deleteChunkFile(filePath: String): Boolean = manager.deleteChunkFile(filePath)

    @JavascriptInterface
    fun startNativeAudioExtraction(): Boolean = manager.startNativeAudioExtractionMode(0.0, false)

    @JavascriptInterface
    fun startNativeAudioExtractionFrom(startSec: Double): Boolean =
        manager.startNativeAudioExtractionMode(startSec, false)

    @JavascriptInterface
    fun startNativeAudioExtractionMode(startSec: Double, batchMode: Boolean): Boolean =
        manager.startNativeAudioExtractionMode(startSec, batchMode)

    @JavascriptInterface
    fun saveApiKey(key: String): Boolean = manager.keyVault.saveApiKey(key)

    @JavascriptInterface
    fun getApiKey(): String = manager.keyVault.getApiKey()

    @JavascriptInterface
    fun saveGeminiApiKey(key: String): Boolean = manager.keyVault.saveGeminiApiKey(key)

    @JavascriptInterface
    fun getGeminiApiKey(): String = manager.keyVault.getGeminiApiKey()

    @JavascriptInterface
    fun saveSecureKey(alias: String, plainValue: String): Boolean = manager.keyVault.saveSecureKey(alias, plainValue)

    @JavascriptInterface
    fun getSecureKey(alias: String): String = manager.keyVault.getSecureKey(alias)

    @JavascriptInterface
    fun hasSecureKey(alias: String): Boolean = manager.keyVault.hasSecureKey(alias)
}
