package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Persistent, idempotent state for SMS-originated orders and bank/provider reconciliation. */
internal class SmsActivationStore(context: Context) {
    private val helper = DatabaseHelper.getInstance(context.applicationContext)
    private val db get() = helper.writableDatabase

    init {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sms_activation_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER NOT NULL, service_id INTEGER NOT NULL,
                customer_phone TEXT NOT NULL, customer_name TEXT NOT NULL, service_name TEXT NOT NULL,
                amount_cents INTEGER NOT NULL, payment_method TEXT NOT NULL, status TEXT NOT NULL,
                activation_command TEXT NOT NULL, provider_phone TEXT NOT NULL DEFAULT '',
                provider_balance_before_cents INTEGER, attempt_count INTEGER NOT NULL DEFAULT 0,
                bank_reference TEXT NOT NULL DEFAULT '', activation_id INTEGER,
                status_message TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_activation_status_provider ON sms_activation_requests(status, provider_phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_activation_customer_service ON sms_activation_requests(customer_id, service_id, created_at)")
    }

    /** Creates an order, applying credit policy and reserving credit atomically. */
    fun createRequest(customer: JSONObject, service: JSONObject, phone: String): JSONObject {
        val customerId = customer.optInt("id")
        val serviceId = service.optInt("id")
        val amountCents = decimalToCents(service.optDouble("price")) ?: 0L
        val normalizedPhone = SmsTextAnalysis.normalizePhone(phone)
        val command = service.optString("activation_command", "").trim()
        val telecom = helper.getTelecomCompanyById(service.optInt("telecom_company_id", 0))
        val providerPhone = SmsTextAnalysis.normalizePhone(telecom?.optString("balance_phone").orEmpty().ifBlank { helper.getSetting("activation_provider_phone") })
        val now = System.currentTimeMillis()
        val result = JSONObject()
        if (customerId <= 0 || serviceId <= 0 || amountCents <= 0) return result.put("error", "بيانات الطلب غير صالحة")
        if (normalizedPhone.length < 7 || providerPhone.length < 7 ||
            SmsTextAnalysis.buildUssdCommand(command, normalizedPhone) == null) {
            return result.put("error", "بيانات التفعيل أو رقم المورد غير مكتمل؛ لم يُنشأ طلب")
        }
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT * FROM sms_activation_requests WHERE customer_id=? AND service_id=? AND (status IN ('pending_provider','reconciling','retry_ready','uncertain') OR (status='awaiting_payment' AND created_at>?)) ORDER BY created_at DESC LIMIT 1",
                arrayOf(customerId.toString(), serviceId.toString(), (now - 24L * 60 * 60 * 1000).toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val existing = cursorToJson(cursor).put("duplicate", true)
                    db.setTransactionSuccessful()
                    return existing
                }
            }

            val allowCredit = customer.optInt("allow_credit", 0) == 1
            val limitCents = decimalToCents(customer.optDouble("credit_limit")) ?: 0L
            val dueCents = decimalToCents(customer.optDouble("current_balance")) ?: Long.MAX_VALUE
            val reservedCents = db.rawQuery(
                "SELECT COALESCE(SUM(amount_cents),0) FROM sms_activation_requests WHERE customer_id=? AND payment_method='credit' AND status IN ('pending_provider','reconciling','retry_ready','uncertain')",
                arrayOf(customerId.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            val projectedDebt = try { Math.addExact(Math.addExact(dueCents, reservedCents), amountCents) } catch (_: ArithmeticException) { null }
            val isCredit = allowCredit && limitCents > 0 && projectedDebt != null && projectedDebt <= limitCents
            val paymentMethod = if (isCredit) "credit" else "bank"
            val status = if (isCredit) "pending_provider" else "awaiting_payment"
            if (!isCredit) {
                val bankName = helper.getSetting("system_bank_name").trim()
                val accountName = helper.getSetting("system_bank_account_name").trim()
                val bankSender = SmsTextAnalysis.normalizePhone(helper.getSetting("system_bank_sender_phone"))
                if (bankName.isBlank() || accountName.isBlank() || bankSender.length < 7) {
                    return result.put("error", "إعدادات الحساب البنكي غير مكتملة؛ لم يُنشأ طلب دفع")
                }
            }
            val providerBalance = SmsTextAnalysis.toCents(helper.getSetting("activation_provider_balance"))
            val values = ContentValues().apply {
                put("customer_id", customerId)
                put("service_id", serviceId)
                put("customer_phone", normalizedPhone)
                put("customer_name", customer.optString("full_name", ""))
                put("service_name", service.optString("service_name_ar", service.optString("service_name", "الخدمة")))
                put("amount_cents", amountCents)
                put("payment_method", paymentMethod)
                put("status", status)
                put("activation_command", command)
                put("provider_phone", SmsTextAnalysis.normalizePhone(providerPhone))
                if (providerBalance != null) put("provider_balance_before_cents", providerBalance)
                put("attempt_count", if (isCredit) 1 else 0)
                put("created_at", now)
                put("updated_at", now)
            }
            val id = db.insertOrThrow("sms_activation_requests", null, values)
            result.put("id", id).put("customer_id", customerId).put("service_id", serviceId)
                .put("customer_phone", normalizedPhone)
                .put("customer_name", customer.optString("full_name", ""))
                .put("service_name", service.optString("service_name_ar", service.optString("service_name", "الخدمة")))
                .put("amount_cents", amountCents).put("payment_method", paymentMethod)
                .put("status", status).put("activation_command", command)
                .put("provider_phone", SmsTextAnalysis.normalizePhone(providerPhone))
                .put("attempt_count", if (isCredit) 1 else 0).put("duplicate", false)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return result
    }

    /** Strict bank reconciliation: all configured identifiers and amount/balance arithmetic must match. */
    fun acceptBankPayment(sender: String, bankNameInSms: String, notice: SmsTextAnalysis.BankNotice, configuredSender: String? = null, configuredBank: String? = null): JSONObject? {
        val now = System.currentTimeMillis()
        val expectedSender = SmsTextAnalysis.normalizePhone(configuredSender?.ifBlank { helper.getSetting("system_bank_sender_phone") } ?: helper.getSetting("system_bank_sender_phone"))
        val expectedBank = SmsTextAnalysis.normalize(configuredBank?.ifBlank { helper.getSetting("system_bank_name") } ?: helper.getSetting("system_bank_name"))
        if (expectedSender.length < 7 || SmsTextAnalysis.normalizePhone(sender) != expectedSender) return null
        if (expectedBank.isBlank() || SmsTextAnalysis.normalize(bankNameInSms) != expectedBank) return null
        if (notice.amountCents <= 0L) return null

        db.beginTransaction()
        try {
            val matching = mutableListOf<JSONObject>()
            db.rawQuery(
                "SELECT * FROM sms_activation_requests WHERE status='awaiting_payment' AND payment_method='bank' AND amount_cents=? AND created_at>?",
                arrayOf(notice.amountCents.toString(), (now - 24L * 60 * 60 * 1000).toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = cursorToJson(cursor)
                    if (SmsTextAnalysis.normalize(row.optString("customer_name")) == SmsTextAnalysis.normalize(notice.accountName)) matching += row
                }
            }
            if (matching.size != 1) return null
            val request = matching.single()
            val id = request.getLong("id")
            val updated = ContentValues().apply {
                put("status", "pending_provider")
                put("payment_method", "bank")
                put("bank_reference", "bank-sms:$now")
                put("updated_at", now)
            }
            if (db.update("sms_activation_requests", updated, "id=? AND status='awaiting_payment'", arrayOf(id.toString())) != 1) return null
            request.put("status", "pending_provider").put("payment_method", "bank")
            db.setTransactionSuccessful()
            return request
        } finally { db.endTransaction() }
    }

    fun getRequest(id: Long): JSONObject? = db.rawQuery(
        "SELECT * FROM sms_activation_requests WHERE id=?", arrayOf(id.toString())
    ).use { if (it.moveToFirst()) cursorToJson(it) else null }

    fun markDispatching(id: Long, attempt: Int): Boolean {
        val values = ContentValues().apply {
            put("status", "pending_provider")
            put("attempt_count", attempt)
            put("updated_at", System.currentTimeMillis())
        }
        return db.update("sms_activation_requests", values, "id=? AND status IN ('pending_provider','retry_ready')", arrayOf(id.toString())) == 1
    }

    fun markReconciliation(id: Long): Boolean {
        val values = ContentValues().apply { put("status", "reconciling"); put("updated_at", System.currentTimeMillis()) }
        return db.update("sms_activation_requests", values, "id=? AND status='pending_provider'", arrayOf(id.toString())) == 1
    }

    fun markUncertain(id: Long, reason: String): Boolean = updateStatus(id, "uncertain", reason)
    fun markFailed(id: Long, reason: String): Boolean = updateStatus(id, "failed", reason)

    private fun updateStatus(id: Long, status: String, reason: String): Boolean {
        val values = ContentValues().apply { put("status", status); put("status_message", reason.take(300)); put("updated_at", System.currentTimeMillis()) }
        return db.update("sms_activation_requests", values, "id=? AND status NOT IN ('success','failed')", arrayOf(id.toString())) == 1
    }

    fun claimIncoming(phone: String, message: String, receivedAt: Long): Boolean {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${SmsTextAnalysis.normalizePhone(phone)}|${SmsTextAnalysis.normalize(message)}|$receivedAt".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val values = ContentValues().apply {
            put("message_hash", digest)
            put("phone", SmsTextAnalysis.normalizePhone(phone))
            put("message_preview", message.take(100))
            put("processed_at", System.currentTimeMillis())
        }
        return db.insertWithOnConflict("sms_processed_hashes", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Persistent throttle: at most one automated reply per 15 seconds and 20 per number per day. */
    fun claimReply(phone: String): Boolean {
        val normalized = SmsTextAnalysis.normalizePhone(phone)
        val now = System.currentTimeMillis()
        val midnight = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        db.beginTransaction()
        try {
            var last = 0L; var dayStart = 0L; var count = 0
            db.rawQuery("SELECT last_reply_at,day_start,daily_count FROM sms_rate_limits WHERE phone=?", arrayOf(normalized)).use { cursor ->
                if (cursor.moveToFirst()) { last = cursor.getLong(0); dayStart = cursor.getLong(1); count = cursor.getInt(2) }
            }
            if (dayStart != midnight) { dayStart = midnight; count = 0 }
            if (now - last < 15_000L || count >= 20) return false
            val values = ContentValues().apply {
                put("phone", normalized); put("last_reply_at", now); put("day_start", dayStart)
                put("daily_count", count + 1); put("updated_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(now)))
            }
            db.insertWithOnConflict("sms_rate_limits", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** Returns a request only when one and only one operation can be attributed to the provider SMS. */
    fun findUniqueProviderRequest(sender: String, body: String? = null): JSONObject? {
        val phone = SmsTextAnalysis.normalizePhone(sender)
        val rows = mutableListOf<JSONObject>()
        db.rawQuery(
            "SELECT * FROM sms_activation_requests WHERE provider_phone=? AND status IN ('pending_provider','reconciling','retry_ready','uncertain') AND created_at>? ORDER BY created_at",
            arrayOf(phone, (System.currentTimeMillis() - 60L * 60 * 1000).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val row = cursorToJson(cursor)
                rows += row
            }
        }
        if (body != null) {
            val phoneTokens = Regex("(?<![0-9-])\\+?[0-9][0-9\\s().-]{5,}[0-9](?![0-9])")
                .findAll(SmsTextAnalysis.normalizeDigits(body))
                .map { SmsTextAnalysis.normalizePhone(it.value) }
                .filter { it.length >= 7 }
                .toSet()
            val exactPhoneRows = rows.filter { SmsTextAnalysis.normalizePhone(it.optString("customer_phone")) in phoneTokens }
            if (exactPhoneRows.size == 1) return exactPhoneRows.single()
            if (exactPhoneRows.isNotEmpty()) return null
            if (phoneTokens.isNotEmpty()) return null
        }
        return rows.singleOrNull()
    }

    /** A second USSD attempt is permitted once only, and only if queried balance is unchanged. */
    fun prepareSafeRetry(sender: String, newBalanceCents: Long): JSONObject? {
        val request = findUniqueProviderRequest(sender) ?: return null
        if (request.optString("status") != "reconciling") return null
        val attempt = request.optInt("attempt_count")
        val before = if (request.has("provider_balance_before_cents") && !request.isNull("provider_balance_before_cents")) request.optLong("provider_balance_before_cents") else -1L
        if (attempt != 1 || before < 0 || newBalanceCents != before) {
            markUncertain(request.getLong("id"), "رصيد المورد لم يثبت عدم الخصم؛ لم تتم إعادة المحاولة")
            return null
        }
        val values = ContentValues().apply { put("status", "retry_ready"); put("attempt_count", 2); put("updated_at", System.currentTimeMillis()) }
        if (db.update("sms_activation_requests", values, "id=? AND status='reconciling' AND attempt_count=1", arrayOf(request.getLong("id").toString())) != 1) return null
        request.put("status", "retry_ready").put("attempt_count", 2)
        return request
    }

    /** Creates a single activation ledger entry and posts credit only after provider success. */
    fun completeSuccess(requestId: Long, providerMessage: String): JSONObject? {
        db.beginTransaction()
        try {
            val request = getRequest(requestId) ?: return null
            if (request.optString("status") == "success") { db.setTransactionSuccessful(); return request }
            if (request.optString("status") !in setOf("pending_provider", "reconciling", "retry_ready", "uncertain")) return null
            val code = "SMS-${requestId}-${System.currentTimeMillis()}"
            val amount = request.optLong("amount_cents") / 100.0
            val activationValues = ContentValues().apply {
                put("activation_code", code)
                put("customer_id", request.optInt("customer_id"))
                put("service_id", request.optInt("service_id"))
                put("phone_number", request.optString("customer_phone"))
                put("amount", amount)
                put("payment_method", request.optString("payment_method"))
                put("activation_command", request.optString("activation_command"))
                put("status", "success")
                put("status_message", providerMessage.take(300))
                put("activated_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                put("created_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            }
            val activationId = db.insertOrThrow("activations", null, activationValues)
            if (request.optString("payment_method") == "credit") {
                db.execSQL(
                    "UPDATE customers SET current_balance=current_balance+?, total_purchases=total_purchases+?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    arrayOf(amount, amount, request.optInt("customer_id"))
                )
            }
            val values = ContentValues().apply {
                put("status", "success"); put("activation_id", activationId)
                put("status_message", providerMessage.take(300)); put("updated_at", System.currentTimeMillis())
            }
            db.update("sms_activation_requests", values, "id=?", arrayOf(requestId.toString()))
            request.put("status", "success").put("activation_id", activationId)
            db.setTransactionSuccessful()
            return request
        } finally { db.endTransaction() }
    }

    private fun cursorToJson(cursor: android.database.Cursor): JSONObject = JSONObject().apply {
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            if (cursor.isNull(i)) put(name, JSONObject.NULL)
            else when (cursor.getType(i)) {
                android.database.Cursor.FIELD_TYPE_INTEGER -> put(name, cursor.getLong(i))
                android.database.Cursor.FIELD_TYPE_FLOAT -> put(name, cursor.getDouble(i))
                else -> put(name, cursor.getString(i))
            }
        }
    }

    private fun decimalToCents(value: Double): Long? = try {
        if (!value.isFinite()) null
        else java.math.BigDecimal.valueOf(value).movePointRight(2)
            .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
    } catch (_: Exception) { null }
}

private object BigCents {
    fun format(cents: Long): String = java.math.BigDecimal.valueOf(cents, 2).setScale(2).toPlainString()
}
