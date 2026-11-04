@file:OptIn(UnstableApi::class)

package com.fromsub.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var exoManager: ExoPlayerManager
    private var webViewRef: WebView? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null

        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                val mime = contentResolver.getType(uri) ?: ""
                val path = uri.toString().lowercase()
                if (mime.startsWith("video/") || mime.startsWith("audio/") ||
                    path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".webm")
                ) {
                    exoManager.setSelectedVideoUri(uri)
                }
                callback?.onReceiveValue(arrayOf(uri))
                return@registerForActivityResult
            }
        }
        callback?.onReceiveValue(null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        exoManager = ExoPlayerManager(this) { jsCode ->
            webViewRef?.evaluateJavascript(jsCode, null)
        }

        setContent {
            val bounds by remember { exoManager.playerBounds }
            val density = LocalDensity.current

            DisposableEffect(Unit) {
                onDispose { exoManager.release() }
            }

            BackHandler {
                val wv = webViewRef
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121212))
                    .systemBarsPadding()
            ) {
                // Слой 1: Нативный Media3 ExoPlayer точно в координатах #playerContainer
                if (bounds.visible && bounds.widthPx > 0 && bounds.heightPx > 0) {
                    val widthDp = with(density) { bounds.widthPx.toDp() }
                    val heightDp = with(density) { bounds.heightPx.toDp() }
                    val cornerRadiusPx = with(density) {
                        if (bounds.isFullscreen) 0f else 16f * this.density
                    }

                    AndroidView(
                        modifier = Modifier
                            .offset { IntOffset(bounds.xPx, bounds.yPx) }
                            .size(widthDp, heightDp),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoManager.exoPlayer
                                useController = false
                                clipToOutline = true
                                outlineProvider = object : ViewOutlineProvider() {
                                    override fun getOutline(view: View, outline: Outline) {
                                        outline.setRoundRect(
                                            0, 0, view.width, view.height, cornerRadiusPx
                                        )
                                    }
                                }
                            }
                        },
                        update = { playerView ->
                            playerView.resizeMode = bounds.resizeMode
                            playerView.invalidateOutline()
                        }
                    )
                }

                // Слой 2: Прозрачный WebView с интерфейсом, караоке-субтитрами и ИИ-чатом
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(AndroidColor.TRANSPARENT)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }

                            addJavascriptInterface(exoManager, "AndroidExo")

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.evaluateJavascript(ExoPlayerManager.JS_BRIDGE_SCRIPT, null)
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                                    this@MainActivity.filePathCallback = filePathCallback

                                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "*/*"
                                    }
                                    fileChooserLauncher.launch(intent)
                                    return true
                                }
                            }

                            loadUrl("file:///android_asset/index.html")
                            webViewRef = this
                        }
                    }
                )
            }
        }
    }
}
