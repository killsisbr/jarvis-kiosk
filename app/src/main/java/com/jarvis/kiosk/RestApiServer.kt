package com.jarvis.kiosk

import android.webkit.WebView
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class RestApiServer(
    private val webView: WebView,
    private val onNavigateHome: () -> Unit,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val corsHeaders = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers" to "Content-Type"
    )

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
                .also { corsHeaders.forEach { (k, v) -> it.addHeader(k, v) } }
        }

        return when (session.uri) {
            "/status" -> handleStatus(session)
            "/navigate" -> handleNavigate(session)
            "/reload" -> handleReload()
            "/back" -> handleBack()
            "/forward" -> handleForward()
            "/home" -> handleHome()
            else -> json(Response.Status.NOT_FOUND, mapOf("error" to "not found"))
        }
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val data = mapOf(
            "url" to (webView.url ?: ""),
            "title" to (webView.title ?: ""),
            "canGoBack" to webView.canGoBack(),
            "canGoForward" to webView.canGoForward(),
            "progress" to webView.progress
        )
        return json(Response.Status.OK, data)
    }

    private fun handleNavigate(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull() ?: return json(
            Response.Status.BAD_REQUEST, mapOf("error" to "missing url parameter")
        )
        webView.post { webView.loadUrl(url) }
        return json(Response.Status.OK, mapOf("status" to "navigating", "url" to url))
    }

    private fun handleReload(): Response {
        webView.post { webView.reload() }
        return json(Response.Status.OK, mapOf("status" to "reloading"))
    }

    private fun handleBack(): Response {
        if (webView.canGoBack()) webView.post { webView.goBack() }
        return json(Response.Status.OK, mapOf("status" to "ok"))
    }

    private fun handleForward(): Response {
        if (webView.canGoForward()) webView.post { webView.goForward() }
        return json(Response.Status.OK, mapOf("status" to "ok"))
    }

    private fun handleHome(): Response {
        onNavigateHome()
        return json(Response.Status.OK, mapOf("status" to "returning home"))
    }

    private fun json(status: Response.Status, data: Map<String, Any?>): Response {
        val json = JSONObject(data).toString()
        return newFixedLengthResponse(status, "application/json", json)
            .also { corsHeaders.forEach { (k, v) -> it.addHeader(k, v) } }
    }
}