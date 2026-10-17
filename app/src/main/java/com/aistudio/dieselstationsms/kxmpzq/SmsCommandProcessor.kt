package com.aistudio.dieselstationsms.kxmpzq

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

internal object SmsCommandProcessor {
    private const val TAG = "SmsCommandProcessor"
    private const val MAX_REPLY_PARTS = 4

    fun process(context: Context, sender: String, body: String) {
        val helper = DatabaseHelper.getInstance(context)
        val store = SmsActivationStore(context)
        val normalizedSender = SmsTextAnalysis.normalizePhone(sender)
        if (helper.getSetting("sms_enabled") == "false" || helper.getSetting("app_active") == "false") {
            helper.logSms(sender, body, "received", "ignored: SMS or application disabled")
            return
        }
        val bank = helper.getFinancialInstitutionBySender(sender)
        val legacyBankSender = SmsTextAnalysis.normalizePhone(helper.getSetting("system_bank_sender_phone"))
        val providerSender = SmsTextAnalysis.normalizePhone(helper.getSetting("activation_provider_phone"))
        val telecomCompanies = helper.getTelecomCompanies()
        val configuredProvider = (0 until telecomCompanies.length()).any { index ->
            val telecom = telecomCompanies.optJSONObject(index)
            telecom != null && SmsTextAnalysis.normalizePhone(telecom.optString("balance_phone")) == normalizedSender
        }

        if (bank != null || (legacyBankSender.isNotBlank() && normalizedSender == legacyBankSender)) {
            val bankName = bank?.optString("name")?.ifBlank { helper.getSetting("system_bank_name") } ?: helper.getSetting("system_bank_name")
            val notice = SmsTextAnalysis.parseBankNotice(body, bankName)
            if (notice == null) {
                Log.w(TAG, "Bank payment SMS ignored: unrecognized notice format")
                helper.logSms(sender, body, "bank_notice", "ignored: invalid bank format")
                return
            }
            val request = store.acceptBankPayment(sender, bankName, notice, bank?.optString("trusted_sender"), bankName)
            if (request == null) {
                Log.w(TAG, "Bank payment SMS rejected: strict reconciliation mismatch")
                helper.logSms(sender, body, "bank_notice", "rejected: strict reconciliation mismatch")
                return
            }
            helper.logSms(sender, body, "bank_notice", "verified")
            val requestId = request.getLong("id")
            Log.i(TAG, "Bank payment verified; dispatching USSD request=$requestId")
            dispatchUssd(context, requestId, store)
            return
        }

        if ((providerSender.isNotBlank() && normalizedSender == providerSender) || configuredProvider) {
            handleProviderMessage(context, sender, body, store, helper)
            return
        }

        val customer = helper.getCustomerByPhone(sender)
        if (customer == null || customer.optString("status", "active") != "active" || customer.optInt("is_blocked", 0) == 1) {
            helper.logSms(sender, body, "received", if (customer?.optInt("is_blocked", 0) == 1) "ignored: customer blocked" else "ignored: unknown or inactive customer")
            return
        }
        helper.logSms(sender, body, "received", "processing")
        val message = SmsTextAnalysis.normalize(body)

        if (message == "رصيدي" || message == "رصيدي الحالي" || message == "رصيد" || message == "balance") {
            val balance = customer.optDouble("current_balance", 0.0)
            reply(context, helper, sender, "مرحباً ${customer.optString("full_name")}، رصيد حسابك الحالي: ${formatMoney(balance)} ريال.")
            return
        }
        if (message == "استعلام" || message == "مساعده" || message == "help" || message == "؟") {
            reply(context, helper, sender, helpMessage(helper.getServices(status = "active")))
            return
        }

        val requestedService = SmsTextAnalysis.findService(body, helper.getServices(status = "active"))
        if (requestedService == null) {
            reply(context, helper, sender, "لم أستطع تحديد الخدمة المطلوبة. أرسل «استعلام» لعرض الخدمات وطريقة الطلب.")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Activation request rejected before payment: USSD platform or permission unavailable")
            helper.logSms(sender, body, "activation_request", "rejected: USSD unavailable; no payment requested")
            reply(context, helper, sender, "تعذر بدء التفعيل الآلي على هذا الجهاز لغياب دعم USSD أو صلاحية الاتصال. لم يُنشأ طلب ولم يُطلب منك التحويل؛ تواصل مع الدعم.")
            return
        }

        val request = store.createRequest(customer, requestedService, sender)
        if (request.has("error")) {
            helper.logSms(sender, body, "activation_request", "rejected: ${request.optString("error")}")
            val reason = request.optString("error").ifBlank { "بيانات الخدمة أو إعدادات التفعيل غير مكتملة" }
            reply(context, helper, sender, "تعذر إنشاء طلب التفعيل: $reason. لم يتم طلب تحويل؛ تواصل مع الدعم.")
            return
        }
        val amount = request.optLong("amount_cents") / 100.0
        val serviceName = request.optString("service_name", "الخدمة")
        val duplicate = request.optBoolean("duplicate")

        if (duplicate) {
            val statusText = when (request.optString("status")) {
                "awaiting_payment" -> "الطلب السابق بانتظار التحويل؛ لن ننشئ طلباً مكرراً."
                "pending_provider", "reconciling", "retry_ready" -> "الطلب السابق قيد المعالجة أو التحقق؛ لا تعاود إرساله الآن."
                "uncertain" -> "الطلب السابق قيد المراجعة اليدوية؛ لم نكرر التفعيل منعاً للخصم المزدوج."
                else -> "يوجد طلب سابق لهذه الخدمة خلال آخر 24 ساعة."
            }
            reply(context, helper, sender, "$serviceName — التكلفة ${formatMoney(amount)} ريال. $statusText")
            return
        }

        if (request.optString("payment_method") == "credit") {
            if (request.optString("activation_command").isBlank()) {
                store.markFailed(request.getLong("id"), "أمر التفعيل الكامل غير مضبوط")
                reply(context, helper, sender, "$serviceName تكلفتها ${formatMoney(amount)} ريال، لكن أمر التفعيل الكامل غير مضبوط؛ لم يتم خصم أو تفعيل الخدمة.")
                return
            }
            dispatchUssd(context, request.getLong("id"), store)
            reply(context, helper, sender, "تم استلام طلب $serviceName. التكلفة ${formatMoney(amount)} ريال، والطلب قيد التفعيل والتحقق من المورد.")
            return
        }

        val bankName = helper.getSetting("system_bank_name").trim()
        val accountName = helper.getSetting("system_bank_account_name").trim()
        val bankNumber = helper.getSetting("system_bank_sender_phone").trim()
        if (bankName.isBlank() || accountName.isBlank() || bankNumber.isBlank()) {
            store.markFailed(request.getLong("id"), "إعدادات الحساب البنكي غير مكتملة؛ لم يُطلب الدفع")
            reply(context, helper, sender, "$serviceName تكلفتها ${formatMoney(amount)} ريال. لا يتوفر إعداد مكتمل للتحويل البنكي؛ يرجى التواصل مع الدعم لإتمام الطلب. لم يتم تفعيل الخدمة.")
            return
        }
        reply(
            context, helper, sender,
            "$serviceName تكلفتها ${formatMoney(amount)} ريال. لإتمام الطلب أودع المبلغ في حساب النظام لدى $bankName، باسم $accountName، ثم انتظر تأكيد البنك. لا ترسل بيانات بطاقتك أو رمز التحقق."
        )
    }

