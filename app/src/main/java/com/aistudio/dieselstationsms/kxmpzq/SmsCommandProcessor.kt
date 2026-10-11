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
        val bankSender = SmsTextAnalysis.normalizePhone(helper.getSetting("system_bank_sender_phone"))
        val providerSender = SmsTextAnalysis.normalizePhone(helper.getSetting("activation_provider_phone"))

        if (bankSender.isNotBlank() && normalizedSender == bankSender) {
            val bankName = helper.getSetting("system_bank_name")
            val notice = SmsTextAnalysis.parseBankNotice(body, bankName)
            if (notice == null) {
                helper.logSms(sender, body, "bank_notice", "ignored: invalid bank format")
                return
            }
            val request = store.acceptBankPayment(sender, bankName, notice)
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

        if (providerSender.isNotBlank() && normalizedSender == providerSender) {
            handleProviderMessage(context, sender, body, store, helper)
            return
        }

        val customer = helper.getCustomerByPhone(sender)
        if (customer == null || customer.optString("status", "active") != "active") {
            helper.logSms(sender, body, "received", "ignored: unknown or inactive customer")
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

        val request = store.createRequest(customer, requestedService, sender)
        if (request.has("error")) {
            reply(context, helper, sender, "تعذر إنشاء الطلب حالياً. يرجى المحاولة لاحقاً أو التواصل مع الدعم.")
            return
        }
        val amount = request.optLong("amount_cents") / 100.0
        val serviceName = request.optString("service_name", "الخدمة")
        val duplicate = request.optBoolean("duplicate")

        if (duplicate) {
            val statusText = when (request.optString("status")) {
                "awaiting_payment" -> "الطلب السابق بانتظار التحويل؛ لن ننشئ طلباً مكرراً."
                "pending_provider", "reconciling" -> "الطلب السابق قيد المعالجة أو التحقق؛ لا تعاود إرساله الآن."
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
        if (bankName.isBlank() || accountName.isBlank() || bankNumber.isBlank() || SmsTextAnalysis.toCents(helper.getSetting("system_bank_expected_balance")) == null) {
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
            helper.logSms(sender, body, "provider_notice", "ignored: no unique pending request")
            return
        }
        val requestId = request.optLong("id")
        if (SmsTextAnalysis.isFailureMessage(body)) {
            Log.w(TAG, "Provider reported activation failure request=$requestId")
            store.markFailed(requestId, body)
            reply(context, helper, request.optString("customer_phone"), "تعذر تفعيل ${request.optString("service_name")}. لم نسجل التفعيل كناجح؛ يرجى التواصل مع الدعم.")
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
        if (providerPhone.isBlank()) {
            store.markFailed(requestId, "رقم المورد المؤكد غير مضبوط")
            reply(context, helper, request.optString("customer_phone"), "لم يبدأ التفعيل: رقم هاتف المورد غير مضبوط، ولم يتم خصم أي مبلغ.")
            return
        }
        val command = SmsTextAnalysis.buildUssdCommand(request.optString("activation_command"), request.optString("customer_phone"))
        if (command == null) {
            store.markFailed(requestId, "أمر التفعيل الكامل يجب أن يحتوي خانة phone صالحة")
            reply(context, helper, request.optString("customer_phone"), "لم يبدأ التفعيل: أمر الخدمة يجب أن يحتوي (phone) لاستبداله برقمك. لم يتم خصم أي مبلغ.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.e(TAG, "USSD dispatch blocked request=$requestId reason=android_version_below_26")
            store.markFailed(requestId, "USSD غير متاح: Android 8+ وصلاحية الاتصال مطلوبان")
            reply(context, helper, request.optString("customer_phone"), "تعذر إرسال أمر USSD تلقائياً. لم يتم خصم أو تسجيل التفعيل؛ تحقق من إصدار Android وصلاحية الاتصال.")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "USSD dispatch blocked request=$requestId reason=call_phone_permission_missing")
            store.markFailed(requestId, "USSD غير متاح: Android 8+ وصلاحية الاتصال مطلوبان")
            reply(context, helper, request.optString("customer_phone"), "تعذر إرسال أمر USSD تلقائياً. لم يتم خصم أو تسجيل التفعيل؛ تحقق من إصدار Android وصلاحية الاتصال.")
            return
        }
        if (!store.markDispatching(requestId, attempt)) {
            Log.w(TAG, "USSD dispatch skipped request=$requestId reason=invalid_request_state")
            return
        }
        scheduleTimeout(context, requestId, attempt)
        try {
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
                    store.markFailed(requestId, "USSD request failed with code $failureCode")
                    WorkManager.getInstance(context).cancelUniqueWork(timeoutWorkName(requestId, attempt))
                    reply(context, helper, request.optString("customer_phone"), "فشل إرسال أمر التفعيل عبر الشبكة. لم نسجل الخدمة كناجحة؛ يرجى المحاولة لاحقاً.")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            store.markFailed(requestId, "تعذر إرسال USSD: ${e.javaClass.simpleName}")
            WorkManager.getInstance(context).cancelUniqueWork(timeoutWorkName(requestId, attempt))
            reply(context, helper, request.optString("customer_phone"), "تعذر إرسال أمر التفعيل عبر الشبكة. لم يتم تسجيل التفعيل كناجح.")
            Log.e(TAG, "USSD dispatch failed request=$requestId error=${e.javaClass.simpleName}")
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
        val names = (0 until minOf(services.length(), 8)).mapNotNull { services.optJSONObject(it)?.let { item -> item.optString("service_name_ar").ifBlank { item.optString("service_name") } } }
        val catalogue = if (names.isEmpty()) "لا توجد خدمات نشطة حالياً." else "الخدمات المتاحة: ${names.joinToString("، ")}."
        return "الأوامر: «رصيدي» لمعرفة رصيد الحساب، «استعلام» للمساعدة. لطلب خدمة أرسل: «تفعيل اسم الخدمة». $catalogue"
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

    private fun extractLastBalance(body: String): Long? {
        val money = Regex("[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
        val matches = money.findAll(SmsTextAnalysis.normalizeDigits(body)).toList()
        return matches.lastOrNull()?.value?.let(SmsTextAnalysis::toCents)
    }

    private fun formatMoney(amount: Double): String = java.math.BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
