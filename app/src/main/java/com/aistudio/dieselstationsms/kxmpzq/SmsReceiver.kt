package com.aistudio.dieselstationsms.kxmpzq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Receives SMS quickly, claims each message atomically, then persists processing. */
class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
        private const val MAX_MESSAGE_LENGTH = 1600
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val appContext = context.applicationContext
        val store = SmsActivationStore(appContext)
        val workManager = WorkManager.getInstance(appContext)

        // A multipart broadcast can contain independent messages. Group only
        // segments sharing both origin and timestamp.
        messages.filter { !it.displayOriginatingAddress.isNullOrBlank() }
            .groupBy { it.displayOriginatingAddress!! to it.timestampMillis }
            .forEach { (batch, parts) ->
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
                val idempotencyKey = smsInboundIdempotencyKey(sender, body, receivedAt)
                val input = Data.Builder()
                    .putString(SmsInboundWorker.KEY_SENDER, sender)
                    .putString(SmsInboundWorker.KEY_BODY, body)
                    .putLong(SmsInboundWorker.KEY_RECEIVED_AT, receivedAt)
                    .putString(SmsInboundWorker.KEY_IDEMPOTENCY, idempotencyKey)
                    .build()
                val request = OneTimeWorkRequestBuilder<SmsInboundWorker>()
                    .setInputData(input)
                    .addTag("sms-inbound")
                    .build()
                // KEEP makes receiver redelivery and process restarts converge
                // on one durable job for the same inbound SMS.
                workManager.enqueueUniqueWork(
                    "sms-inbound-$idempotencyKey",
                    ExistingWorkPolicy.KEEP,
                    request
                )
                Log.i(TAG, "Inbound SMS accepted and queued for processing")
            }
    }
}
