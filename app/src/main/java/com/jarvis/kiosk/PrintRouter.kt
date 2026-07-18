package com.jarvis.kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Roteador de impressao. Tenta imprimir na ordem:
 *   1. Sunmi interna (SDK nativo)
 *   2. USB externa (Epson / qualquer ESC/POS)
 *
 * Centraliza toda logica de impressao -- os demais modulos (PrintBridge, RestApiServer)
 * chamam apenas o PrintRouter, sem saber qual impressora esta ativa.
 */
object PrintRouter {

    private const val TAG = "PrintRouter"

    private var usbPrinter: UsbPrinterManager? = null

    /**
     * Inicializa ambas as vias de impressao.
     * Chamar no onCreate da Activity.
     */
    fun init(context: Context) {
        // Via 1: Sunmi interna
        SunmiPrintHelper.init(context)

        // Via 2: USB externa (Epson, generica)
        usbPrinter = UsbPrinterManager(context).also { it.init() }

        Log.i(TAG, "PrintRouter inicializado. Sunmi + USB Host ativo.")
    }

    /**
     * Libera recursos de ambas as vias.
     * Chamar no onDestroy da Activity.
     */
    fun destroy(context: Context) {
        SunmiPrintHelper.destroy(context)
        usbPrinter?.destroy()
        usbPrinter = null
    }

    /**
     * Verifica se alguma impressora esta pronta (Sunmi ou USB).
     */
    fun isReady(): Boolean = SunmiPrintHelper.isReady() || (usbPrinter?.isReady() == true)

    /**
     * Status detalhado de todas as impressoras.
     */
    fun getStatusDetail(): String {
        val sunmi = "sunmi=${SunmiPrintHelper.getStatusDetail()}"
        val usb = "usb=${usbPrinter?.getStatusDetail() ?: "nao_inicializado"}"
        val active = when {
            SunmiPrintHelper.isReady() -> "ativa=sunmi"
            usbPrinter?.isReady() == true -> "ativa=usb"
            else -> "ativa=nenhuma"
        }
        return "$active | $sunmi | $usb"
    }

    /**
     * Imprime Bitmap na impressora disponivel.
     * Prioridade: Sunmi > USB
     */
    fun printBitmap(bitmap: Bitmap): Boolean {
        // Tenta Sunmi primeiro
        if (SunmiPrintHelper.isReady()) {
            SunmiPrintHelper.printBitmap(bitmap)
            return true
        }

        // Fallback: USB
        if (usbPrinter?.isReady() == true) {
            return usbPrinter!!.printBitmap(bitmap)
        }

        Log.e(TAG, "Nenhuma impressora disponivel para printBitmap")
        return false
    }

    /**
     * Renderiza HTML em WebView offscreen -> Bitmap -> imprime.
     * Processo silencioso, sem popup.
     */
    fun printHtml(context: Context, html: String, paperWidth: Int = 576) {
        // Se Sunmi esta pronta, usa o pipeline dela (que ja faz WebView -> Bitmap -> Sunmi SDK)
        if (SunmiPrintHelper.isReady()) {
            SunmiPrintHelper.printHtml(context, html, paperWidth)
            return
        }

        // Se USB esta pronta, faz o pipeline WebView -> Bitmap -> USB ESC/POS
        if (usbPrinter?.isReady() == true) {
            renderHtmlToBitmap(context, html, paperWidth) { bitmap ->
                usbPrinter?.printBitmap(bitmap)
            }
            return
        }

        Log.e(TAG, "Nenhuma impressora disponivel para printHtml")
    }

    /**
     * Imprime cupom de teste na impressora disponivel.
     */
    fun printTest(): Boolean {
        if (SunmiPrintHelper.isReady()) {
            return SunmiPrintHelper.printTest()
        }
        if (usbPrinter?.isReady() == true) {
            return usbPrinter!!.printTest()
        }
        Log.e(TAG, "Nenhuma impressora disponivel para teste")
        return false
    }

    /**
     * Forca rescan de impressoras USB.
     * Util se a impressora foi conectada apos o boot do app.
     */
    fun rescanUsb() {
        usbPrinter?.scanAndConnect()
    }

    // --- Renderizacao HTML -> Bitmap (para USB) ---

    private fun renderHtmlToBitmap(
        context: Context,
        html: String,
        paperWidth: Int,
        onBitmapReady: (Bitmap) -> Unit
    ) {
        Handler(Looper.getMainLooper()).post {
            try {
                val offscreenWebView = WebView(context)
                val targetWidth = if (paperWidth > 0) paperWidth else 576

                offscreenWebView.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    useWideViewPort = false
                    loadWithOverviewMode = false
                }
                offscreenWebView.setInitialScale(100)

                val wrappedHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=${targetWidth}">
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        html, body { 
                            width: ${targetWidth}px !important; 
                            max-width: ${targetWidth}px !important;
                            overflow-x: hidden;
                        }
                    </style>
                    </head>
                    <body>$html</body>
                    </html>
                """.trimIndent()

                offscreenWebView.loadDataWithBaseURL(null, wrappedHtml, "text/html", "utf-8", null)

                offscreenWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                view.measure(
                                    View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                                )
                                var height = view.measuredHeight
                                if (height <= 0) height = 800

                                view.layout(0, 0, targetWidth, height)

                                val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bitmap)
                                view.draw(canvas)

                                // Callback com bitmap pronto
                                Thread { onBitmapReady(bitmap) }.start()

                                Log.i(TAG, "HTML renderizado para USB (${targetWidth}x${height})")
                            } catch (e: Exception) {
                                Log.e(TAG, "Erro ao renderizar HTML para bitmap: ${e.message}", e)
                            } finally {
                                try {
                                    view.destroy()
                                } catch (ex: Exception) {
                                    Log.e(TAG, "Erro ao destruir WebView offscreen: ${ex.message}")
                                }
                            }
                        }, 500)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao instanciar WebView offscreen: ${e.message}", e)
            }
        }
    }
}
