package com.jarvis.kiosk

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
            // Precisa da Activity para anexar a WebView offscreen a decorView --
            // sem isso, onAttachedToWindow() nunca dispara e o measure/layout/draw
            // roda em estado indefinido no WebKit antigo do Android 7.1.2 (API 25),
            // podendo derrubar o processo de renderizacao compartilhado com a WebView principal.
            val activity = context as? Activity
            val decorView = activity?.window?.decorView as? ViewGroup
            if (decorView == null) {
                Log.e(TAG, "renderHtmlToBitmap: context nao e uma Activity, nao foi possivel anexar WebView offscreen")
                return@post
            }

            try {
                val offscreenWebView = WebView(context)
                offscreenWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                val targetWidth = if (paperWidth > 0) paperWidth else 576

                val density = context.resources.displayMetrics.density
                val physicalWidth = (targetWidth * density).toInt()

                offscreenWebView.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                offscreenWebView.setInitialScale(100)

                fun cleanup() {
                    try {
                        decorView.removeView(offscreenWebView)
                    } catch (ex: Exception) {
                        Log.e(TAG, "Erro ao remover WebView offscreen da decorView: ${ex.message}")
                    }
                    try {
                        offscreenWebView.destroy()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Erro ao destruir WebView offscreen: ${ex.message}")
                    }
                }

                // Anexa a WebView com a largura fisica real (invisivel) a decorView ANTES de carregar o
                // conteudo, para que o motor do Chromium do Android calcule o flexbox e fontes
                // com as dimensoes reais corretas, sem truncamentos de layout de 1x1.
                val layoutParams = ViewGroup.LayoutParams(physicalWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                decorView.addView(offscreenWebView, layoutParams)
                offscreenWebView.visibility = View.INVISIBLE

                // Registra o client ANTES de chamar loadDataWithBaseURL: caso contrario
                // o carregamento pode terminar antes do listener existir e onPageFinished
                // nunca dispara -- e a impressao nunca acontece.
                offscreenWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                view.measure(
                                    View.MeasureSpec.makeMeasureSpec(physicalWidth, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                                )
                                var physicalHeight = view.measuredHeight
                                if (physicalHeight <= 0) physicalHeight = (800 * density).toInt()

                                view.layout(0, 0, physicalWidth, physicalHeight)

                                val originalBitmap = Bitmap.createBitmap(physicalWidth, physicalHeight, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(originalBitmap)
                                view.draw(canvas)

                                // Redimensiona o bitmap de alta resolucao para a largura util da impressora
                                val targetHeight = (physicalHeight / density).toInt()
                                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)

                                // Callback com bitmap pronto
                                Thread { onBitmapReady(scaledBitmap) }.start()

                                Log.i(TAG, "HTML renderizado via supersampling para USB (${targetWidth}x${targetHeight})")
                            } catch (e: Exception) {
                                Log.e(TAG, "Erro ao renderizar HTML para bitmap: ${e.message}", e)
                            } finally {
                                cleanup()
                            }
                        }, 500)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        errorCode: Int,
                        description: String,
                        failingUrl: String
                    ) {
                        Log.e(TAG, "Erro ao carregar HTML offscreen (USB): $description ($errorCode)")
                        cleanup()
                    }
                }

                // Cupons do PDV ja chegam como documento completo (<!DOCTYPE html>...);
                // re-envelopar aninharia <html> dentro de <body>. Injetamos a viewport
                // e estilos de reset no Head de documentos completos para forcar a bobina termica.
                val trimmed = html.trimStart()
                val isFullDocument = trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
                    trimmed.startsWith("<html", ignoreCase = true)

                val injection = """
                    <meta name="viewport" content="width=$targetWidth, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <style>
                        html, body {
                            width: ${targetWidth}px !important;
                            max-width: ${targetWidth}px !important;
                            overflow-x: hidden !important;
                            margin: 0 !important;
                            padding: 0 !important;
                        }
                        * {
                            box-sizing: border-box !important;
                            max-width: 100% !important;
                        }
                    </style>
                """.trimIndent()

                val wrappedHtml = if (isFullDocument) {
                    if (html.contains("</head>", ignoreCase = true)) {
                        html.replace("</head>", "$injection</head>", ignoreCase = true)
                    } else if (html.contains("<body>", ignoreCase = true)) {
                        html.replace("<body>", "<head>$injection</head><body>", ignoreCase = true)
                    } else {
                        injection + html
                    }
                } else {
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                    <meta charset="utf-8">
                    $injection
                    </head>
                    <body>$html</body>
                    </html>
                    """.trimIndent()
                }

                offscreenWebView.loadDataWithBaseURL(null, wrappedHtml, "text/html", "utf-8", null)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao instanciar WebView offscreen: ${e.message}", e)
            }
        }
    }
}
