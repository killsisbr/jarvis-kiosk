package com.jarvis.kiosk

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private var restServer: RestApiServer? = null
    private var baseUrl: String = ""
    private lateinit var printBridge: PrintBridge

    companion object {
        private const val PREFS_NAME = "kiosk_prefs"
        private const val KEY_URL = "base_url"
        private const val REST_PORT = 8081
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // Inicializa impressoras: Sunmi interna + USB externa (Epson)
        PrintRouter.init(this)

        // Bridge JS para interceptar window.print()
        printBridge = PrintBridge(this)

        baseUrl = getSavedUrl()

        if (baseUrl.isEmpty()) {
            showConfigScreen()
        } else {
            initKiosk(baseUrl)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        restServer?.stop()
        PrintRouter.destroy(this)
        super.onDestroy()
    }

    // --- Config Screen ---

    private fun showConfigScreen() {
        container = FrameLayout(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 128, 64, 64)
        }

        val urlInput = EditText(this).apply {
            hint = getString(com.jarvis.kiosk.R.string.url_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            textSize = 18f
        }

        val btnStart = Button(this).apply {
            text = getString(com.jarvis.kiosk.R.string.btn_start)
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    saveUrl(url)
                    initKiosk(url)
                }
            }
        }

        layout.addView(urlInput)
        layout.addView(btnStart)
        container.addView(layout)
        setContentView(container)
    }

    // --- Kiosk Mode ---

    private fun initKiosk(url: String) {
        baseUrl = url
        webView = WebView(this)
        container = FrameLayout(this)
        container.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(container)

        // Setup com PrintBridge integrado -- intercepta window.print() silenciosamente
        KioskWebView.setup(webView, printBridge)
        webView.loadUrl(url)

        startRestServer()
    }

    private fun startRestServer() {
        try {
            val server = RestApiServer(webView, { navigateHome() }, REST_PORT)
            server.start()
            restServer = server
        } catch (e: IOException) {
            // porta ocupada ou erro de rede — kiosk continua funcionando
        }
    }

    private fun navigateHome() {
        webView.post { webView.loadUrl(baseUrl) }
    }

    // --- System UI ---

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
    }

    // --- Persistence ---

    private fun getSavedUrl(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_URL, "https://killsis.com/admin") ?: "https://killsis.com/admin"
    }

    private fun saveUrl(url: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, url)
            .apply()
    }
}