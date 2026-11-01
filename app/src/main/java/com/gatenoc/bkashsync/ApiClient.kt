package com.smartpay.sync

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ApiResult(
    val httpCode: Int,
    val success: Boolean,
    val status: String,
    val message: String,
    val json: JSONObject? = null
)

object ApiClient {

    /**
     * Exact Method 76 (bkash_sms_sync.php) protocol:
     * Sends POST request with X-Device-Token header and JSON payload {"sender": "bKash", "raw_sms": "...", "local_event_id": "..."}
     */
    private fun postJson(endpoint: String, deviceToken: String, payload: JSONObject): ApiResult {
        if (endpoint.isBlank() || deviceToken.length < 16) {
            return ApiResult(0, false, "error", "Invalid Device Token or Endpoint URL")
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                // Official method 76 header authentication
                setRequestProperty("X-Device-Token", deviceToken)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""

            val json = try { JSONObject(responseText) } catch (_: Exception) { null }
            val ok = json?.optBoolean("success", false) ?: (code in 200..299)
            val st = json?.optString("status", "") ?: ""
            val msg = json?.optString("message", if (ok) "Success" else "HTTP $code") ?: "HTTP $code"

            ApiResult(code, ok, st, msg, json)
        } catch (e: Exception) {
            ApiResult(0, false, "network_error", e.localizedMessage ?: "Could not connect to server")
        } finally {
            conn?.disconnect()
        }
    }

    fun pingServer(endpoint: String, deviceToken: String): ApiResult {
        val body = JSONObject().apply {
            put("action", "ping")
        }
        return postJson(endpoint, deviceToken, body)
    }

    fun uploadSms(endpoint: String, deviceToken: String, record: SmsRecord): ApiResult {
        val body = JSONObject().apply {
            put("sender", "bKash")
            put("raw_sms", record.message)
            put("local_event_id", record.id)
        }
        return postJson(endpoint, deviceToken, body)
    }

    fun checkStatuses(endpoint: String, deviceToken: String, trxIds: List<String>): Map<String, String> {
        return emptyMap()
    }
}