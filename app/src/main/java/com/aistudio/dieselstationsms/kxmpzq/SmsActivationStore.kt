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
                idempotency_key TEXT NOT NULL DEFAULT '', dispatched_attempt INTEGER NOT NULL DEFAULT 0,
                bank_reference TEXT NOT NULL DEFAULT '', activation_id INTEGER,
                status_message TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        ensureColumn("sms_activation_requests", "idempotency_key", "TEXT NOT NULL DEFAULT ''")
        ensureColumn("sms_activation_requests", "dispatched_attempt", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn("activations", "sms_request_id", "INTEGER")
        ensureColumn("activations", "idempotency_key", "TEXT")
        db.execSQL("UPDATE sms_activation_requests SET idempotency_key='legacy-request-' || id WHERE idempotency_key=''")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_sms_activation_idempotency ON sms_activation_requests(idempotency_key)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_activations_sms_request ON activations(sms_request_id) WHERE sms_request_id IS NOT NULL")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_activations_idempotency ON activations(idempotency_key) WHERE idempotency_key IS NOT NULL")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sms_activation_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, request_id INTEGER NOT NULL,
            idempotency_key TEXT NOT NULL, event_type TEXT NOT NULL, attempt INTEGER NOT NULL DEFAULT 0,
            detail TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
            UNIQUE(idempotency_key,event_type,attempt))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_activation_status_provider ON sms_activation_requests(status, provider_phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_activation_customer_service ON sms_activation_requests(customer_id, service_id, created_at)")
    }

    private fun ensureColumn(table: String, column: String, definition: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val index = cursor.getColumnIndex("name")
            index >= 0 && generateSequence { if (cursor.moveToNext()) cursor.getString(index) else null }.any { it == column }
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    /** Creates an order, applying credit policy and reserving credit atomically. */
    fun createRequest(
        customer: JSONObject,
        service: JSONObject,
        phone: String,
        idempotencyKey: String = "smsv1_${java.util.UUID.randomUUID()}"
    ): JSONObject {
        val customerId = customer.optInt("id")
        val serviceId = service.optInt("id")
        val amountCents = decimalToCents(service.optDouble("price")) ?: 0L
        val normalizedPhone = SmsTextAnalysis.normalizePhone(phone)
        // Older databases stored the complete USSD template in activation_code.
        // Prefer the new field, but keep those user-created services usable after upgrade.
        val command = service.optString("activation_command", "").trim()
            .ifBlank { service.optString("activation_code", "").trim() }
        val telecom = helper.getTelecomCompanyById(service.optInt("telecom_company_id", 0))
        val providerPhone = providerPhone(telecom)
        val now = System.currentTimeMillis()
        val result = JSONObject()
        if (customerId <= 0 || serviceId <= 0 || amountCents <= 0 || !idempotencyKey.matches(Regex("[A-Za-z0-9_-]{16,100}"))) return result.put("error", "بيانات الطلب أو مفتاح منع التكرار غير صالح")
        if (normalizedPhone.length < 7 || SmsTextAnalysis.buildUssdCommand(command, normalizedPhone) == null) {
            return result.put("error", "قالب أمر التفعيل أو رقم العميل غير مكتمل؛ لم يُنشأ طلب")
        }
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT * FROM sms_activation_requests WHERE idempotency_key=? LIMIT 1",
                arrayOf(idempotencyKey)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val existing = cursorToJson(cursor).put("duplicate", true).put("idempotent_replay", true)
                    db.setTransactionSuccessful()
                    return existing
                }
            }
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
                put("idempotency_key", idempotencyKey)
                put("provider_phone", SmsTextAnalysis.normalizePhone(providerPhone))
                if (providerBalance != null) put("provider_balance_before_cents", providerBalance)
                put("attempt_count", if (isCredit) 1 else 0)
                put("created_at", now)
                put("updated_at", now)
            }
            val id = db.insertOrThrow("sms_activation_requests", null, values)
            appendEvent(id, idempotencyKey, "request_created", 0, "payment_method=$paymentMethod;amount_cents=$amountCents")
            result.put("id", id).put("customer_id", customerId).put("service_id", serviceId)
                .put("customer_phone", normalizedPhone)
                .put("customer_name", customer.optString("full_name", ""))
                .put("service_name", service.optString("service_name_ar", service.optString("service_name", "الخدمة")))
                .put("amount_cents", amountCents).put("payment_method", paymentMethod)
                .put("status", status).put("activation_command", command)
                .put("idempotency_key", idempotencyKey)
                .put("provider_phone", SmsTextAnalysis.normalizePhone(providerPhone))
                .put("attempt_count", if (isCredit) 1 else 0).put("duplicate", false)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return result
    }

    /** Resolve the trusted sender for provider result SMS for new and legacy records. */
    private fun providerPhone(telecom: JSONObject?): String {
        val candidates = listOf(
            telecom?.optString("balance_phone").orEmpty(),
            telecom?.optString("ussd_phone").orEmpty(),
            helper.getSetting("activation_provider_phone")
        )
        return candidates.firstOrNull { SmsTextAnalysis.normalizePhone(it).length >= 7 }
            ?.let(SmsTextAnalysis::normalizePhone).orEmpty()
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
                put("bank_reference", "bank-request:$id")
                put("updated_at", now)
            }
            if (db.update("sms_activation_requests", updated, "id=? AND status='awaiting_payment'", arrayOf(id.toString())) != 1) return null
            appendEvent(id, request.optString("idempotency_key"), "bank_payment_matched", 0, "amount_cents=${notice.amountCents}")
            request.put("status", "pending_provider").put("payment_method", "bank")
            db.setTransactionSuccessful()
            return request
        } finally { db.endTransaction() }
    }

    fun getRequest(id: Long): JSONObject? = db.rawQuery(
        "SELECT * FROM sms_activation_requests WHERE id=?", arrayOf(id.toString())
    ).use { if (it.moveToFirst()) cursorToJson(it) else null }

    fun markDispatching(id: Long, attempt: Int): Boolean {
        if (attempt !in 1..2) return false
        val values = ContentValues().apply {
            put("status", "pending_provider")
            put("attempt_count", attempt)
            put("dispatched_attempt", attempt)
            put("updated_at", System.currentTimeMillis())
        }
        db.beginTransaction()
        try {
            val updated = db.update(
                "sms_activation_requests", values,
                "id=? AND status IN ('pending_provider','retry_ready') AND dispatched_attempt=?",
                arrayOf(id.toString(), (attempt - 1).toString())
            ) == 1
            if (!updated) return false
            val request = getRequest(id) ?: return false
            appendEvent(id, request.optString("idempotency_key"), "ussd_dispatch_claimed", attempt, "")
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun markReconciliation(id: Long): Boolean {
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("status", "reconciling"); put("updated_at", System.currentTimeMillis()) }
            if (db.update("sms_activation_requests", values, "id=? AND status='pending_provider'", arrayOf(id.toString())) != 1) return false
            val request = getRequest(id) ?: return false
            appendEvent(id, request.optString("idempotency_key"), "reconciliation_started", request.optInt("attempt_count"), "")
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun markUncertain(id: Long, reason: String): Boolean = updateStatus(id, "uncertain", reason)
    fun markFailed(id: Long, reason: String): Boolean = updateStatus(id, "failed", reason)

    private fun updateStatus(id: Long, status: String, reason: String): Boolean {
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("status", status); put("status_message", reason.take(300)); put("updated_at", System.currentTimeMillis()) }
            if (db.update("sms_activation_requests", values, "id=? AND status NOT IN ('success','failed')", arrayOf(id.toString())) != 1) return false
            val request = getRequest(id) ?: return false
            appendEvent(id, request.optString("idempotency_key"), "status_$status", request.optInt("attempt_count"), reason.take(200))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    private fun appendEvent(requestId: Long, key: String, type: String, attempt: Int, detail: String) {
        db.execSQL(
            "INSERT OR IGNORE INTO sms_activation_events(request_id,idempotency_key,event_type,attempt,detail,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf(requestId, key, type, attempt, detail.take(200), System.currentTimeMillis())
        )
    }

    fun claimIncoming(phone: String, message: String, receivedAt: Long): Boolean {
        val digest = smsInboundIdempotencyKey(phone, message, receivedAt).removePrefix("smsv1_")
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

    /** Balance equality alone is not proof of non-execution; require trusted explicit provider evidence. */
    fun prepareSafeRetry(sender: String, newBalanceCents: Long, providerConfirmedNotExecuted: Boolean = false): JSONObject? {
        val request = findUniqueProviderRequest(sender) ?: return null
        if (request.optString("status") != "reconciling") return null
        val attempt = request.optInt("attempt_count")
        val before = if (request.has("provider_balance_before_cents") && !request.isNull("provider_balance_before_cents")) request.optLong("provider_balance_before_cents") else -1L
        if (!providerConfirmedNotExecuted || attempt != 1 || before < 0 || newBalanceCents != before) {
            markUncertain(request.getLong("id"), "لم يثبت المورد صراحةً عدم تنفيذ العملية؛ لم تتم إعادة المحاولة")
            return null
        }
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("status", "retry_ready"); put("attempt_count", 2); put("updated_at", System.currentTimeMillis()) }
            if (db.update("sms_activation_requests", values, "id=? AND status='reconciling' AND attempt_count=1 AND dispatched_attempt=1", arrayOf(request.getLong("id").toString())) != 1) return null
            appendEvent(request.getLong("id"), request.optString("idempotency_key"), "retry_authorized", 2, "provider_confirmed_not_executed; balance_unchanged")
            request.put("status", "retry_ready").put("attempt_count", 2)
            db.setTransactionSuccessful()
            return request
        } finally { db.endTransaction() }
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
                put("sms_request_id", requestId)
                put("idempotency_key", request.optString("idempotency_key"))
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
            if (db.update("sms_activation_requests", values, "id=? AND status IN ('pending_provider','reconciling','retry_ready','uncertain')", arrayOf(requestId.toString())) != 1) return null
            appendEvent(requestId, request.optString("idempotency_key"), "provider_activation_confirmed", request.optInt("attempt_count"), "")
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

/** Stable across Android SMS broadcast redelivery; different received SMS events remain distinct. */
internal fun smsInboundIdempotencyKey(phone: String, message: String, receivedAt: Long): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${SmsTextAnalysis.normalizePhone(phone)}|${SmsTextAnalysis.normalize(message)}|$receivedAt".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "smsv1_$digest"
}

private object BigCents {
    fun format(cents: Long): String = java.math.BigDecimal.valueOf(cents, 2).setScale(2).toPlainString()
}
