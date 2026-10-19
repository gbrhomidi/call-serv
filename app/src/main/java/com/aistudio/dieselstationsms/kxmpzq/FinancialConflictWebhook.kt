package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.security.MessageDigest

/** Enqueues a real HTTP webhook delivery; no delivery is attempted until configured by the owner. */
internal object FinancialConflictNotifier {
    private const val KEY_URL = "webhook_url"
    private const val KEY_SECRET = "webhook_secret"
    private const val KEY_PAYLOAD = "webhook_payload"
    private const val MAX_SECRET_LENGTH = 500

    fun enqueue(
        context: Context,
        eventType: String,
        sender: String,
        requestId: Long? = null,
        reason: String,
        message: String
    ) {
        val helper = DatabaseHelper.getInstance(context.applicationContext)
        val url = helper.getSetting("financial_conflict_webhook_url").trim()
        val enabled = helper.getSetting("financial_conflict_webhook_enabled").trim().lowercase() in setOf("1", "true", "yes")
        if (!enabled || !url.startsWith("https://") || url.length > 2048) return

        val payload = JSONObject().apply {
            put("event", eventType)
            put("occurred_at", System.currentTimeMillis())
            if (requestId != null) put("request_id", requestId)
            put("sender", SmsTextAnalysis.normalizePhone(sender))
            put("reason", reason.take(500))
            put("message_sha256", sha256(message))
            put("source", "android_sms_activation")
        }.toString()
        val secret = helper.getSetting("financial_conflict_webhook_secret").take(MAX_SECRET_LENGTH)
        val name = "financial-conflict-${sha256(payload).take(32)}"
        val data = Data.Builder()
            .putString(KEY_URL, url)
            .putString(KEY_SECRET, secret)
            .putString(KEY_PAYLOAD, payload)
            .build()
        val request = OneTimeWorkRequestBuilder<FinancialConflictWebhookWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    internal const val DATA_URL = KEY_URL
    internal const val DATA_SECRET = KEY_SECRET
    internal const val DATA_PAYLOAD = KEY_PAYLOAD

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
