package com.aistudio.dieselstationsms.kxmpzq

import java.util.Locale

/** قواعد نقية قابلة للاختبار، بدون Regex ديناميكي من إدخال المستخدم. */
object SmsActivationRules {
    private val phoneToken = Regex("\\bphone\\b|%phone%|\\${'$'}\\{phone}|\\(phone\\)", RegexOption.IGNORE_CASE)

    fun validateUssdTemplate(template: String): Boolean {
        val value = template.trim()
        if (value.isEmpty() || value.length > 160) return false
        if (!phoneToken.containsMatchIn(value)) return false
        // نسمح فقط بنص USSD المعتاد ورمز phone، ونمنع أي محارف تحكم أو حقن.
        return value.none { it == '\r' || it == '\n' || it == '\u0000' || it == '"' || it == '\'' }
    }

    fun renderUssd(template: String, phone: String): String? {
        val normalized = normalizePhone(phone)
        if (normalized.length < 9 || !validateUssdTemplate(template)) return null
        return template
            .replace(Regex("\\(phone\\)", RegexOption.IGNORE_CASE), normalized)
            .replace(Regex("%phone%", RegexOption.IGNORE_CASE), normalized)
            .replace(Regex("\\${'$'}\\{phone}", RegexOption.IGNORE_CASE), normalized)
            .replace(Regex("\\bphone\\b", RegexOption.IGNORE_CASE), normalized)
    }

    fun normalizePhone(phone: String): String = phone.filter(Char::isDigit).takeLast(9)

    fun providerPrefix(phone: String): String = normalizePhone(phone).take(2)

    fun parseActivationRequest(message: String): String? {
        val normalized = message.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return null
        return normalized
    }
}
