package com.fromsub.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import org.json.JSONObject

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var exoPlayerManager: ExoPlayerManager
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var webViewRef: WebView? = null
    private var currentThemeBgColor: Int = Color.parseColor("#24272D")
    private var pendingExportContent: String? = null
    private var pendingExportFileName: String = "subtitles.vtt"

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val content = pendingExportContent
        val fileName = pendingExportFileName
        pendingExportContent = null
        if (uri != null && content != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                Toast.makeText(this, "Сохранено: $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

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
                    val displayName = resolveUriDisplayName(chosenUri)
                    exoPlayerManager.storeMediaUri(chosenUri, displayName, isExternalIntent = false)
                }
                callback?.onReceiveValue(uris.toTypedArray())
            } else {
                callback?.onReceiveValue(null)
            }
        } else {
            callback?.onReceiveValue(null)
        }
    }

    fun launchSafExportDocument(fileName: String, content: String) {
        runOnUiThread {
            pendingExportFileName = fileName
            pendingExportContent = content
            createDocumentLauncher.launch(fileName)
        }
    }

    private fun resolveUriDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            var cursor: Cursor? = null
            try {
                cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("SubLater", "resolveUriDisplayName failed", e)
            } finally {
                try {
                    cursor?.close()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w("SubLater", "resolveUriDisplayName cursor close failed", e)
                }
            }
        }
        val seg = uri.lastPathSegment?.substringAfterLast('/')
        return if (!seg.isNullOrBlank()) seg else "external_video.mp4"
    }

    fun cleanupWebView() {
        runOnUiThread {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null

            val wv = webViewRef ?: return@runOnUiThread
            webViewRef = null
            if (::exoPlayerManager.isInitialized) {
                exoPlayerManager.detachWebView()
            }

            try {
                wv.stopLoading()
                wv.onPause()
                wv.pauseTimers()
                wv.removeJavascriptInterface("ExoPlayerManager")
                wv.removeJavascriptInterface("AndroidBridge")
                wv.removeJavascriptInterface("Android")
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.clearCache(false)
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.removeAllViews()
                wv.webChromeClient = null
                wv.destroy()
            } catch (_: Exception) {
            }
        }
    }

    fun applySystemBarsColor(hexColor: String) {
        runOnUiThread {
            try {
                val parsed = Color.parseColor(hexColor.trim())
                currentThemeBgColor = parsed
                if (::exoPlayerManager.isInitialized) {
                    exoPlayerManager.themeBgColorState.intValue = parsed
                }
                val isLightBg = ColorUtils.calculateLuminance(parsed) > 0.5
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                if (!::exoPlayerManager.isInitialized || !exoPlayerManager.videoBoundsState.value.isFullscreen) {
                    insetsController.isAppearanceLightStatusBars = isLightBg
                    insetsController.isAppearanceLightNavigationBars = isLightBg
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT < 35) {
                        window.statusBarColor = parsed
                        window.navigationBarColor = parsed
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun applyImmersiveMode(fullscreen: Boolean) {
        runOnUiThread {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        } else {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }
                }
                insetsController.isAppearanceLightStatusBars = false
                insetsController.isAppearanceLightNavigationBars = false
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < 35) {
                    window.statusBarColor = Color.BLACK
                    window.navigationBarColor = Color.BLACK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.let { controller ->
                        controller.hide(
                            WindowInsets.Type.statusBars() or
                                WindowInsets.Type.navigationBars() or
                                WindowInsets.Type.displayCutout()
                        )
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }
                }
                val isLightBg = ColorUtils.calculateLuminance(currentThemeBgColor) > 0.5
                insetsController.isAppearanceLightStatusBars = isLightBg
                insetsController.isAppearanceLightNavigationBars = isLightBg
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < 35) {
                    window.statusBarColor = currentThemeBgColor
                    window.navigationBarColor = currentThemeBgColor
                }
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
                }
            }
        }
    }

    fun applyLandscapeOrientation(landscape: Boolean) {
        runOnUiThread {
            if (!landscape) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                return@runOnUiThread
            }
            val w = exoPlayerManager.getVideoWidth()
            val h = exoPlayerManager.getVideoHeight()
            requestedOrientation = if (h > w && w > 0) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(currentThemeBgColor),
            navigationBarStyle = SystemBarStyle.dark(currentThemeBgColor)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        exoPlayerManager = ExoPlayerManager(applicationContext, this)
        handleIncomingIntent(intent)

        addOnNewIntentListener { newIntent ->
            setIntent(newIntent)
            handleIncomingIntent(newIntent)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webViewRef
                if (wv != null) {
                    wv.evaluateJavascript(
                        "(function(){ if (document.body.classList.contains('global-settings-open')) { document.getElementById('closeSettingsBtn').click(); return 'handled'; } if (document.body.classList.contains('player-fullscreen-open')) { document.getElementById('fsBtn').click(); return 'handled'; } return 'exit'; })()"
                    ) { res ->
                        if (res != null && res.contains("handled")) {
                            // Закрыли модальное окно или вышли из полноэкранного режима
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
                    val chooserIntent = try {
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
                    fileChooserLauncher.launch(chooserIntent)
                    true
                },
                onWebViewCreated = { wv ->
                    webViewRef = wv
                },
                onWebViewDisposed = {
                    cleanupWebView()
                }
            )
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val uri: Uri? = when (action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                } ?: intent.data
            }
            else -> intent.data
        }

        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }

            val pathStr = uri.toString().lowercase()
            val isSubtitleFile = pathStr.endsWith(".vtt") || pathStr.endsWith(".srt") || pathStr.endsWith(".ass")
            if (!isSubtitleFile) {
                val displayName = resolveUriDisplayName(uri)
                exoPlayerManager.storeMediaUri(uri, displayName, isExternalIntent = true)
                exoPlayerManager.prepareStoredVideo()

                val safeName = JSONObject.quote(displayName)
                val safeUri = JSONObject.quote(uri.toString())
                runOnUiThread {
                    webViewRef?.evaluateJavascript(
                        "window.__onExternalMediaOpened && window.__onExternalMediaOpened($safeUri, $safeName);",
                        null
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webViewRef?.onResume()
        webViewRef?.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        if (::exoPlayerManager.isInitialized) {
            exoPlayerManager.pause()
            if (!exoPlayerManager.isBusyInBackground()) {
                webViewRef?.onPause()
            }
        }
    }

    override fun onDestroy() {
        cleanupWebView()
        if (::exoPlayerManager.isInitialized) {
            exoPlayerManager.release()
        }
        super.onDestroy()
    }
}

@OptIn(UnstableApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HybridVideoApp(
    exoPlayerManager: ExoPlayerManager,
    onOpenFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    onWebViewCreated: (WebView) -> Unit,
    onWebViewDisposed: () -> Unit = {}
) {
    val resizeMode by exoPlayerManager.resizeModeState
    val bounds by exoPlayerManager.videoBoundsState
    val zoomScale by exoPlayerManager.videoZoomState
    val themeBgColor by exoPlayerManager.themeBgColorState

    DisposableEffect(Unit) {
        onDispose {
            onWebViewDisposed()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (bounds.isFullscreen) ComposeColor(0xFF000000) else ComposeColor(themeBgColor))
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!bounds.isFullscreen) Modifier.systemBarsPadding() else Modifier)
        ) {
            val maxW = maxWidth
            val maxH = maxHeight

            val playerModifier = if (!bounds.isVisible) {
                Modifier
                    .width(0.dp)
                    .height(0.dp)
                    .alpha(0f)
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

            // Layer 1 (Background): Native AndroidX Media3 ExoPlayer + FFmpeg Audio Decoder
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayerManager.exoPlayer
                        useController = false
                        this.resizeMode = resizeMode
                        setBackgroundColor(Color.BLACK)
                        setShutterBackgroundColor(Color.BLACK)
                        setKeepContentOnPlayerReset(true)
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                val radiusPx = 16f * view.resources.displayMetrics.density
                                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                            }
                        }
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                    }
                },
                update = { playerView ->
                    playerView.resizeMode = resizeMode
                    playerView.scaleX = zoomScale
                    playerView.scaleY = zoomScale
                    playerView.clipToOutline = !bounds.isFullscreen
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
                            @Suppress("DEPRECATION")
                            allowFileAccessFromFileURLs = true
                            @Suppress("DEPRECATION")
                            allowUniversalAccessFromFileURLs = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }

                        addJavascriptInterface(exoPlayerManager, "ExoPlayerManager")
                        addJavascriptInterface(exoPlayerManager, "AndroidBridge")
                        addJavascriptInterface(exoPlayerManager, "Android")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                exoPlayerManager.notifyExternalMediaIfPresent()
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
                                request?.grant(request.resources)
                            }
                        }

                        exoPlayerManager.attachWebView(this)
                        loadUrl("file:///android_asset/index.html")
                        onWebViewCreated(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}