package com.v2bot.huautoscan

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object PcSyncClient {
    private val executor = Executors.newSingleThreadExecutor()

    data class PullResult(
        val ok: Boolean,
        val quantities: List<Int> = emptyList(),
        val labels: List<String> = emptyList(),
        val barcodes: List<String> = emptyList(),
        val message: String = ""
    )

    fun pullQueue(context: Context, onComplete: (PullResult) -> Unit) {
        val baseUrl = BotConfig.getServerUrl(context)
        if (!baseUrl.startsWith("http")) {
            onComplete(PullResult(false, message = "Valid URL daalein (http://...)"))
            return
        }
        executor.execute {
            try {
                val conn = (URL("$baseUrl/api/state").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3500
                    readTimeout = 3500
                }
                val code = conn.responseCode
                if (code != 200) {
                    onComplete(PullResult(false, message = "Server error HTTP $code"))
                    return@execute
                }
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(body)
                val items = json.optJSONArray("items")
                val qtys = mutableListOf<Int>()
                val lbls = mutableListOf<String>()
                val bcs = mutableListOf<String>()
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val obj = items.getJSONObject(i)
                        val q = obj.optInt("qty", 0)
                        if (q > 0) {
                            qtys.add(q)
                            lbls.add(obj.optString("label", "Art ${i + 1}"))
                            bcs.add(obj.optString("barcode", ""))
                        }
                    }
                }
                if (qtys.isEmpty()) {
                    onComplete(PullResult(false, message = "PC list khali hai! Pehle PC par barcodes add karein."))
                } else {
                    BotConfig.setQueueRaw(context, qtys.joinToString(", "))
                    BotConfig.setLabelsRaw(context, lbls.joinToString("||"))
                    BotConfig.setBarcodesRaw(context, bcs.joinToString("||"))
                    val poNo = json.optString("po_no", "")
                    val boxNo = json.optString("box_no", "")
                    BotConfig.setPoNo(context, poNo)
                    BotConfig.setBoxNo(context, boxNo)
                    onComplete(PullResult(true, qtys, lbls, bcs, "Synced ${qtys.size} Art (${qtys.sum()} pcs) | Box: $boxNo"))
                }
            } catch (e: Exception) {
                onComplete(PullResult(false, message = "Connect nahi hua: ${e.message ?: "Check Wi-Fi/IP"}"))
            }
        }
    }

    fun sendBotEvent(context: Context, endpoint: String, payload: Map<String, Any>) {
        if (!BotConfig.isSyncEnabled(context)) return
        val baseUrl = BotConfig.getServerUrl(context)
        if (!baseUrl.startsWith("http")) return
        executor.execute {
            try {
                val json = JSONObject(payload).toString()
                val conn = (URL("$baseUrl$endpoint").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2000
                    readTimeout = 2000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
