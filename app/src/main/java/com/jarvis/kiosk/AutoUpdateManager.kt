package com.jarvis.kiosk

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gerenciador de atualizacoes OTA (Over The Air) para o Kiosk.
 * Consulta periodicamente a API de Releases do GitHub para verificar se ha novas versoes
 * e gerencia o download e instalacao local do APK.
 */
object AutoUpdateManager {

    private const val TAG = "AutoUpdateManager"
    private const val VERSION_URL = "https://github.com/killsisbr/jarvis-kiosk/releases/latest/download/version.json"

    private val handler = Handler(Looper.getMainLooper())

    private data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String
    )

    /**
     * Verifica se existe uma nova versao disponivel no GitHub.
     * Deve ser chamada na thread principal (passando a Activity).
     */
    fun check(activity: Activity) {
        Thread {
            try {
                Log.i(TAG, "Iniciando checagem de atualizacoes OTA...")
                val url = URL(VERSION_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val stream = connection.inputStream
                    val json = stream.bufferedReader().use { it.readText() }
                    val info = Gson().fromJson(json, VersionInfo::class.java)

                    val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode
                    }

                    Log.i(TAG, "OTA: Versao instalada=$currentVersionCode, Versao remota=${info.versionCode}")

                    if (info.versionCode > currentVersionCode) {
                        handler.post {
                            showUpdateDialog(activity, info)
                        }
                    } else {
                        Log.i(TAG, "OTA: O aplicativo ja esta na versao mais recente.")
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao checar atualizacoes OTA: ${e.message}")
            }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, info: VersionInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        AlertDialog.Builder(activity)
            .setTitle("Atualizacao Disponivel")
            .setMessage("Uma nova versao do Kiosk (v${info.versionName}) esta disponivel. Deseja atualizar agora?")
            .setCancelable(false)
            .setPositiveButton("Atualizar") { dialog, _ ->
                dialog.dismiss()
                downloadAndInstall(activity, info.downloadUrl)
            }
            .setNegativeButton("Mais tarde") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun downloadAndInstall(activity: Activity, downloadUrl: String) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(activity).apply {
            setTitle("Baixando Atualizacao")
            setMessage("Fazendo download da nova versao...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                val fileLength = connection.contentLength
                val input = BufferedInputStream(url.openStream(), 8192)

                val outputFile = File(activity.cacheDir, "update.apk")
                if (outputFile.exists()) {
                    outputFile.delete()
                }

                val output = FileOutputStream(outputFile)
                val data = ByteArray(1024)
                var total: Long = 0
                var count: Int

                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        handler.post {
                            progressDialog.progress = progress
                        }
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                handler.post {
                    progressDialog.dismiss()
                    installApk(activity, outputFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao baixar atualizacao OTA: ${e.message}")
                handler.post {
                    progressDialog.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("Erro na Atualizacao")
                        .setMessage("Falha ao baixar nova versao: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            Log.i(TAG, "Instalador de pacotes Android iniciado para: ${apkFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao executar instalador do APK: ${e.message}")
        }
    }
}
