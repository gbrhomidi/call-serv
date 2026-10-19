package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Processes an already-claimed inbound SMS outside the short BroadcastReceiver
 * lifetime. WorkManager persists the job across process death and device idle.
 */
@Keep
internal class SmsInboundWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_SENDER = "sender"
        const val KEY_BODY = "body"
        const val KEY_RECEIVED_AT = "received_at"
        const val KEY_IDEMPOTENCY = "idempotency_key"
    }

    override suspend fun doWork(): Result {
        val sender = inputData.getString(KEY_SENDER).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()
        val receivedAt = inputData.getLong(KEY_RECEIVED_AT, 0L)
        val key = inputData.getString(KEY_IDEMPOTENCY)
            ?: smsInboundIdempotencyKey(sender, body, receivedAt)
        if (sender.isBlank() || body.isBlank() || receivedAt <= 0L) return Result.failure()

        return try {
            SmsCommandProcessor.process(applicationContext, sender, body, key)
            Result.success()
        } catch (e: Exception) {
            DatabaseHelper.getInstance(applicationContext)
                .logSms(sender, body, "received", "failed: ${e.javaClass.simpleName}")
            // The inbound hash has already been claimed. Retrying would be safe
            // because the request idempotency key is persisted and unique.
            Result.retry()
        }
    }
}
