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
import java.util.ArrayDeque

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

    // Fila de impressao serial -- evita instanciar varios WebViews offscreen
    // simultaneamente (o D2 Mini tem RAM limitada; renderers concorrentes
    // derrubam o processo de renderizacao compartilhado com a WebView principal).
    private data class PrintJob(val context: Context, val html: String, val paperWidth: Int)

    private val jobQueue = ArrayDeque<PrintJob>()

    @Volatile
    private var printing = false

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
            // Deteccao dinamica da largura util da impressora fisica da Sunmi (1 = 80mm, 2 = 58mm)
            val resolvedPaperWidth = if (isReady()) {
                val paperType = try {
                    printerService?.getPrinterPaper()
                } catch (e: Exception) {
                    null
                }
                when (paperType) {
                    0 -> 576  // 0 = 80mm no SDK Sunmi
                    1 -> 384  // 1 = 58mm no SDK Sunmi
                    else -> if (paperWidth > 0) paperWidth else 384
                }
            } else {
                paperWidth
            }
            Log.i(TAG, "printHtml: paperWidth recebido=$paperWidth, resolvido=$resolvedPaperWidth")
            jobQueue.add(PrintJob(context, html, resolvedPaperWidth))
            processNextJob()
        }
    }

    private fun processNextJob() {
        if (printing) return
        val job = jobQueue.poll() ?: return
        printing = true
        renderAndPrint(job.context, job.html, job.paperWidth) {
            printing = false
            processNextJob()
        }
    }

    private fun renderAndPrint(context: Context, html: String, paperWidth: Int, onDone: () -> Unit) {
        // Precisa da Activity para anexar a WebView offscreen a decorView --
        // sem isso, onAttachedToWindow() nunca dispara e o measure/layout/draw
        // roda em estado indefinido no WebKit antigo do Android 7.1.2 (API 25),
        // derrubando o processo de renderizacao compartilhado (tela branca no kiosk).
        val activity = context as? Activity
        val decorView = activity?.window?.decorView as? ViewGroup
        if (decorView == null) {
            Log.e(TAG, "printHtml: context nao e uma Activity, nao foi possivel anexar WebView offscreen")
            onDone()
            return
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
                onDone()
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

                    // Delay para renderizar CSS/fontes/layouts
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

                            printBitmap(scaledBitmap)

                            Log.i(TAG, "HTML renderizado via supersampling e impresso (${targetWidth}x${targetHeight})")
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao converter WebView para Bitmap: ${e.message}", e)
                        } finally {
                            cleanup()
                        }
                    }, 1200)
                }

                override fun onReceivedError(
                    view: WebView,
                    errorCode: Int,
                    description: String,
                    failingUrl: String
                ) {
                    Log.e(TAG, "Erro ao carregar HTML offscreen: $description ($errorCode)")
                    cleanup()
                }
            }

            // Cupons do PDV ja chegam como documento completo (<!DOCTYPE html>...);
            // re-envelopar aninharia <html> dentro de <body>. Injetamos apenas a viewport
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
            onDone()
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
