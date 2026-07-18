package com.jarvis.kiosk

import android.annotation.SuppressLint
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

@SuppressLint("SetJavaScriptEnabled")
object KioskWebView {

    /**
     * Configura o WebView para modo kiosk com auto-print integrado.
     *
     * @param webView WebView principal do kiosk
     * @param printBridge Interface JS para impressao silenciosa (pode ser null se impressao desabilitada)
     */
    fun setup(webView: WebView, printBridge: PrintBridge? = null) {
        val settings = webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            userAgentString = userAgentString.replace("; wv", "")
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_AUTO)
        }

        webView.scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        // Registra a interface JS para impressao silenciosa (estilo RawBT)
        if (printBridge != null) {
            webView.addJavascriptInterface(printBridge, PrintBridge.JS_INTERFACE_NAME)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                // Injeta interceptor de window.print() em cada pagina carregada
                if (printBridge != null) {
                    view.evaluateJavascript(PrintBridge.INJECT_SCRIPT, null)
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                android.util.Log.w("KioskWebView", "Processo de renderizacao Chromium caiu (didCrash=${detail.didCrash()}). Recarregando...")
                view.post {
                    view.reload()
                }
                return true // Impede crash da aplicacao
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm()
                return true
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm()
                return true
            }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: android.webkit.JsPromptResult): Boolean {
                result.confirm()
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }
        }
    }
}