package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class FinancialConflictWebhookWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "FinancialWebhook"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 32 * 1024
    }

    override suspend fun doWork(): Result {
        val urlText = inputData.getString(FinancialConflictNotifier.DATA_URL).orEmpty()
        val payload = inputData.getString(FinancialConflictNotifier.DATA_PAYLOAD).orEmpty()
        val secret = inputData.getString(FinancialConflictNotifier.DATA_SECRET).orEmpty()
        if (!urlText.startsWith("https://") || payload.isBlank()) return Result.failure()
        val connection = try {
            (URL(urlText).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "CallServ-Android-FinancialWebhook/1")
                setRequestProperty("X-CallServ-Event", "financial_conflict")
                if (secret.isNotBlank()) setRequestProperty("X-CallServ-Signature", hmacSha256(secret, payload))
                outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Webhook network failure; retrying", e)
            return Result.retry()
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    val buffer = CharArray(1024)
                    var total = 0
                    while (total < MAX_RESPONSE_BYTES) {
                        val read = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total))
                        if (read <= 0) break
                        total += read
                    }
                }
            }
            when {
                code in 200..299 -> Result.success()
                code == 408 || code == 425 || code == 429 || code >= 500 -> Result.retry()
                else -> {
                    Log.e(TAG, "Webhook rejected event with HTTP $code")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Webhook response failure; retrying", e)
            Result.retry()
        } finally {
            connection.disconnect()
        }
    }

    private fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
