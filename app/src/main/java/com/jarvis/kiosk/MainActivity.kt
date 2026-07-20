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
    private var backgroundWebView: WebView? = null
    private lateinit var container: FrameLayout
    private val restServers = mutableListOf<RestApiServer>()
    private var baseUrl: String = ""
    private lateinit var printBridge: PrintBridge
    private var isQuadroLoaded = false

    companion object {
        private const val PREFS_NAME = "kiosk_prefs"
        private const val KEY_URL = "base_url"
        // 8080: porta que o PDV web chama em http://127.0.0.1:8080/print
        // 8081: porta documentada na pagina de download do kiosk
        private val REST_PORTS = intArrayOf(8080, 8081)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Habilita desenho offscreen de documento completo de forma estatica antes de instanciar qualquer WebView.
        // Chamar isso apos criar WebViews causa tela branca e comportamento indefinido no WebKit.
        WebView.enableSlowWholeDocumentDraw()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // Inicializa impressoras: Sunmi interna + USB externa (Epson)
        PrintRouter.init(this)

        // Verifica se ha atualizacoes OTA disponiveis no GitHub Releases
        AutoUpdateManager.check(this)

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
        restServers.forEach { it.stop() }
        restServers.clear()
        PrintRouter.destroy(this)
        backgroundWebView?.let {
            it.stopLoading()
            it.destroy()
        }
        backgroundWebView = null
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
        backgroundWebView = WebView(this)
        isQuadroLoaded = false

        container = FrameLayout(this)
        
        // Adiciona a backgroundWebView em segundo plano (com tamanho 1x1) para nao suspender o JS
        container.addView(backgroundWebView, FrameLayout.LayoutParams(1, 1))
        
        container.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(container)

        // Setup da WebView oculta (com a mesma bridge de impressao)
        backgroundWebView?.let { KioskWebView.setup(it, printBridge) }

        // Setup da WebView principal com callback para recarregar o quadro em background apos o login
        KioskWebView.setup(webView, printBridge) { pageUrl ->
            checkAndLoadBackgroundQuadro(pageUrl)
        }
        webView.loadUrl(url)

        startRestServer()
    }

    private fun checkAndLoadBackgroundQuadro(currentUrl: String) {
        if (currentUrl.contains("/admin") && !currentUrl.contains("/login") && !isQuadroLoaded) {
            val cleanUrl = currentUrl.split("?").first()
            val quadroUrl = when {
                cleanUrl.endsWith("/admin") -> "$cleanUrl/quadro.html"
                cleanUrl.endsWith("/admin/") -> "${cleanUrl}quadro.html"
                cleanUrl.contains("/index.html") -> cleanUrl.replace("/index.html", "/quadro.html")
                cleanUrl.contains("/caixa.html") -> cleanUrl.replace("/caixa.html", "/quadro.html")
                cleanUrl.endsWith("/public/admin") -> "$cleanUrl/quadro.html"
                cleanUrl.endsWith("/public/admin/") -> "${cleanUrl}quadro.html"
                else -> {
                    val idx = cleanUrl.indexOf("/admin")
                    if (idx != -1) {
                        cleanUrl.substring(0, idx + 6) + "/quadro.html"
                    } else {
                        null
                    }
                }
            }

            if (quadroUrl != null) {
                android.util.Log.i("MainActivity", "Iniciando WebView de background para Quadro: $quadroUrl")
                backgroundWebView?.post {
                    backgroundWebView?.loadUrl(quadroUrl)
                    isQuadroLoaded = true
                }
            }
        } else if (currentUrl.contains("/login")) {
            isQuadroLoaded = false
        }
    }

    private fun startRestServer() {
        for (port in REST_PORTS) {
            try {
                val server = RestApiServer(webView, { navigateHome() }, port)
                server.start()
                restServers.add(server)
            } catch (e: IOException) {
                // porta ocupada ou erro de rede — tenta a proxima; kiosk continua funcionando
            }
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