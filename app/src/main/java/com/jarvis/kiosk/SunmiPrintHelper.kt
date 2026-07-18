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

import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.SunmiPrinterService

/**
 * Wrapper do SDK Sunmi InnerPrinter.
 * Conecta ao servico de impressao nativo do SO Sunmi (D2 Mini, T2, V2)
 * e fornece impressao silenciosa de HTML via Bitmap -- estilo RawBT integrado.
 *
 * Ciclo de vida: init() no onCreate, destroy() no onDestroy.
 */
object SunmiPrintHelper {

    private const val TAG = "SunmiPrintHelper"

    private var printerService: SunmiPrinterService? = null

    @Volatile
    private var connected = false

    private val callback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiPrinterService) {
            printerService = service
            connected = true
            Log.i(TAG, "Sunmi Printer Service conectado.")
        }

        override fun onDisconnected() {
            connected = false
            printerService = null
            Log.w(TAG, "Sunmi Printer Service desconectado.")
        }
    }

    /** Inicia bind com o servico de impressao nativo do Sunmi. */
    fun init(context: Context) {
        try {
            InnerPrinterManager.getInstance().bindService(context, callback)
        } catch (e: InnerPrinterException) {
            Log.e(TAG, "Falha ao conectar impressora Sunmi: ${e.message}")
        }
    }

    /** Libera bind ao destruir a Activity/Service. */
    fun destroy(context: Context) {
        try {
            InnerPrinterManager.getInstance().unBindService(context, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar impressora: ${e.message}")
        }
    }

    fun isReady(): Boolean = connected && printerService != null

    fun getStatusDetail(): String = when {
        printerService == null -> "sem_servico"
        !connected -> "desconectado"
        else -> "conectado"
    }

    /**
     * Imprime um Bitmap diretamente na termica.
     * Usado pelo printHtml apos renderizar o WebView offscreen.
     */
    fun printBitmap(bitmap: Bitmap) {
        if (!isReady()) {
            Log.e(TAG, "printBitmap: impressora nao pronta (${getStatusDetail()})")
            return
        }
        try {
            printerService?.printBitmap(bitmap, null)
            printerService?.lineWrap(3, null)
            printerService?.cutPaper(null)
            Log.i(TAG, "Bitmap impresso com sucesso (${bitmap.width}x${bitmap.height})")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao imprimir bitmap: ${e.message}", e)
        }
    }

    /**
     * Renderiza HTML completo em um WebView offscreen, gera Bitmap e imprime.
     * Processo inteiramente silencioso -- sem popup, sem dialogo.
     *
     * @param context Context da Activity
     * @param html Conteudo HTML completo (document.documentElement.outerHTML)
     * @param paperWidth Largura em pixels da bobina (384 = 58mm, 576 = 80mm)
     */
    fun printHtml(context: Context, html: String, paperWidth: Int = 576) {
        Handler(Looper.getMainLooper()).post {
            try {
                // Cria o WebView usando o context da Activity e desativa aceleracao de hardware
                // para evitar conflitos na GPU compartilhada que deixam a tela principal branca.
                val offscreenWebView = WebView(context)
                offscreenWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                val targetWidth = if (paperWidth > 0) paperWidth else 576

                offscreenWebView.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    useWideViewPort = false
                    loadWithOverviewMode = false
                }
                offscreenWebView.setInitialScale(100)

                // Injeta CSS para forcar largura da bobina termica
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
                        @media print { 
                            body { width: ${targetWidth}px !important; } 
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

                        // Delay para renderizar CSS/fontes/layouts
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

                                printBitmap(bitmap)

                                Log.i(TAG, "HTML renderizado e impresso (${targetWidth}x${height})")
                            } catch (e: Exception) {
                                Log.e(TAG, "Erro ao converter WebView para Bitmap: ${e.message}", e)
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

    /**
     * Imprime cupom de teste para validar bind do Sunmi.
     */
    fun printTest(): Boolean {
        if (!isReady()) {
            Log.e(TAG, "Teste: impressora nao pronta (${getStatusDetail()})")
            return false
        }
        return try {
            printerService?.printerInit(null)
            printerService?.setAlignment(1, null)
            printerService?.setFontSize(26f, null)
            printerService?.printText("TESTE JARVIS KIOSK\n", null)
            printerService?.setFontSize(20f, null)
            printerService?.printText("Impressora OK\n", null)
            printerService?.printText("Auto-print ativo\n", null)
            printerService?.setAlignment(0, null)
            printerService?.printText("--------------------------------\n", null)
            printerService?.lineWrap(3, null)
            printerService?.cutPaper(null)
            Log.i(TAG, "Cupom de teste impresso.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao imprimir teste: ${e.message}", e)
            false
        }
    }
}
