package com.jarvis.kiosk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Gerenciador de impressoras USB via Android USB Host API.
 * Compativel com Epson (TM-T20, TM-T88, TM-m30) e qualquer impressora termica ESC/POS via USB.
 *
 * Fluxo: detecta dispositivo USB classe Printer (7) -> pede permissao -> abre conexao -> envia ESC/POS.
 *
 * Nao depende de SDK proprietario -- funciona com qualquer impressora ESC/POS.
 */
class UsbPrinterManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbPrinterManager"
        private const val ACTION_USB_PERMISSION = "com.jarvis.kiosk.USB_PERMISSION"
        private const val TIMEOUT_MS = 5000

        // ESC/POS Commands
        private val ESC_INIT = byteArrayOf(0x1B, 0x40)                     // ESC @ -- Initialize
        private val ESC_ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)       // ESC a 1
        private val ESC_ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)         // ESC a 0
        private val ESC_FEED_LINES = byteArrayOf(0x1B, 0x64, 0x03)         // ESC d 3 -- feed 3 lines
        private val GS_CUT_PAPER = byteArrayOf(0x1D, 0x56, 0x00)           // GS V 0 -- full cut
        private val GS_CUT_PAPER_PARTIAL = byteArrayOf(0x1D, 0x56, 0x01)   // GS V 1 -- partial cut
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbEndpointOut: UsbEndpoint? = null
    private var usbInterface: UsbInterface? = null

    @Volatile
    private var connected = false

    @Volatile
    private var permissionGranted = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this@UsbPrinterManager) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            permissionGranted = true
                            device?.let { openConnection(it) }
                            Log.i(TAG, "Permissao USB concedida para: ${device?.deviceName}")
                        } else {
                            Log.w(TAG, "Permissao USB negada para: ${device?.deviceName}")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "Dispositivo USB conectado. Rescanning...")
                    scanAndConnect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device == usbDevice) {
                        disconnect()
                        Log.w(TAG, "Impressora USB desconectada: ${device?.deviceName}")
                    }
                }
            }
        }
    }

    /**
     * Inicializa o gerenciador: registra receivers e faz scan inicial.
     * Chamar no onCreate da Activity.
     */
    fun init() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        scanAndConnect()
    }

    /**
     * Libera recursos. Chamar no onDestroy da Activity.
     */
    fun destroy() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        disconnect()
    }

    fun isReady(): Boolean = connected && usbConnection != null && usbEndpointOut != null

    fun getStatusDetail(): String = when {
        usbDevice == null -> "nenhuma_impressora_usb"
        !permissionGranted -> "aguardando_permissao"
        !connected -> "desconectada"
        else -> "conectada_usb (${usbDevice?.productName ?: usbDevice?.deviceName})"
    }

    /**
     * Escaneia dispositivos USB conectados procurando impressoras (classe 7).
     * Se encontrar, pede permissao e conecta.
     */
    fun scanAndConnect() {
        val deviceList = usbManager.deviceList
        Log.i(TAG, "Scan USB: ${deviceList.size} dispositivos encontrados")

        for ((_, device) in deviceList) {
            if (isPrinterDevice(device)) {
                Log.i(TAG, "Impressora USB encontrada: ${device.productName ?: device.deviceName} " +
                        "(VID:${device.vendorId} PID:${device.productId})")
                usbDevice = device

                if (usbManager.hasPermission(device)) {
                    permissionGranted = true
                    openConnection(device)
                } else {
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pi = PendingIntent.getBroadcast(context, 0,
                        Intent(ACTION_USB_PERMISSION), flags)
                    usbManager.requestPermission(device, pi)
                    Log.i(TAG, "Permissao USB solicitada para: ${device.deviceName}")
                }
                return
            }
        }
        Log.i(TAG, "Nenhuma impressora USB encontrada no scan.")
    }

    /**
     * Verifica se o dispositivo USB e uma impressora.
     * Checa a classe da interface (7 = Printer) ou vendor IDs conhecidos da Epson.
     */
    private fun isPrinterDevice(device: UsbDevice): Boolean {
        // Epson Vendor IDs conhecidos
        val epsonVendorIds = setOf(0x04B8)

        // Verifica por classe de interface (7 = Printer)
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                return true
            }
        }

        // Fallback: verifica por vendor ID da Epson
        if (device.vendorId in epsonVendorIds) {
            return true
        }

        return false
    }

    /**
     * Abre conexao USB com o dispositivo e localiza o endpoint Bulk OUT.
     */
    private fun openConnection(device: UsbDevice) {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // Procura interface de impressora ou bulk endpoints
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT) {

                    val conn = usbManager.openDevice(device)
                    if (conn != null && conn.claimInterface(iface, true)) {
                        usbConnection = conn
                        usbEndpointOut = endpoint
                        usbInterface = iface
                        connected = true
                        Log.i(TAG, "Conexao USB aberta. Endpoint OUT: ${endpoint.address}, " +
                                "MaxPacket: ${endpoint.maxPacketSize}")
                        return
                    } else {
                        Log.e(TAG, "Falha ao abrir conexao ou claim interface USB")
                    }
                }
            }
        }
        Log.e(TAG, "Nenhum endpoint Bulk OUT encontrado no dispositivo USB")
    }

    private fun disconnect() {
        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (_: Exception) {}
        usbConnection = null
        usbEndpointOut = null
        usbInterface = null
        connected = false
    }

    // --- Envio de dados ESC/POS ---

    /**
     * Envia bytes raw para a impressora USB.
     */
    private fun sendData(data: ByteArray): Boolean {
        val conn = usbConnection ?: return false
        val ep = usbEndpointOut ?: return false

        // Envia em chunks do tamanho maximo do endpoint
        val chunkSize = ep.maxPacketSize.coerceAtLeast(64)
        var offset = 0
        while (offset < data.size) {
            val length = minOf(chunkSize, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + length)
            val sent = conn.bulkTransfer(ep, chunk, chunk.size, TIMEOUT_MS)
            if (sent < 0) {
                Log.e(TAG, "Erro ao enviar dados USB no offset $offset")
                return false
            }
            offset += length
        }
        return true
    }

    /**
     * Imprime um Bitmap na impressora USB via ESC/POS raster image (GS v 0).
     * Converte o bitmap para monocromatico e envia como raster data.
     */
    fun printBitmap(bitmap: Bitmap): Boolean {
        if (!isReady()) {
            Log.e(TAG, "printBitmap: impressora USB nao pronta")
            return false
        }

        return try {
            // Inicializa impressora
            sendData(ESC_INIT)

            // Converte bitmap para ESC/POS raster
            val rasterData = bitmapToEscPosRaster(bitmap)
            sendData(rasterData)

            // Feed + Corte
            sendData(ESC_FEED_LINES)
            sendData(GS_CUT_PAPER_PARTIAL)

            Log.i(TAG, "Bitmap impresso via USB (${bitmap.width}x${bitmap.height})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao imprimir bitmap via USB: ${e.message}", e)
            false
        }
    }

    /**
     * Imprime texto simples via ESC/POS.
     */
    fun printText(text: String): Boolean {
        if (!isReady()) return false
        return try {
            sendData(ESC_INIT)
            sendData(text.toByteArray(Charsets.UTF_8))
            sendData(ESC_FEED_LINES)
            sendData(GS_CUT_PAPER_PARTIAL)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao imprimir texto via USB: ${e.message}", e)
            false
        }
    }

    /**
     * Cupom de teste via USB ESC/POS.
     */
    fun printTest(): Boolean {
        if (!isReady()) {
            Log.e(TAG, "Teste USB: impressora nao pronta (${getStatusDetail()})")
            return false
        }
        return try {
            sendData(ESC_INIT)
            sendData(ESC_ALIGN_CENTER)
            sendData("TESTE MINHA LOJA\n".toByteArray())
            sendData("Impressora USB OK\n".toByteArray())
            sendData("Auto-print ativo\n".toByteArray())
            sendData(ESC_ALIGN_LEFT)
            sendData("--------------------------------\n".toByteArray())
            sendData(ESC_FEED_LINES)
            sendData(GS_CUT_PAPER_PARTIAL)
            Log.i(TAG, "Cupom de teste USB impresso.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao imprimir teste USB: ${e.message}", e)
            false
        }
    }

    // --- Conversao Bitmap -> ESC/POS Raster ---

    /**
     * Converte Bitmap ARGB para formato raster ESC/POS (GS v 0).
     *
     * Formato GS v 0:
     *   0x1D 0x76 0x30 m xL xH yL yH [data]
     *   m = 0 (normal density)
     *   xL xH = largura em bytes (width / 8)
     *   yL yH = altura em linhas
     *   data = raster bits (1 = preto, 0 = branco)
     */
    private fun bitmapToEscPosRaster(original: Bitmap): ByteArray {
        // Redimensiona para largura da bobina se necessario (max 576px para 80mm)
        val maxWidth = 576
        val bitmap = if (original.width > maxWidth) {
            val ratio = maxWidth.toFloat() / original.width
            val newHeight = (original.height * ratio).toInt()
            Bitmap.createScaledBitmap(original, maxWidth, newHeight, true)
        } else {
            original
        }

        val width = bitmap.width
        val height = bitmap.height

        // Largura deve ser multiplo de 8
        val widthBytes = (width + 7) / 8

        val baos = ByteArrayOutputStream()

        // Header: GS v 0 m xL xH yL yH
        baos.write(0x1D) // GS
        baos.write(0x76) // v
        baos.write(0x30) // 0
        baos.write(0x00) // m = normal
        baos.write(widthBytes and 0xFF) // xL
        baos.write((widthBytes shr 8) and 0xFF) // xH
        baos.write(height and 0xFF) // yL
        baos.write((height shr 8) and 0xFF) // yH

        // Pixel data: cada bit = 1 pixel (1=preto, 0=branco)
        for (y in 0 until height) {
            for (xByte in 0 until widthBytes) {
                var byteVal = 0
                for (bit in 0 until 8) {
                    val x = xByte * 8 + bit
                    if (x < width) {
                        val pixel = bitmap.getPixel(x, y)
                        val alpha = Color.alpha(pixel)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        // Luminancia (threshold 128). Pixels transparentes = branco.
                        val luminance = if (alpha < 128) 255 else (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                        if (luminance < 128) {
                            byteVal = byteVal or (0x80 shr bit) // bit preto
                        }
                    }
                }
                baos.write(byteVal)
            }
        }

        return baos.toByteArray()
    }
}