    private fun handleProviderMessage(context: Context, sender: String, body: String, store: SmsActivationStore, helper: DatabaseHelper) {
        val request = store.findUniqueProviderRequest(sender, body)
        if (request == null) {
            Log.w(TAG, "Provider SMS ignored: no unique pending activation request")
            helper.logSms(sender, body, "provider_notice", "ignored: no unique pending request")
            return
        }
        val requestId = request.optLong("id")
        if (SmsTextAnalysis.isFailureMessage(body)) {
            Log.w(TAG, "Provider reported activation failure request=$requestId")
            activationFailure(context, helper, store, request, body, "أفاد المورد بفشل عملية التفعيل")
            helper.logSms(sender, body, "provider_notice", "activation failure reported")
            return
        }
        if (SmsTextAnalysis.isSuccessMessage(body)) {
            val completed = store.completeSuccess(requestId, body) ?: return
            Log.i(TAG, "Provider confirmed activation request=$requestId")
            reply(context, helper, completed.optString("customer_phone"), "تم تفعيل ${completed.optString("service_name")} بنجاح. التكلفة ${formatMoney(completed.optLong("amount_cents") / 100.0)} ريال.")
            helper.logSms(sender, body, "provider_notice", "activation confirmed")
            return
        }

        val balance = extractLastBalance(body)
        if (balance != null && request.optString("status") == "reconciling") {
            val retry = store.prepareSafeRetry(sender, balance)
            if (retry != null) {
                helper.setSetting("activation_provider_balance", formatMoney(balance / 100.0))
                dispatchUssd(context, retry.getLong("id"), store)
                helper.logSms(sender, body, "provider_balance", "unchanged balance; one safe retry dispatched")
            } else {
                reply(context, helper, request.optString("customer_phone"), "لم يثبت استعلام رصيد المورد أن المحاولة الأولى لم تخصم؛ أوقفنا إعادة التفعيل منعاً للخصم المزدوج. الطلب قيد المراجعة.")
                helper.logSms(sender, body, "provider_balance", "no safe retry")
            }
            return
        }
        helper.logSms(sender, body, "provider_notice", "received: awaiting definitive result")
    }

