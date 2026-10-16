package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsActivationRulesTest {
    @Test fun acceptsSupportedPhoneTokensWithoutDynamicRegex() {
        assertTrue(SmsActivationRules.validateUssdTemplate("*123*phone#"))
        assertTrue(SmsActivationRules.validateUssdTemplate("*123*%phone%#"))
        assertTrue(SmsActivationRules.validateUssdTemplate("*123*${'$'}{phone}#"))
    }

    @Test fun rejectsMalformedOrUnsafeTemplates() {
        assertFalse(SmsActivationRules.validateUssdTemplate("*123*#"))
        assertFalse(SmsActivationRules.validateUssdTemplate("*123*phone\n#"))
    }

    @Test fun rendersNormalizedNineDigitPhone() {
        assertEquals("*123*777123456#", SmsActivationRules.renderUssd("*123*phone#", "+967 777123456"))
        assertEquals("77", SmsActivationRules.providerPrefix("+967 771234567"))
    }
}
