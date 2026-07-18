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
            "/health" -> handleHealth()
            "/print" -> handlePrint(session)
            "/test" -> handleTest()
            else -> json(Response.Status.NOT_FOUND, mapOf("error" to "not found"))
        }
    }

    private fun handleHealth(): Response = json(
        Response.Status.OK,
        mapOf("status" to "ok", "app" to "jarvis-kiosk", "printer" to PrintRouter.getStatusDetail())
    )

    private fun handleTest(): Response = json(
        Response.Status.OK,
        mapOf("printed" to PrintRouter.printTest(), "printer" to PrintRouter.getStatusDetail())
    )

    /**
     * POST /print -- payload JSON do PDV:
     *   modo GRAPHIC: { "html": "<!DOCTYPE...", "width": 576 }
     *   modo TEXT:    { tenant_name, order_number, items: [...], total, ... }
     * O modo TEXT e convertido em HTML simples e segue o mesmo pipeline de bitmap.
     */
    private fun handlePrint(session: IHTTPSession): Response {
        if (!PrintRouter.isReady()) {
            return json(
                Response.Status.SERVICE_UNAVAILABLE,
                mapOf("error" to "printer_not_ready", "detail" to PrintRouter.getStatusDetail())
            )
        }

        val body = try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: Exception) {
            return json(Response.Status.BAD_REQUEST, mapOf("error" to "body_parse_failed", "detail" to e.message))
        }
        if (body.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, mapOf("error" to "empty_body"))
        }

        return try {
            val payload = JSONObject(body)
            val width = when {
                payload.has("width") -> payload.optInt("width", 576)
                payload.optInt("paper_width", 80) == 58 -> 384
                else -> 576
            }
            val html = payload.optString("html", "")
            val content = if (html.isNotEmpty()) html else buildTextReceiptHtml(payload)
            PrintRouter.printHtml(webView.context, content, width)
            json(Response.Status.OK, mapOf("status" to "printing", "width" to width))
        } catch (e: Exception) {
            json(Response.Status.BAD_REQUEST, mapOf("error" to "invalid_payload", "detail" to e.message))
        }
    }

    /** Converte o payload TEXT do PDV em um cupom HTML simples (monoespacado). */
    private fun buildTextReceiptHtml(o: JSONObject): String {
        fun money(v: Double) = "R$ %.2f".format(v)
        val sb = StringBuilder()
        sb.append("<div style=\"font-family:'Courier New',monospace;font-weight:bold;color:#000;font-size:18px\">")
        sb.append("<div style='text-align:center;font-size:22px'>${o.optString("tenant_name", "RESTAURANTE")}</div>")
        sb.append("<div style='text-align:center;font-size:30px'>PEDIDO #${o.optString("order_number")}</div>")
        if (o.optBoolean("is_reprint")) {
            sb.append("<div style='text-align:center;border:3px solid #000;font-size:20px;padding:4px'>REIMPRESSO</div>")
        }
        sb.append("<hr style='border:1px dashed #000'>")
        o.optString("customer_name").takeIf { it.isNotEmpty() }?.let { sb.append("<div>Cliente: $it</div>") }
        o.optString("customer_phone").takeIf { it.isNotEmpty() }?.let { sb.append("<div>Tel: $it</div>") }
        o.optString("table_number").takeIf { it.isNotEmpty() }?.let { sb.append("<div style='font-size:24px'>MESA: $it</div>") }
        o.optString("delivery_address").takeIf { it.isNotEmpty() }?.let { sb.append("<div>End: $it</div>") }
        sb.append("<hr style='border:1px dashed #000'>")
        val items = o.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val qty = item.optInt("quantity", 1)
                val price = item.optDouble("price", 0.0)
                sb.append("<div style='overflow:hidden'>${qty}x ${item.optString("name")}" +
                    "<span style='float:right'>${money(price * qty)}</span></div>")
                val addons = item.optJSONArray("addons")
                if (addons != null) {
                    for (j in 0 until addons.length()) {
                        sb.append("<div style='padding-left:14px;font-size:15px'>+ ${addons.optString(j)}</div>")
                    }
                }
            }
        }
        sb.append("<hr style='border:1px dashed #000'>")
        o.optDouble("delivery_fee", 0.0).takeIf { it > 0 }?.let {
            sb.append("<div style='overflow:hidden'>Entrega:<span style='float:right'>${money(it)}</span></div>")
        }
        sb.append("<div style='overflow:hidden;font-size:24px'>TOTAL:<span style='float:right'>${money(o.optDouble("total", 0.0))}</span></div>")
        o.optString("payment_method").takeIf { it.isNotEmpty() }?.let { sb.append("<div>Pagamento: $it</div>") }
        o.optDouble("payment_change", 0.0).takeIf { it > 0 }?.let { sb.append("<div>Troco: ${money(it)}</div>") }
        o.optString("observation").takeIf { it.isNotEmpty() }?.let { sb.append("<div>Obs: $it</div>") }
        sb.append("</div>")
        return sb.toString()
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