package com.fromsub.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var exoPlayerManager: ExoPlayerManager
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var webViewRef: WebView? = null
    private var pendingExternalJson: String = ""

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

    private var immersiveFullscreen = false
    private var currentSurfaceColor: Int = 0xFF161922.toInt()

    fun applyImmersiveMode(fullscreen: Boolean) {
        runOnUiThread {
            immersiveFullscreen = fullscreen
            applyImmersiveNow()
        }
    }

    fun applyStatusBarColor(color: Int) {
        runOnUiThread {
            currentSurfaceColor = color
            if (!immersiveFullscreen) {
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                applyBarIconAppearance(color)
            }
        }
    }

    fun statusBarInsetPx(): Int = systemBarHeightPx("status_bar_height")

    fun navigationBarInsetPx(): Int = systemBarHeightPx("navigation_bar_height")

    private fun systemBarHeightPx(name: String): Int {
        val resId = resources.getIdentifier(name, "dimen", "android")
        if (resId <= 0) return 0
        val rawPx = resources.getDimensionPixelSize(resId)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        return (rawPx / density + 0.5f).toInt()
    }

    @Suppress("DEPRECATION")
    private fun applyBarIconAppearance(color: Int) {
        val luma = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        val isLightBg = luma > 0.6
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appearance = if (isLightBg) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            } else {
                0
            }
            window.insetsController?.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (isLightBg) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun applyCutoutMode(fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val attrs = window.attributes
        attrs.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && fullscreen) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = attrs
    }

    private fun applyImmersiveNow() {
        applyCutoutMode(immersiveFullscreen)
        if (immersiveFullscreen) {
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
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
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                applyBarIconAppearance(currentSurfaceColor)
            } else {
                applyBarIconAppearance(currentSurfaceColor)
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

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        applyCutoutMode(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        exoPlayerManager = ExoPlayerManager(applicationContext, this)
        handleExternalIntent(intent)

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
        webViewRef?.resumeTimers()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveFullscreen) applyImmersiveNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    private fun handleExternalIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return

        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            else -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
        } ?: return

        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme != "content" && scheme != "file" && scheme != "http" && scheme != "https") return
        val isStream = scheme == "http" || scheme == "https"
        if (isStream) {
            handleStreamIntent(uri, intent)
            return
        }

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }

        var displayName = ""
        var size = 0L
        var mime = intent.type ?: ""
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: ""
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) {
        }
        if (displayName.isBlank()) displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "video"
        if (mime.isBlank()) mime = contentResolver.getType(uri) ?: "video/*"
        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) {
            mime = guessMediaMimeByExt(displayName, mime.ifBlank { "video/*" })
        }

        exoPlayerManager.storeMediaUri(uri)
        pendingExternalJson = org.json.JSONObject().apply {
            put("uri", uri.toString())
            put("name", displayName)
            put("size", size)
            put("mime", mime)
            put("lastModified", System.currentTimeMillis())
        }.toString()

        flushPendingExternalMedia()
    }

    private fun handleStreamIntent(uri: Uri, intent: Intent) {
        var displayName = uri.lastPathSegment?.substringBefore('?')?.substringAfterLast('/') ?: ""
        displayName = Uri.decode(displayName).orEmpty().trim()
        if (displayName.isBlank()) displayName = "Stream"

        var mime = intent.type ?: ""
        if (mime.isBlank() || mime == "application/octet-stream" || mime == "*/*") {
            mime = guessMediaMimeByExt(uri.lastPathSegment ?: "", mime.ifBlank { "video/*" })
        }

        exoPlayerManager.storeMediaUri(uri, mime)
        pendingExternalJson = org.json.JSONObject().apply {
            put("uri", uri.toString())
            put("name", displayName)
            put("size", 0L)
            put("mime", mime)
            put("isStream", true)
            put("lastModified", System.currentTimeMillis())
        }.toString()

        flushPendingExternalMedia()
    }

    private fun guessMediaMimeByExt(name: String, fallback: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            else -> fallback
        }
    }

    private fun flushPendingExternalMedia() {
        if (pendingExternalJson.isBlank()) return
        val wv = webViewRef ?: return
        val json = pendingExternalJson
        pendingExternalJson = ""
        wv.post {
            wv.evaluateJavascript(
                "window.__onExternalMedia && window.__onExternalMedia(${org.json.JSONObject.quote(json)});",
                null
            )
        }
    }

    fun consumePendingExternalMedia(): String {
        val json = pendingExternalJson
        pendingExternalJson = ""
        return json
    }

    override fun onDestroy() {
        exoPlayerManager.attachWebView(null)
        webViewRef?.apply {
            stopLoading()
            removeJavascriptInterface("ExoPlayerManager")
            removeJavascriptInterface("AndroidBridge")
            destroy()
        }
        webViewRef = null
        exoPlayerManager.release()
        super.onDestroy()
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
    val hostSurfaceColor by exoPlayerManager.hostSurfaceColorState

    DisposableEffect(Unit) {
        onDispose { }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(if (bounds.isFullscreen) ComposeColor(0xFF000000) else ComposeColor(hostSurfaceColor))
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
                .clip(RoundedCornerShape(16.dp))
        }

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

        AndroidView(
            factory = { ctx ->
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                    .build()
                val nativeBridge = NativeBridge(exoPlayerManager)

                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.TRANSPARENT)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = false
                        allowContentAccess = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }

                    addJavascriptInterface(nativeBridge, "ExoPlayerManager")
                    addJavascriptInterface(nativeBridge, "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return true
                            val urlStr = uri.toString()
                            if (
                                urlStr.startsWith("https://appassets.androidplatform.net/") ||
                                urlStr.startsWith("file:///android_asset/")
                            ) {
                                return false
                            }
                            if (urlStr.startsWith("https://") || urlStr.startsWith("http://")) {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                } catch (_: Exception) {
                                }
                            }
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val uri = request?.url ?: return null
                            val url = uri.toString()
                            if (exoPlayerManager.isChunkStreamUrl(url)) {
                                return exoPlayerManager.buildChunkStreamResponse(url)
                            }
                            return assetLoader.shouldInterceptRequest(uri)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (view == null) return
                            val activity = view.context as? MainActivity
                            if (activity != null) {
                                val sbInset = activity.statusBarInsetPx()
                                val navInset = activity.navigationBarInsetPx()
                                view.evaluateJavascript(
                                    "document.documentElement.style.setProperty('--status-bar-inset','${sbInset}px');" +
                                        "document.documentElement.style.setProperty('--nav-bar-inset','${navInset}px');",
                                    null
                                )
                            }
                            val pending = activity?.consumePendingExternalMedia()
                            if (!pending.isNullOrBlank()) {
                                view.evaluateJavascript(
                                    "window.__onExternalMedia && window.__onExternalMedia(${org.json.JSONObject.quote(pending)});",
                                    null
                                )
                            }
                        }
                    }
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
                            if (request == null) return
                            val allowed = request.resources.filter {
                                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                            }.toTypedArray()
                            if (allowed.isNotEmpty()) {
                                request.grant(allowed)
                            } else {
                                request.deny()
                            }
                        }
                    }

                    exoPlayerManager.attachWebView(this)
                    loadUrl("https://appassets.androidplatform.net/assets/index.html")
                    onWebViewCreated(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
