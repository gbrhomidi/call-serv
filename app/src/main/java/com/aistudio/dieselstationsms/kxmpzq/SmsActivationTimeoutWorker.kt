package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal class SmsActivationTimeoutWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_REQUEST_ID = "activation_request_id"
        const val KEY_ATTEMPT = "attempt"
        private const val KEY_FINALIZE = "finalize_reconciliation"
        private const val TAG = "SmsActivationTimeout"
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_REQUEST_ID, -1L)
        val attempt = inputData.getInt(KEY_ATTEMPT, 1)
        if (id <= 0L) return Result.failure()
        val helper = DatabaseHelper.getInstance(applicationContext)
        val store = SmsActivationStore(applicationContext)
        val request = store.getRequest(id) ?: return Result.success()
        val status = request.optString("status")

        if (inputData.getBoolean(KEY_FINALIZE, false)) {
            if (status == "reconciling") {
                store.markUncertain(id, "لم يصل رد رصيد واضح من المورد بعد الاستعلام")
                SmsCommandProcessor.notifyCustomer(applicationContext, helper, request.optString("customer_phone"), "لا يزال تفعيل ${request.optString("service_name")} غير محسوم. لم نكرر العملية؛ يرجى مراجعة الدعم قبل إعادة الطلب.")
            }
            return Result.success()
        }
        if (status != "pending_provider" || request.optInt("attempt_count") != attempt) return Result.success()
        if (attempt >= 2) {
            store.markUncertain(id, "انتهت مهلة المحاولة الثانية؛ لم تتم إعادة المحاولة مرة أخرى")
            SmsCommandProcessor.notifyCustomer(applicationContext, helper, request.optString("customer_phone"), "لم يصل تأكيد المورد لتفعيل ${request.optString("service_name")}. أوقفنا التكرار منعاً للخصم المزدوج؛ يرجى مراجعة الدعم.")
            return Result.success()
        }

        val queryTemplate = helper.getSetting("supplier_balance_query_sms").trim()
        val providerPhone = SmsTextAnalysis.normalizePhone(request.optString("provider_phone"))
        val hasBaseline = !request.isNull("provider_balance_before_cents") && request.optLong("provider_balance_before_cents", -1L) >= 0
        if (queryTemplate.isBlank() || providerPhone.isBlank() || !hasBaseline) {
            store.markUncertain(id, "لا توجد رسالة استعلام/رصيد أساس موثوق؛ لم تتم إعادة المحاولة تلقائياً")
            SmsCommandProcessor.notifyCustomer(applicationContext, helper, request.optString("customer_phone"), "تأخر تأكيد المورد لخدمة ${request.optString("service_name")}. لم نكرر التفعيل لعدم توفر وسيلة تحقق موثوقة؛ تواصل مع الدعم.")
            return Result.success()
        }
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            store.markUncertain(id, "صلاحية SMS غير متاحة لإرسال استعلام المورد")
            return Result.success()
        }
        if (!store.markReconciliation(id)) return Result.success()
        val query = queryTemplate.replace(Regex("(?i)\\{phone}|%phone%"), request.optString("customer_phone"))
            .replace(Regex("(?i)\\{service}|%service%"), request.optString("service_name"))
        try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) applicationContext.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val parts = manager.divideMessage(query.take(300))
            if (parts.size > 1) manager.sendMultipartTextMessage(providerPhone, null, parts, null, null)
            else manager.sendTextMessage(providerPhone, null, query, null, null)
            helper.logSms(providerPhone, query, "provider_balance_query", "sent")
            val finalRequest = OneTimeWorkRequestBuilder<SmsActivationTimeoutWorker>()
                .setInitialDelay(5, TimeUnit.MINUTES)
                .setInputData(Data.Builder().putLong(KEY_REQUEST_ID, id).putInt(KEY_ATTEMPT, attempt).putBoolean(KEY_FINALIZE, true).build())
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork("sms-activation-finalize-$id-$attempt", ExistingWorkPolicy.KEEP, finalRequest)
        } catch (e: Exception) {
            store.markUncertain(id, "فشل إرسال استعلام رصيد المورد")
            helper.logSms(providerPhone, query, "provider_balance_query", "failed: ${e.message}")
            Log.e(TAG, "Failed to query provider balance", e)
        }
        return Result.success()
    }
}
