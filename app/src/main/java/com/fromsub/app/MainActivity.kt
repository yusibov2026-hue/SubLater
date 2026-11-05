package com.fromsub.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var exoPlayerManager: ExoPlayerManager
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var webViewRef: WebView? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null

        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            if (data?.clipData != null) {
                val clip = data.clipData!!
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else if (data?.data != null) {
                uris.add(data.data!!)
            }

            if (uris.isNotEmpty()) {
                val chosenUri = uris.first()
                try {
                    contentResolver.takePersistableUriPermission(
                        chosenUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }

                val mimeType = contentResolver.getType(chosenUri) ?: ""
                val pathStr = chosenUri.toString().lowercase()
                val isSubtitleFile = pathStr.endsWith(".vtt") || pathStr.endsWith(".srt") || pathStr.endsWith(".ass")
                if (!isSubtitleFile && (mimeType.startsWith("video/") || mimeType.startsWith("audio/") || !mimeType.startsWith("text/"))) {
                    exoPlayerManager.storeMediaUri(chosenUri)
                }
                callback?.onReceiveValue(uris.toTypedArray())
            } else {
                callback?.onReceiveValue(null)
            }
        } else {
            callback?.onReceiveValue(null)
        }
    }

    fun applyImmersiveMode(fullscreen: Boolean) {
        runOnUiThread {
            if (fullscreen) {
                window.statusBarColor = Color.BLACK
                window.navigationBarColor = Color.BLACK
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.let { controller ->
                        controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
                }
            } else {
                val grayBg = Color.parseColor("#24272D")
                window.statusBarColor = grayBg
                window.navigationBarColor = grayBg
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }
    }

    fun applyLandscapeOrientation(landscape: Boolean) {
        runOnUiThread {
            requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val grayBg = Color.parseColor("#24272D")
        window.statusBarColor = grayBg
        window.navigationBarColor = grayBg

        exoPlayerManager = ExoPlayerManager(applicationContext, this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webViewRef
                if (wv != null) {
                    wv.evaluateJavascript(
                        "(function(){ if (document.body.classList.contains('player-fullscreen-open')) { document.getElementById('fsBtn').click(); return 'handled'; } return 'exit'; })()"
                    ) { res ->
                        if (res != null && res.contains("handled")) {
                            // Вышли из полноэкранного плеера обратно в главное меню
                        } else if (wv.canGoBack()) {
                            wv.goBack()
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContent {
            HybridVideoApp(
                exoPlayerManager = exoPlayerManager,
                onOpenFileChooser = { callback, fileChooserParams ->
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    val intent = try {
                        fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    } catch (_: Exception) {
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    }
                    fileChooserLauncher.launch(intent)
                    true
                },
                onWebViewCreated = { wv ->
                    webViewRef = wv
                }
            )
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        webViewRef?.destroy()
        exoPlayerManager.release()
    }
}

@OptIn(UnstableApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HybridVideoApp(
    exoPlayerManager: ExoPlayerManager,
    onOpenFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    onWebViewCreated: (WebView) -> Unit
) {
    val resizeMode by exoPlayerManager.resizeModeState
    val bounds by exoPlayerManager.videoBoundsState

    DisposableEffect(Unit) {
        onDispose { }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFF000000))
    ) {
        val maxW = maxWidth
        val maxH = maxHeight

        val playerModifier = if (!bounds.isVisible) {
            Modifier
                .width(maxW * 0f)
                .height(maxH * 0f)
        } else if (bounds.isFullscreen) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .offset(
                    x = maxW * bounds.leftRatio,
                    y = maxH * bounds.topRatio
                )
                .width(maxW * bounds.widthRatio)
                .height(maxH * bounds.heightRatio)
        }

        // Layer 1 (Background): Native AndroidX Media3 ExoPlayer (pure black background, zero gray bars)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayerManager.exoPlayer
                    useController = false
                    this.resizeMode = resizeMode
                    setBackgroundColor(Color.BLACK)
                    setShutterBackgroundColor(Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.resizeMode = resizeMode
            },
            modifier = playerModifier
        )

        // Layer 2 (Foreground): WebView with SubLater UI & Subtitle Overlay
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.TRANSPARENT)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }

                    addJavascriptInterface(exoPlayerManager, "ExoPlayerManager")
                    addJavascriptInterface(exoPlayerManager, "AndroidBridge")
                    addJavascriptInterface(exoPlayerManager, "Android")

                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            if (filePathCallback == null) return false
                            return onOpenFileChooser(filePathCallback, fileChooserParams)
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }
                    }

                    loadUrl("file:///android_asset/index.html")
                    onWebViewCreated(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
