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
    fun parsesArabicBankReceiptAmountAndIgnoresAnyBalance() {
        val notice = SmsTextAnalysis.parseBankNotice(
            "الشرعي لكم 2,000 ر ي\nمن حساب: عبدالسلام صالح جحيش\nلكم: 6,085.06 ر ي",
            "الشرعي"
        )
        requireNotNull(notice)
        assertEquals("عبدالسلام صالح جحيش", notice.accountName)
        assertEquals(200_000L, notice.amountCents)
    }

    @Test
    fun rejectsReceiptWithoutConfiguredBankNameAndAcceptsReceiptWithoutBalance() {
        assertNull(SmsTextAnalysis.parseBankNotice("الشرعي لكم 2,000 ر ي من حساب: عميل لكم: 6,085 ر ي", "بنك آخر"))
        val receipt = SmsTextAnalysis.parseBankNotice("الشرعي من حساب: عميل لكم: 2,000 ر ي", "الشرعي")
        requireNotNull(receipt)
        assertEquals(200_000L, receipt.amountCents)
    }

    @Test
    fun bankReceiptNewBalanceDoesNotAffectTheParsedPaymentAmount() {
        val notice = SmsTextAnalysis.parseBankNotice(
            "مصرف الأمل\nمن حساب: أحمد محمد\nلكم: 25.50\nالرصيد لكم: 0.00",
            "مصرف الأمل"
        )
        requireNotNull(notice)
        assertEquals("أحمد محمد", notice.accountName)
        assertEquals(2_550L, notice.amountCents)
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
    fun substitutesPhoneInAllSupportedPlaceholderFormsWithoutRegexErrors() {
        val expected = "*777000001#"
        listOf("*(phone)#", "*{phone}#", "*{{ phone }}#", "*%phone%#", "*phone#").forEach { template ->
            assertEquals(expected, SmsTextAnalysis.buildUssdCommand(template, "777000001"))
        }
    }

    @Test
    fun directUssdReplacementNormalizesArabicIndicAndInternationalCustomerPhones() {
        val command = SmsTextAnalysis.buildUssdCommand(
            "*123*(phone)*300#",
            "+٩٦٧ ٧٧٧ ٠٠٠ ٠٠١"
        )
        assertEquals("*123*777000001*300#", command)
        assertTrue(command!!.none { it == '(' || it == ')' || it == '{' || it == '}' })
    }

    @Test
    fun directUssdReplacementReplacesEveryCustomerPhonePlaceholder() {
        val command = SmsTextAnalysis.buildUssdCommand(
            "*700*(phone)*{phone}*%phone%#",
            "777123456"
        )
        assertEquals("*700*777123456*777123456*777123456#", command)
        assertFalse(command!!.contains("phone", ignoreCase = true))
    }

    @Test
    fun directUssdReplacementRejectsMissingOrInvalidCustomerPhone() {
        listOf("", "12345", "customer-phone", "++967").forEach { phone ->
            assertNull(
                "A direct USSD command must never be sent with an invalid customer phone: $phone",
                SmsTextAnalysis.buildUssdCommand("*123*(phone)#", phone)
            )
        }
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

    @Test
    fun customerMessageMatchesMax300PackageWithoutRequiringExactWording() {
        val services = org.json.JSONArray()
            .put(org.json.JSONObject()
                .put("id", 7)
                .put("service_name_ar", "باقة ماكس 300")
                .put("service_code", "MAX-300")
                .put("status", "active")
                .put("is_active", 1))

        assertEquals(7, SmsTextAnalysis.findService("تفعيل باقة ماكس300", services)!!.getInt("id"))
        assertEquals(7, SmsTextAnalysis.findService("أريد ماكس ٣٠٠", services)!!.getInt("id"))
    }

    @Test
    fun providerSmsStatesAreSeparatedFromPendingOrNegativeMessages() {
        assertTrue(SmsTextAnalysis.isSuccessMessage("تم تفعيل باقة ماكس300 للرقم 777123456"))
        assertTrue(SmsTextAnalysis.isFailureMessage("تعذر تفعيل باقة ماكس300: الرصيد غير كاف"))
        assertFalse(SmsTextAnalysis.isSuccessMessage("طلب تفعيل باقة ماكس300 قيد المعالجة"))
    }
}
