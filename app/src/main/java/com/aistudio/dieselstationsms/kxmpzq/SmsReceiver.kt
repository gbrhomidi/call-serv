package com.aistudio.dieselstationsms.kxmpzq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
        private const val MAX_MESSAGE_LENGTH = 1600
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val pending = goAsync()
        Thread({
            try {
                val store = SmsActivationStore(context.applicationContext)
                // A single SMS broadcast may contain multipart segments, but it can
                // also contain two independent messages from the same sender. Group
                // by timestamp as well; otherwise their bodies are concatenated and
                // bank/provider parsing can be corrupted or silently rejected.
                val grouped = messages.filter { !it.displayOriginatingAddress.isNullOrBlank() }
                    .groupBy { it.displayOriginatingAddress!! to it.timestampMillis }
                grouped.forEach { (batch, parts) ->
                    val sender = batch.first
                    val body = parts.joinToString(separator = "") { it.displayMessageBody.orEmpty() }.trim()
                    if (body.isBlank() || body.length > MAX_MESSAGE_LENGTH) {
                        Log.w(TAG, "Ignoring empty/oversized inbound SMS")
                        return@forEach
                    }
                    val receivedAt = batch.second
                    if (!store.claimIncoming(sender, body, receivedAt)) {
                        Log.d(TAG, "Duplicate inbound SMS ignored")
                        return@forEach
                    }
                    Log.i(TAG, "Inbound SMS accepted for processing")
                    try {
                        val idempotencyKey = smsInboundIdempotencyKey(sender, body, receivedAt)
                        SmsCommandProcessor.process(context.applicationContext, sender, body, idempotencyKey)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process inbound SMS error=${e.javaClass.simpleName}")
                        DatabaseHelper.getInstance(context.applicationContext)
                            .logSms(sender, body, "received", "failed: ${e.javaClass.simpleName}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMS receiver failed error=${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }, "sms-receiver-worker").start()
    }
}
