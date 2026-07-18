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
         * Intercepta window.print() na pagina principal e em qualquer iframe criado.
         */
        val INJECT_SCRIPT = """
            (function() {
                var tag = '[KioskPrint]';
                
                function applyOverride(win) {
                    if (!win) return;
                    try {
                        Object.defineProperty(win, 'print', {
                            value: function() {
                                if (window.AndroidPrint) {
                                    try {
                                        var html = win.document.documentElement.outerHTML;
                                        window.AndroidPrint.printPage(html);
                                    } catch(e) {
                                        console.error(tag, 'Erro ao capturar HTML:', e);
                                    }
                                } else {
                                    console.warn(tag, 'AndroidPrint nao encontrado');
                                }
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
                                        var finalHtml = docData._html;
                                        if (!finalHtml) {
                                            finalHtml = document.documentElement.outerHTML;
                                        }
                                        window.AndroidPrint.printPage(finalHtml);
                                    } catch(e) {
                                        console.error(tag, 'Erro ao imprimir do mockWindow:', e);
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

                // Intercepta criacao de novos iframes
                try {
                    var orgCreate = document.createElement;
                    document.createElement = function(tagName) {
                        var el = orgCreate.apply(this, arguments);
                        if (el && tagName && tagName.toLowerCase() === 'iframe') {
                            el.addEventListener('load', function() {
                                try {
                                    if (el.contentWindow) {
                                        applyOverride(el.contentWindow);
                                    }
                                } catch(err) {}
                            });
                        }
                        return el;
                    };
                } catch(e) {
                    console.error(tag, 'Erro ao interceptar createElement:', e);
                }
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
