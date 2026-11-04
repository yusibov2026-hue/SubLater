@file:OptIn(UnstableApi::class)

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
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

data class PlayerBounds(
    val xPx: Int = 0,
    val yPx: Int = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val visible: Boolean = false,
    val isFullscreen: Boolean = false,
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
)

class ExoPlayerManager(
    private val context: Context,
    private val evaluateJs: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _playerBounds = mutableStateOf(PlayerBounds())
    val playerBounds: State<PlayerBounds> = _playerBounds

    @Volatile
    private var selectedVideoUri: Uri? = null

    @Volatile
    private var lastPositionMs: Long = 0L

    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val syncRunnable = object : Runnable {
        override fun run() {
            pushStateToJs("tick")
            if (exoPlayer.isPlaying) {
                mainHandler.postDelayed(this, 40L)
            }
        }
    }

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    pushStateToJs("loadedmetadata")
                } else if (playbackState == Player.STATE_ENDED) {
                    pushStateToJs("pause")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                mainHandler.removeCallbacks(syncRunnable)
                if (isPlaying) {
                    pushStateToJs("play")
                    mainHandler.post(syncRunnable)
                } else {
                    pushStateToJs("pause")
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                pushStateToJs("loadedmetadata")
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                try {
                    loudnessEnhancer?.release()
                    if (audioSessionId != 0) {
                        loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                    }
                } catch (_: Exception) {}
            }
        })
    }

    fun setSelectedVideoUri(uri: Uri) {
        selectedVideoUri = uri
    }

    private fun pushStateToJs(event: String) {
        val posMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        lastPositionMs = posMs
        val durMs = exoPlayer.duration.coerceAtLeast(0L)
        val posSec = posMs / 1000.0
        val durSec = if (durMs > 0) durMs / 1000.0 else 0.0
        val playing = exoPlayer.isPlaying
        val vw = exoPlayer.videoSize.width.takeIf { it > 0 } ?: 1920
        val vh = exoPlayer.videoSize.height.takeIf { it > 0 } ?: 1080

        val js = String.format(
            Locale.US,
            "window.__onNativePlayerTick && window.__onNativePlayerTick(%.3f, %.3f, %b, %d, %d, '%s');",
            posSec, durSec, playing, vw, vh, event
        )
        evaluateJs(js)
    }

    @JavascriptInterface
    fun loadSelectedVideo() {
        mainHandler.post {
            val uri = selectedVideoUri ?: return@post
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
        }
    }

    @JavascriptInterface
    fun play() {
        mainHandler.post {
            exoPlayer.play()
        }
    }

    @JavascriptInterface
    fun pause() {
        mainHandler.post {
            exoPlayer.pause()
        }
    }

    @JavascriptInterface
    fun seekTo(positionMs: Long) {
        mainHandler.post {
            val target = positionMs.coerceAtLeast(0L)
            lastPositionMs = target
            exoPlayer.seekTo(target)
            pushStateToJs("seeked")
        }
    }

    @JavascriptInterface
    fun setPlaybackSpeed(speed: Float) {
        mainHandler.post {
            val safeSpeed = speed.coerceIn(0.25f, 3.0f)
            exoPlayer.setPlaybackSpeed(safeSpeed)
        }
    }

    @JavascriptInterface
    fun setVolumeBoost(percent: Int) {
        mainHandler.post {
            val clamped = percent.coerceIn(0, 200)
            if (clamped <= 100) {
                exoPlayer.volume = clamped / 100f
                try {
                    loudnessEnhancer?.enabled = false
                } catch (_: Exception) {}
            } else {
                exoPlayer.volume = 1.0f
                try {
                    if (loudnessEnhancer == null && exoPlayer.audioSessionId != 0) {
                        loudnessEnhancer = LoudnessEnhancer(exoPlayer.audioSessionId)
                    }
                    val gainMb = ((clamped - 100) * 15).coerceAtMost(1500)
                    loudnessEnhancer?.setTargetGain(gainMb)
                    loudnessEnhancer?.enabled = true
                } catch (_: Exception) {}
            }
        }
    }

    @JavascriptInterface
    fun setResizeMode(mode: String) {
        mainHandler.post {
            val resize = when (mode) {
                "cover" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            _playerBounds.value = _playerBounds.value.copy(resizeMode = resize)
        }
    }

    @JavascriptInterface
    fun updatePlayerBounds(
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
        visible: Boolean,
        isFullscreen: Boolean
    ) {
        mainHandler.post {
            val cur = _playerBounds.value
            if (cur.xPx != xPx || cur.yPx != yPx ||
                cur.widthPx != widthPx || cur.heightPx != heightPx ||
                cur.visible != visible || cur.isFullscreen != isFullscreen
            ) {
                _playerBounds.value = cur.copy(
                    xPx = xPx,
                    yPx = yPx,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    visible = visible,
                    isFullscreen = isFullscreen
                )
            }
        }
    }

    @JavascriptInterface
    fun captureFrameBase64(): String {
        val uri = selectedVideoUri ?: return ""
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getFrameAtTime(
                lastPositionMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return ""
            val scale = (512f / bitmap.width.coerceAtLeast(1)).coerceAtMost(1f)
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun saveSubtitleFile(fileName: String, content: String) {
        mainHandler.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    )
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                        }
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    val file = File(dir, fileName)
                    file.writeText(content, Charsets.UTF_8)
                }
                Toast.makeText(context, "Сохранено в Загрузки: $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {}
        exoPlayer.release()
    }

    companion object {
        val JS_BRIDGE_SCRIPT = """
            (function() {
              if (window.__exoBridgeInstalled || !window.AndroidExo) return;
              window.__exoBridgeInstalled = true;

              const style = document.createElement('style');
              style.textContent = `
                html, body { background: transparent !important; }
                #playerContainer { background: transparent !important; box-shadow: none !important; }
                #videoPlayer { opacity: 0 !important; }
                #playerContainer.exo-fullscreen {
                  position: fixed !important;
                  inset: 0 !important;
                  width: 100vw !important;
                  height: 100vh !important;
                  max-width: none !important;
                  margin: 0 !important;
                  border-radius: 0 !important;
                  border: none !important;
                  aspect-ratio: auto !important;
                  z-index: 95 !important;
                }
              `;
              document.head.appendChild(style);

              const videoEl = document.getElementById('videoPlayer');
              const playerBox = document.getElementById('playerContainer');
              if (!videoEl || !playerBox) return;

              window.__exoState = {
                currentTime: 0,
                duration: 0,
                paused: true,
                playbackRate: 1,
                volume: 1,
                videoWidth: 1920,
                videoHeight: 1080,
                lastSyncPerf: performance.now()
              };

              function syncBounds() {
                const rect = playerBox.getBoundingClientRect();
                const visible = playerBox.style.display !== 'none' && rect.width > 0 && rect.height > 0;
                const dpr = window.devicePixelRatio || 1;
                const isFs = playerBox.classList.contains('exo-fullscreen') || !!document.fullscreenElement;
                window.AndroidExo.updatePlayerBounds(
                  Math.round(rect.left * dpr),
                  Math.round(rect.top * dpr),
                  Math.round(rect.width * dpr),
                  Math.round(rect.height * dpr),
                  visible,
                  isFs
                );
              }

              window.addEventListener('scroll', syncBounds, { passive: true });
              window.addEventListener('resize', syncBounds);
              if (window.ResizeObserver) {
                new ResizeObserver(syncBounds).observe(playerBox);
              }
              setInterval(syncBounds, 200);

              window.__onNativePlayerTick = function(curSec, durSec, isPlaying, vw, vh, evType) {
                const st = window.__exoState;
                st.currentTime = curSec;
                st.lastSyncPerf = performance.now();
                if (durSec > 0) st.duration = durSec;
                st.videoWidth = vw || 1920;
                st.videoHeight = vh || 1080;

                const wasPaused = st.paused;
                st.paused = !isPlaying;

                if (evType === 'loadedmetadata') {
                  syncBounds();
                  videoEl.dispatchEvent(new Event('loadedmetadata'));
                  videoEl.dispatchEvent(new Event('loadeddata'));
                } else if (evType === 'seeked') {
                  videoEl.dispatchEvent(new Event('seeked'));
                } else if (evType === 'play' || (wasPaused && isPlaying)) {
                  videoEl.dispatchEvent(new Event('play'));
                } else if (evType === 'pause' || (!wasPaused && !isPlaying)) {
                  videoEl.dispatchEvent(new Event('pause'));
                }
              };

              Object.defineProperties(videoEl, {
                src: {
                  get: function() { return this.dataset.exoSrc || ''; },
                  set: function(val) {
                    this.dataset.exoSrc = val;
                    window.__exoState.currentTime = 0;
                    window.__exoState.lastSyncPerf = performance.now();
                    window.AndroidExo.loadSelectedVideo();
                    setTimeout(syncBounds, 50);
                  }
                },
                currentTime: {
                  get: function() {
                    const st = window.__exoState;
                    if (!st.paused) {
                      const delta = (performance.now() - st.lastSyncPerf) / 1000.0;
                      const est = st.currentTime + delta * st.playbackRate;
                      return st.duration > 0 ? Math.min(st.duration, est) : est;
                    }
                    return st.currentTime;
                  },
                  set: function(val) {
                    const sec = Math.max(0, Number(val) || 0);
                    window.__exoState.currentTime = sec;
                    window.__exoState.lastSyncPerf = performance.now();
                    window.AndroidExo.seekTo(Math.round(sec * 1000));
                  }
                },
                duration: {
                  get: function() { return window.__exoState.duration || 0; }
                },
                paused: {
                  get: function() { return window.__exoState.paused; }
                },
                playbackRate: {
                  get: function() { return window.__exoState.playbackRate; },
                  set: function(val) {
                    const spd = Number(val) || 1;
                    window.__exoState.playbackRate = spd;
                    window.AndroidExo.setPlaybackSpeed(spd);
                  }
                },
                volume: {
                  get: function() { return window.__exoState.volume; },
                  set: function(val) {
                    const v = Math.max(0, Math.min(2, Number(val) || 1));
                    window.__exoState.volume = v;
                    window.AndroidExo.setVolumeBoost(Math.round(v * 100));
                  }
                },
                videoWidth: {
                  get: function() { return window.__exoState.videoWidth || 1920; }
                },
                videoHeight: {
                  get: function() { return window.__exoState.videoHeight || 1080; }
                },
                readyState: {
                  get: function() { return 4; }
                }
              });

              videoEl.play = function() {
                window.__exoState.paused = false;
                window.__exoState.lastSyncPerf = performance.now();
                window.AndroidExo.play();
                videoEl.dispatchEvent(new Event('play'));
                return Promise.resolve();
              };

              videoEl.pause = function() {
                window.__exoState.paused = true;
                window.AndroidExo.pause();
                videoEl.dispatchEvent(new Event('pause'));
              };

              const volSlider = document.getElementById('volSlider');
              if (volSlider) {
                volSlider.addEventListener('input', function(e) {
                  window.AndroidExo.setVolumeBoost(parseInt(e.target.value, 10) || 100);
                });
              }

              const fitSelect = document.getElementById('fitModeSelect');
              if (fitSelect) {
                fitSelect.addEventListener('change', function(e) {
                  window.AndroidExo.setResizeMode(e.target.value || 'contain');
                });
              }

              const fsBtn = document.getElementById('fsBtn');
              if (fsBtn) {
                fsBtn.addEventListener('click', function(e) {
                  e.stopImmediatePropagation();
                  playerBox.classList.toggle('exo-fullscreen');
                  setTimeout(() => {
                    syncBounds();
                    window.dispatchEvent(new Event('resize'));
                  }, 60);
                }, true);
              }

              const origDrawImage = CanvasRenderingContext2D.prototype.drawImage;
              CanvasRenderingContext2D.prototype.drawImage = function(img) {
                if (img === videoEl && this.canvas) {
                  this.canvas.__isExoFrame = true;
                  return;
                }
                return origDrawImage.apply(this, arguments);
              };

              const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
              HTMLCanvasElement.prototype.toDataURL = function() {
                if (this.__isExoFrame) {
                  const b64 = window.AndroidExo.captureFrameBase64();
                  if (b64) return 'data:image/jpeg;base64,' + b64;
                }
                return origToDataURL.apply(this, arguments);
              };

              const origAnchorClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function() {
                if (this.download && this.href && this.href.startsWith('blob:')) {
                  const fname = this.download;
                  fetch(this.href)
                    .then(r => r.text())
                    .then(txt => window.AndroidExo.saveSubtitleFile(fname, txt));
                  return;
                }
                return origAnchorClick.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
