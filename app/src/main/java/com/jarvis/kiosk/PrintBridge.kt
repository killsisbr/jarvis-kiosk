package com.jarvis.kiosk

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Interface JavaScript exposta ao WebView como window.AndroidPrint.
 *
 * Quando a pagina web chama window.print(), o JS injetado redireciona
 * para window.AndroidPrint.printPage(html) que chega aqui e dispara
 * a impressao silenciosa via PrintRouter (Sunmi ou USB Epson).
 *
 * Estilo RawBT -- sem popup, sem dialogo, impressao direta.
 */
class PrintBridge(private val context: Context) {

    companion object {
        private const val TAG = "PrintBridge"

        /** Nome da interface JS acessivel via window.AndroidPrint */
        const val JS_INTERFACE_NAME = "AndroidPrint"

        /**
         * Script JS injetado em cada pagina carregada.
         * Intercepta window.print() e envia o HTML para o Android.
         */
        val INJECT_SCRIPT = """
            (function() {
                // Override do window.print() -- chamadas silenciosas
                window.print = function() {
                    if (window.AndroidPrint) {
                        try {
                            var html = document.documentElement.outerHTML;
                            window.AndroidPrint.printPage(html);
                        } catch(e) {
                            console.error('[KioskPrint] Erro ao capturar HTML:', e);
                        }
                    }
                };
                
                // Nulifica handlers de print events para evitar popups
                window.onbeforeprint = null;
                window.onafterprint = null;
                
                console.log('[KioskPrint] Auto-print interceptor ativo');
            })();
        """.trimIndent()
    }

    /**
     * Chamado pelo JS quando window.print() e invocado.
     * Recebe o HTML completo da pagina e envia para impressao silenciosa.
     */
    @JavascriptInterface
    fun printPage(html: String) {
        Log.i(TAG, "printPage() chamado. HTML size: ${html.length} chars")
        if (!PrintRouter.isReady()) {
            Log.w(TAG, "Nenhuma impressora pronta. Status: ${PrintRouter.getStatusDetail()}")
            return
        }
        PrintRouter.printHtml(context, html, 576)
    }

    /**
     * Imprime HTML customizado enviado diretamente pelo JS.
     * Util para imprimir apenas uma secao (ex: um cupom especifico).
     *
     * JS: window.AndroidPrint.printHtml('<div>...</div>', 576)
     */
    @JavascriptInterface
    fun printHtml(html: String, width: Int) {
        Log.i(TAG, "printHtml() chamado. HTML size: ${html.length}, width: ${width}")
        if (!PrintRouter.isReady()) {
            Log.w(TAG, "Nenhuma impressora pronta. Status: ${PrintRouter.getStatusDetail()}")
            return
        }
        PrintRouter.printHtml(context, html, if (width > 0) width else 576)
    }

    /**
     * Verifica se alguma impressora esta conectada (Sunmi ou USB).
     * JS: window.AndroidPrint.isReady()
     */
    @JavascriptInterface
    fun isReady(): Boolean = PrintRouter.isReady()

    /**
     * Status detalhado das impressoras (Sunmi + USB).
     * JS: window.AndroidPrint.getStatus()
     */
    @JavascriptInterface
    fun getStatus(): String = PrintRouter.getStatusDetail()

    /**
     * Imprime cupom de teste na impressora disponivel.
     * JS: window.AndroidPrint.printTest()
     */
    @JavascriptInterface
    fun printTest(): Boolean = PrintRouter.printTest()

    /**
     * Forca rescan de impressoras USB (hot-plug).
     * JS: window.AndroidPrint.rescanUsb()
     */
    @JavascriptInterface
    fun rescanUsb() {
        Log.i(TAG, "rescanUsb() chamado via JS")
        PrintRouter.rescanUsb()
    }
}