    private fun dispatchUssd(context: Context, requestId: Long, store: SmsActivationStore) {
        val request = store.getRequest(requestId) ?: return
        val helper = DatabaseHelper.getInstance(context)
        val attempt = request.optInt("attempt_count", 1).coerceAtLeast(1)
        val providerPhone = SmsTextAnalysis.normalizePhone(request.optString("provider_phone").ifBlank { helper.getSetting("activation_provider_phone") })
        if (providerPhone.length < 7) {
            Log.e(TAG, "USSD dispatch blocked request=$requestId reason=provider_phone_missing")
            activationFailure(context, helper, store, request, "رقم المورد غير مضبوط", "تعذر بدء التفعيل بسبب نقص إعداد رقم المورد")
            return
        }
        val command = SmsTextAnalysis.buildUssdCommand(request.optString("activation_command"), request.optString("customer_phone"))
        if (command == null) {
            Log.e(TAG, "USSD dispatch blocked request=$requestId reason=invalid_activation_template")
            activationFailure(context, helper, store, request, "قالب USSD غير صالح", "تعذر بدء التفعيل بسبب خطأ في قالب أمر الخدمة")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.e(TAG, "USSD dispatch blocked request=$requestId reason=android_version_below_26")
            activationFailure(context, helper, store, request, "إصدار Android لا يدعم USSD المطلوب", "تعذر بدء التفعيل تلقائياً على إصدار Android هذا")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "USSD dispatch blocked request=$requestId reason=call_phone_permission_missing")
            activationFailure(context, helper, store, request, "صلاحية الاتصال غير متاحة", "تعذر بدء التفعيل بسبب غياب صلاحية الاتصال")
            return
        }
        if (!store.markDispatching(requestId, attempt)) {
            Log.w(TAG, "USSD dispatch skipped request=$requestId reason=invalid_request_state")
            return
        }
        try {
            scheduleTimeout(context, requestId, attempt)
            val telephony = context.getSystemService(TelephonyManager::class.java)
                ?: throw IllegalStateException("TelephonyManager unavailable")
            Log.i(TAG, "USSD dispatch initiated request=$requestId attempt=$attempt commandLength=${command.length}")
            telephony.sendUssdRequest(command, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                    // The carrier UI response does not prove provider-side charging/success; await the registered provider SMS.
                    Log.i(TAG, "Carrier USSD callback received request=$requestId responseLength=${response.length}; awaiting provider SMS")
                }
                override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, ussdRequest: String, failureCode: Int) {
                    Log.e(TAG, "Carrier USSD request failed request=$requestId failureCode=$failureCode")
                    cancelTimeoutSafely(context, requestId, attempt)
                    activationFailure(context, helper, store, request, "USSD request failed with code $failureCode", "تعذر تأكيد التفعيل عبر شبكة المشغل")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            cancelTimeoutSafely(context, requestId, attempt)
            activationFailure(context, helper, store, request, "تعذر إرسال USSD: ${e.javaClass.simpleName}", "تعذر تأكيد التفعيل عبر شبكة المشغل")
            Log.e(TAG, "USSD dispatch failed request=$requestId error=${e.javaClass.simpleName}")
        }
    }

    private fun activationFailure(
        context: Context,
        helper: DatabaseHelper,
        store: SmsActivationStore,
        request: JSONObject,
        reason: String,
        customerDetail: String
    ) {
        val requestId = request.optLong("id")
        val customerPhone = request.optString("customer_phone")
        val serviceName = request.optString("service_name", "الخدمة")
        if (request.optString("payment_method") == "bank") {
            store.markUncertain(requestId, reason)
            reply(context, helper, customerPhone,
                "تم التحقق من تحويلك مقابل $serviceName، لكن $customerDetail. لا تعِد التحويل أو الطلب؛ تواصل مع الدعم لمراجعة الحالة.")
        } else {
            store.markFailed(requestId, reason)
            reply(context, helper, customerPhone,
                "$serviceName: $customerDetail. لم نضف أي مبلغ إلى رصيدك الآجل؛ تواصل مع الدعم.")
        }
    }

    private fun cancelTimeoutSafely(context: Context, requestId: Long, attempt: Int) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(timeoutWorkName(requestId, attempt))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to cancel USSD timeout request=$requestId error=${e.javaClass.simpleName}")
        }
    }

    private fun scheduleTimeout(context: Context, requestId: Long, attempt: Int) {
        val data = Data.Builder().putLong(SmsActivationTimeoutWorker.KEY_REQUEST_ID, requestId).putInt(SmsActivationTimeoutWorker.KEY_ATTEMPT, attempt).build()
        val request = OneTimeWorkRequestBuilder<SmsActivationTimeoutWorker>()
            .setInitialDelay(5, TimeUnit.MINUTES).setInputData(data).build()
        WorkManager.getInstance(context).enqueueUniqueWork(timeoutWorkName(requestId, attempt), ExistingWorkPolicy.KEEP, request)
    }

    private fun timeoutWorkName(requestId: Long, attempt: Int) = "sms-activation-timeout-$requestId-$attempt"

    private fun helpMessage(services: JSONArray): String {
        val names = (0 until services.length()).mapNotNull { services.optJSONObject(it)?.let { item ->
            item.optString("service_name_ar").ifBlank { item.optString("service_name") }.trim().takeIf(String::isNotBlank)
        } }
        val catalogue = if (names.isEmpty()) "لا توجد خدمات نشطة حالياً." else
            "الخدمات المتاحة:\n" + names.mapIndexed { index, name -> "${index + 1}. $name" }.joinToString("\n")
        return "الأوامر: «رصيدي» لمعرفة رصيد الحساب، «استعلام» للمساعدة. لطلب خدمة أرسل: «تفعيل اسم الخدمة».\n$catalogue"
    }

    internal fun notifyCustomer(context: Context, helper: DatabaseHelper, phone: String, message: String) =
        reply(context, helper, phone, message)

    private fun reply(context: Context, helper: DatabaseHelper, phone: String, message: String) {
        val normalized = SmsTextAnalysis.normalizePhone(phone)
        if (normalized.length < 7 || ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        val store = SmsActivationStore(context)
        if (!store.claimReply(normalized)) return
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val safeMessage = message.take(600)
            val parts = smsManager.divideMessage(safeMessage).take(MAX_REPLY_PARTS)
            if (parts.size > 1) smsManager.sendMultipartTextMessage(normalized, null, ArrayList(parts), null, null)
            else smsManager.sendTextMessage(normalized, null, safeMessage, null, null)
            helper.logSms(normalized, safeMessage, "auto_reply", "sent")
        } catch (e: Exception) {
            helper.logSms(normalized, message, "auto_reply", "failed: ${e.message}")
            Log.e(TAG, "SMS reply failed", e)
        }
    }

    private fun extractLastBalance(body: String): Long? = SmsTextAnalysis.lastMoneyCents(body)

    private fun formatMoney(amount: Double): String = java.math.BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
