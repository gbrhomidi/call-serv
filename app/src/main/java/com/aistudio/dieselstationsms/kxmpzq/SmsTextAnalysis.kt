package com.aistudio.dieselstationsms.kxmpzq

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** Deterministic SMS parsing: ambiguous messages are deliberately not auto-executed. */
internal object SmsTextAnalysis {
    private val bankAmountPattern = Regex("لكم\\s*[:：]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    private val accountPattern = Regex("من\\s*حساب\\s*[:：]?\\s*(.+?)(?=\\s+لكم(?:\\s|[:：]))", RegexOption.IGNORE_CASE)
    private val successPattern = Regex("(تم\\s*(التفعيل|التفعيل بنجاح|بن?جاح)|نجاح|successful|success|activated)", RegexOption.IGNORE_CASE)
    private val failurePattern = Regex("(فشل|غير\\s*كاف|لم\\s*يتم|مرفوض|failed|insufficient)", RegexOption.IGNORE_CASE)

    fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { c ->
            append(when (c) {
                in '٠'..'٩' -> ('0'.code + (c.code - '٠'.code)).toChar()
                in '۰'..'۹' -> ('0'.code + (c.code - '۰'.code)).toChar()
                else -> c
            })
        }
    }

    fun normalize(value: String): String {
        val noMarks = normalizeDigits(value).lowercase()
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
        return noMarks.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    }

    fun normalizePhone(phone: String): String = normalizeDigits(phone).filter(Char::isDigit).takeLast(9)

    /** Replaces the full-command phone placeholder only after a payment/request is authorized. */
    fun buildUssdCommand(template: String, phone: String): String? {
        if (template.isBlank() || phone.isBlank()) return null
        val normalizedPhone = normalizePhone(phone)
        if (normalizedPhone.length < 7) return null
        val marker = Regex("(?i)\\(\\s*phone\\s*\\)|\\{\\{?\\s*phone\\s*}?\\}|%phone%|\\bphone\\b")
        if (!marker.containsMatchIn(template)) return null
        val command = marker.replace(template) { normalizedPhone }
        if (command.length !in 3..100 || !command.matches(Regex("[0-9*#+,;.\\-]+"))) return null
        return command
    }

    fun toCents(raw: String): Long? = try {
        BigDecimal(normalizeDigits(raw).replace(",", "").replace("٬", "").replace("٫", ".").trim())
            .setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
    } catch (_: Exception) { null }

    data class BankNotice(val accountName: String, val amountCents: Long, val newBalanceCents: Long)

    fun parseBankNotice(body: String, expectedBankName: String): BankNotice? {
        val bank = normalize(expectedBankName)
        val normalizedBody = normalize(body)
        if (bank.isBlank() || !normalizedBody.contains(bank)) return null
        val amountMatches = bankAmountPattern.findAll(normalizeDigits(body)).toList()
        if (amountMatches.size < 2) return null
        val amount = toCents(amountMatches.first().groupValues[1]) ?: return null
        val total = toCents(amountMatches.last().groupValues[1]) ?: return null
        if (amount <= 0 || total <= 0) return null
        val account = accountPattern.find(normalizeDigits(body))?.groupValues?.get(1)?.trim()
            ?.trim(',', '،', ':', '：', ' ')
            ?.takeIf { it.isNotBlank() } ?: return null
        return BankNotice(account, amount, total)
    }

    fun isSuccessMessage(body: String): Boolean = successPattern.containsMatchIn(normalizeDigits(body))
    fun isFailureMessage(body: String): Boolean = failurePattern.containsMatchIn(normalizeDigits(body))

    /** Returns exactly one best match; no service is selected when the top score is tied. */
    fun findService(message: String, services: JSONArray): JSONObject? {
        val normalizedMessage = normalize(message)
        if (normalizedMessage.isBlank()) return null
        val messageTokens = normalizedMessage.split(' ').toSet()
        val stopWords = setOf("اريد", "ابغي", "ابي", "طلب", "تفعيل", "فعل", "لي", "ل", "الى", "الي", "رقم", "هاتف", "خدمه", "الخدمه", "من", "على", "please", "activate", "buy")
        val filteredMessageTokens = messageTokens - stopWords
        val scored = mutableListOf<Pair<Int, JSONObject>>()
        for (i in 0 until services.length()) {
            val service = services.optJSONObject(i) ?: continue
            if (service.optInt("is_active", 1) != 1 || service.optString("status", "active") != "active") continue
            val fields = listOf(
                service.optString("service_name_ar"), service.optString("service_name"),
                service.optString("service_code"), service.optString("description"),
                service.optString("service_type")
            ).filter(String::isNotBlank).map(::normalize)
            val nameFields = fields.take(2).filter(String::isNotBlank)
            val exact = nameFields.filter { it.length >= 3 && normalizedMessage.contains(it) }
            var score = if (exact.isNotEmpty()) 100 + (exact.maxOfOrNull(String::length) ?: 0) else 0
            if (score == 0) {
                val serviceTokens = nameFields.flatMap { it.split(' ') }.toSet() - stopWords
                val overlaps = serviceTokens.intersect(filteredMessageTokens)
                if (overlaps.isNotEmpty()) score = 15 + overlaps.size * 10
                // A number alone is safe only if it identifies a single service.
                val numericTokens = fields.flatMap { it.split(' ') }.filter { it.all(Char::isDigit) && it.isNotEmpty() }.toSet()
                if (numericTokens.intersect(filteredMessageTokens).isNotEmpty()) score += 10
            }
            if (score > 0) scored += score to service
        }
        if (scored.isEmpty()) return null
        val maxScore = scored.maxOf { it.first }
        val best = scored.filter { it.first == maxScore }
        return best.singleOrNull()?.second
    }
}
