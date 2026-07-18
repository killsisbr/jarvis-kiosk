package com.jarvis.kiosk

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Interface JavaScript exposta ao WebView como window.AndroidPrint.
 *
 * Intercepta chamadas de impressao, emula popups de window.open na sandbox do cliente
 * e fragmenta a transmissao de strings de HTML grandes em pedacos (chunks) pequenos.
 * Isso evita estouros do buffer IPC do Android Binder (limite de 1MB) que crasham
 * o renderizador Chromium do WebView principal (causando tela branca).
 */
class PrintBridge(private val context: Context) {

    private val htmlBuffer = StringBuilder()

    // Debounce anti-duplicata: o mesmo cupom pode chegar por mais de um caminho
    // (auto-print no iframe + print() explicito + fallback popup) em sequencia.
    private var lastPrintHash = 0
    private var lastPrintAt = 0L

    private fun isDuplicate(html: String): Boolean {
        val hash = html.hashCode()
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (hash == lastPrintHash && now - lastPrintAt < 3000) return true
            lastPrintHash = hash
            lastPrintAt = now
            return false
        }
    }

    companion object {
        private const val TAG = "PrintBridge"

        /** Nome da interface JS acessivel via window.AndroidPrint */
        const val JS_INTERFACE_NAME = "AndroidPrint"

        /**
         * Script JS injetado em cada pagina carregada.
         * Intercepta window.print() na pagina principal e em qualquer iframe criado.
         */
        val INJECT_SCRIPT = """
            (function() {
                var tag = '[KioskPrint]';
                
                function sendHtmlInChunks(winContext, targetWidth) {
                    if (!window.AndroidPrint) {
                        console.warn(tag, 'AndroidPrint nao encontrado para envio de chunks');
                        return;
                    }
                    try {
                        var html = winContext.document.documentElement.outerHTML;
                        window.AndroidPrint.startPrintJob();
                        var chunkSize = 200000; // ~200KB chunks (totalmente seguro para o Binder)
                        var offset = 0;
                        while (offset < html.length) {
                            var chunk = html.substring(offset, offset + chunkSize);
                            window.AndroidPrint.writeChunk(chunk);
                            offset += chunkSize;
                        }
                        window.AndroidPrint.endPrintJob(targetWidth || 576);
                    } catch(e) {
                        console.error(tag, 'Erro ao transmitir HTML em chunks:', e);
                    }
                }
                
                function applyOverride(win) {
                    if (!win) return;
                    try {
                        Object.defineProperty(win, 'print', {
                            value: function() {
                                sendHtmlInChunks(win, 576);
                            },
                            writable: true,
                            configurable: true
                        });
                        win.onbeforeprint = null;
                        win.onafterprint = null;
                        console.log(tag, 'Override de print aplicado em:', win.location.href);
                    } catch(e) {
                        console.error(tag, 'Falha ao aplicar override de print:', e);
                    }
                }

                // Aplica na janela principal
                applyOverride(window);

                // Cria um Proxy resiliente recursivo para evitar TypeErrors
                function createResilientProxy(name, targetObj) {
                    return new Proxy(targetObj || {}, {
                        get: function(target, prop) {
                            if (prop === 'then' || prop === 'constructor') return undefined;
                            if (prop in target) {
                                return target[prop];
                            }
                            // Retorna stubs funcionais para metodos de manipulacao comuns de DOM
                            if (prop === 'appendChild' || prop === 'removeChild' || prop === 'createElement' || prop === 'getElementById') {
                                return function() { return createResilientProxy(name + '.' + String(prop)); };
                            }
                            return createResilientProxy(name + '.' + String(prop));
                        },
                        set: function(target, prop, value) {
                            target[prop] = value;
                            return true;
                        }
                    });
                }

                // Mock de window.open para capturar fluxo de criacao de cupons via popups dinâmicos
                try {
                    window.open = function(url, target, features) {
                        console.log(tag, 'window.open interceptado para URL:', url);
                        
                        if (url && url !== 'about:blank' && !url.startsWith('javascript:')) {
                            window.location.href = url;
                            return window;
                        }

                        var docData = {
                            _html: '',
                            open: function() {
                                this._html = '';
                                return this;
                            },
                            write: function(content) {
                                this._html += content;
                            },
                            writeln: function(content) {
                                this._html += content + '\n';
                            },
                            close: function() {
                                console.log(tag, 'document.close() chamado no mock');
                            }
                        };

                        var mockDocument = new Proxy(docData, {
                            get: function(target, prop) {
                                if (prop in target) return target[prop];
                                if (prop === 'body' || prop === 'head') {
                                    return createResilientProxy('document.' + String(prop), {
                                        write: function(c) { target.write(c); },
                                        innerHTML: ''
                                    });
                                }
                                return createResilientProxy('document.' + String(prop));
                            },
                            set: function(target, prop, value) {
                                target[prop] = value;
                                return true;
                            }
                        });

                        var mockWindow = {
                            closed: false,
                            document: mockDocument,
                            print: function() {
                                if (window.AndroidPrint) {
                                    try {
                                        var htmlToPrint = docData._html;
                                        if (htmlToPrint) {
                                            window.AndroidPrint.startPrintJob();
                                            var chunkSize = 200000;
                                            var offset = 0;
                                            while (offset < htmlToPrint.length) {
                                                var chunk = htmlToPrint.substring(offset, offset + chunkSize);
                                                window.AndroidPrint.writeChunk(chunk);
                                                offset += chunkSize;
                                            }
                                            window.AndroidPrint.endPrintJob(576);
                                        } else {
                                            // Fallback para o top window se o mock de escrita estiver vazio
                                            sendHtmlInChunks(window, 576);
                                        }
                                    } catch(e) {
                                        console.error(tag, 'Erro ao transmitir HTML do mockWindow:', e);
                                    }
                                }
                            },
                            close: function() {
                                this.closed = true;
                                docData._html = '';
                                console.log(tag, 'mockWindow.close() chamado');
                            },
                            focus: function() {},
                            blur: function() {}
                        };

                        return new Proxy(mockWindow, {
                            get: function(target, prop) {
                                if (prop in target) return target[prop];
                                return createResilientProxy('window.' + String(prop));
                            }
                        });
                    };
                    console.log(tag, 'Mock de window.open resiliente via Proxy registrado');
                } catch(e) {
                    console.error(tag, 'Erro ao interceptar window.open:', e);
                }

                // Intercepta iframes NO MOMENTO da insercao no DOM (microtask do
                // MutationObserver roda antes do evento load). Esperar o load do
                // elemento e tarde demais: cupons com <script>window.onload=print()</script>
                // disparam o window.print() nativo antes -- e um print de script nao
                // atendido trava o renderer do WebView antigo em modo impressao
                // (tela branca permanente no Android 7).
                function hookIframe(el) {
                    try {
                        if (el.contentWindow) applyOverride(el.contentWindow);
                    } catch(err) {}
                    try {
                        el.addEventListener('load', function() {
                            try {
                                if (el.contentWindow) applyOverride(el.contentWindow);
                            } catch(err) {}
                        });
                    } catch(err) {}
                }

                try {
                    var mo = new MutationObserver(function(muts) {
                        for (var i = 0; i < muts.length; i++) {
                            var nodes = muts[i].addedNodes;
                            for (var j = 0; j < nodes.length; j++) {
                                var n = nodes[j];
                                if (!n.tagName) continue;
                                if (n.tagName === 'IFRAME') {
                                    hookIframe(n);
                                } else if (n.querySelectorAll) {
                                    var frames = n.querySelectorAll('iframe');
                                    for (var k = 0; k < frames.length; k++) hookIframe(frames[k]);
                                }
                            }
                        }
                    });
                    mo.observe(document.documentElement, { childList: true, subtree: true });

                    var existing = document.querySelectorAll('iframe');
                    for (var i = 0; i < existing.length; i++) hookIframe(existing[i]);
                } catch(e) {
                    console.error(tag, 'Erro ao observar iframes:', e);
                }
            })();
        """.trimIndent()
    }

    /**
     * Limpa o buffer de transmissao do HTML para iniciar um novo envio.
     */
    @JavascriptInterface
    fun startPrintJob() {
        synchronized(htmlBuffer) {
            htmlBuffer.setLength(0)
        }
        Log.i(TAG, "startPrintJob: Buffer de transmissao limpo.")
    }

    /**
     * Escreve um fragmento do HTML no buffer.
     */
    @JavascriptInterface
    fun writeChunk(chunk: String) {
        synchronized(htmlBuffer) {
            htmlBuffer.append(chunk)
        }
        Log.d(TAG, "writeChunk: Recebidos ${chunk.length} chars (Acumulado: ${htmlBuffer.length})")
    }

    /**
     * Finaliza a transmissao do HTML e dispara o processamento de impressao.
     */
    @JavascriptInterface
    fun endPrintJob(width: Int) {
        val fullHtml = synchronized(htmlBuffer) {
            val res = htmlBuffer.toString()
            htmlBuffer.setLength(0)
            res
        }
        Log.i(TAG, "endPrintJob: Transmissao concluida. Total: ${fullHtml.length} chars.")
        if (!PrintRouter.isReady()) {
            Log.w(TAG, "Nenhuma impressora pronta no endPrintJob (${PrintRouter.getStatusDetail()})")
            return
        }
        if (isDuplicate(fullHtml)) {
            Log.i(TAG, "endPrintJob: impressao duplicada ignorada (mesmo HTML em <3s)")
            return
        }
        PrintRouter.printHtml(context, fullHtml, if (width > 0) width else 576)
    }

    /**
     * Fallback legado: Recebe o HTML em uma unica string (pode estourar o Binder se > 1MB).
     */
    @JavascriptInterface
    fun printPage(html: String) {
        Log.i(TAG, "printPage() legado chamado. HTML size: ${html.length} chars")
        if (!PrintRouter.isReady()) {
            Log.w(TAG, "Impressora nao pronta. Status: ${PrintRouter.getStatusDetail()}")
            return
        }
        if (isDuplicate(html)) {
            Log.i(TAG, "printPage: impressao duplicada ignorada (mesmo HTML em <3s)")
            return
        }
        PrintRouter.printHtml(context, html, 576)
    }

    /**
     * Fallback legado: Imprime HTML customizado em uma unica string.
     */
    @JavascriptInterface
    fun printHtml(html: String, width: Int) {
        Log.i(TAG, "printHtml() legado chamado. HTML size: ${html.length}, width: ${width}")
        if (!PrintRouter.isReady()) {
            Log.w(TAG, "Impressora nao pronta. Status: ${PrintRouter.getStatusDetail()}")
            return
        }
        if (isDuplicate(html)) {
            Log.i(TAG, "printHtml: impressao duplicada ignorada (mesmo HTML em <3s)")
            return
        }
        PrintRouter.printHtml(context, html, if (width > 0) width else 576)
    }

    @JavascriptInterface
    fun isReady(): Boolean = PrintRouter.isReady()

    @JavascriptInterface
    fun getStatus(): String = PrintRouter.getStatusDetail()

    @JavascriptInterface
    fun printTest(): Boolean = PrintRouter.printTest()

    @JavascriptInterface
    fun rescanUsb() {
        Log.i(TAG, "rescanUsb() chamado via JS")
        PrintRouter.rescanUsb()
    }
}
