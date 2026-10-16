package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test
    fun substitutesCustomerPhoneIntoFullUssdTemplate() {
        assertEquals(
            "300#*777000001*130*",
            SmsTextAnalysis.buildUssdCommand("300#*(phone)*130*", "+967 777 000 001")
        )
        assertNull(SmsTextAnalysis.buildUssdCommand("300#*130*", "777000001"))
        assertNull(SmsTextAnalysis.buildUssdCommand("300#*(phone)*SEND*", "777000001"))
    }

    @Test
    fun parsesArabicThousandsAndDecimalSeparatorsWithoutTruncatingCents() {
        val notice = SmsTextAnalysis.parseBankNotice(
            "مصرف الأمل\nمن حساب: أحمد محمد\nلكم: ٢٬٠٠٠٫٥٠ ر.ي.\nالرصيد لكم: ٦٬٠٨٥٫٠٦ ر.ي.",
            "مصرف الأمل"
        )
        requireNotNull(notice)
        assertEquals("أحمد محمد", notice.accountName)
        assertEquals(200_050L, notice.amountCents)
        assertEquals(608_506L, notice.newBalanceCents)
        assertEquals(200_050L, SmsTextAnalysis.lastMoneyCents("رصيدك الحالي ٢٬٠٠٠٫٥٠ ريال"))
        assertNull(SmsTextAnalysis.lastMoneyCents("الرصيد ٢٬٠٠٠٫٥٠ ثم مبلغ ملتبس 10.005"))
        assertNull(SmsTextAnalysis.lastMoneyCents("الرصيد -٢٬٠٠٠٫٥٠ ريال"))
    }

    @Test
    fun rejectsAmbiguousAmountsAndAmountsWithMoreThanTwoDecimalPlaces() {
        assertNull(SmsTextAnalysis.toCents("1.005"))
        assertNull(SmsTextAnalysis.toCents("ليس مبلغاً"))
        assertNull(SmsTextAnalysis.toCents("12,34"))
        assertNull(SmsTextAnalysis.toCents("1 2"))
        assertNull(SmsTextAnalysis.toCents("-1.00"))
        assertNull(SmsTextAnalysis.parseBankNotice(
            "مصرف الأمل من حساب: أحمد محمد لكم: 10.005 لكم: 20.00",
            "مصرف الأمل"
        ))
        assertNull(SmsTextAnalysis.parseBankNotice(
            "مصرف الأمل من حساب: أحمد محمد لكم: 10.00 لكم: 15.00 لكم: 20.00",
            "مصرف الأمل"
        ))
        assertNull(SmsTextAnalysis.parseBankNotice(
            "مصرف الأملاد من حساب: أحمد محمد لكم: 10.00 لكم: 20.00",
            "مصرف الأمل"
        ))
    }

    @Test
    fun negativeProviderMessagesMustNeverBeClassifiedAsSuccess() {
        val negativeMessages = listOf(
            "Activation not successful",
            "Activation not a success",
            "The number was not activated",
            "Unsuccessful transaction",
            "لم يتم التفعيل بنجاح",
            "لم تنجح عملية التفعيل",
            "لا يوجد نجاح في العملية",
            "فشل التفعيل"
        )
        negativeMessages.forEach { message ->
            assertTrue("Expected failure classification for: $message", SmsTextAnalysis.isFailureMessage(message))
            assertFalse("Negative result must not be treated as success: $message", SmsTextAnalysis.isSuccessMessage(message))
        }
        assertFalse(SmsTextAnalysis.isSuccessMessage("Request received; processing"))
    }

    @Test
    fun serviceMatchingRejectsTiesAndIgnoresInactiveRows() {
        val services = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 1).put("service_name_ar", "باقة إنترنت").put("status", "active").put("is_active", 1))
            .put(org.json.JSONObject().put("id", 2).put("service_name_ar", "باقة إنترنت").put("status", "active").put("is_active", 1))
        assertNull(SmsTextAnalysis.findService("أريد باقة إنترنت", services))

        val inactive = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 3).put("service_name_ar", "باقة إنترنت").put("status", "inactive").put("is_active", 0))
        assertNull(SmsTextAnalysis.findService("أريد باقة إنترنت", inactive))
    }
}
