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
                val grouped = messages.filter { !it.displayOriginatingAddress.isNullOrBlank() }
                    .groupBy { it.displayOriginatingAddress!! }
                grouped.forEach { (sender, parts) ->
                    val body = parts.joinToString(separator = "") { it.displayMessageBody.orEmpty() }.trim()
                    if (body.isBlank() || body.length > MAX_MESSAGE_LENGTH) {
                        Log.w(TAG, "Ignoring empty/oversized SMS from $sender")
                        return@forEach
                    }
                    val receivedAt = parts.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
                    if (!store.claimIncoming(sender, body, receivedAt)) {
                        Log.d(TAG, "Duplicate SMS ignored from $sender")
                        return@forEach
                    }
                    try {
                        SmsCommandProcessor.process(context.applicationContext, sender, body)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process SMS", e)
                        DatabaseHelper.getInstance(context.applicationContext)
                            .logSms(sender, body, "received", "failed: ${e.javaClass.simpleName}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMS receiver failed", e)
            } finally {
                pending.finish()
            }
        }, "sms-receiver-worker").start()
    }
}
