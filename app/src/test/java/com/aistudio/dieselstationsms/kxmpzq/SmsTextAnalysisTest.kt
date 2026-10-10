package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsTextAnalysisTest {
    @Test
    fun parsesArabicBankReceiptAmountAndResultingBalance() {
        val notice = SmsTextAnalysis.parseBankNotice(
            "الشرعي لكم 2,000 ر ي\nمن حساب: عبدالسلام صالح جحيش\nلكم: 6,085.06 ر ي",
            "الشرعي"
        )
        requireNotNull(notice)
        assertEquals("عبدالسلام صالح جحيش", notice.accountName)
        assertEquals(200_000L, notice.amountCents)
        assertEquals(608_506L, notice.newBalanceCents)
    }

    @Test
    fun rejectsReceiptWithoutConfiguredBankNameOrCompleteBalance() {
        assertNull(SmsTextAnalysis.parseBankNotice("الشرعي لكم 2,000 ر ي من حساب: عميل لكم: 6,085 ر ي", "بنك آخر"))
        assertNull(SmsTextAnalysis.parseBankNotice("الشرعي لكم 2,000 ر ي من حساب: عميل", "الشرعي"))
    }

    @Test
    fun normalizesArabicIndicDigitsAndPhoneNumbers() {
        assertEquals("2000.50", SmsTextAnalysis.normalizeDigits("٢٠٠٠.٥٠"))
        assertEquals("777000001", SmsTextAnalysis.normalizePhone("+967 777 000 001"))
        assertEquals(200_050L, SmsTextAnalysis.toCents("٢٬٠٠٠.٥٠"))
    }

    @Test
    fun recognizesProviderSuccessAndFailureSeparately() {
        assertTrue(SmsTextAnalysis.isSuccessMessage("تم التفعيل بنجاح"))
        assertTrue(SmsTextAnalysis.isFailureMessage("الرصيد غير كاف"))
    }
}
