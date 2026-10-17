package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsActivationStoreTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var store: SmsActivationStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase("telecom_activator.db")
        helper = DatabaseHelper.getInstance(context)
        store = SmsActivationStore(context)
        setDefaultSettings()
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase("telecom_activator.db")
    }

    @Test
    fun `bank reconciliation requires exact sender bank account and amount but not expected balance and accepts once`() {
        helper.setSetting("system_bank_expected_balance", "not configured")
        val customer = customer(name = "علي محمد", phone = "777111222")
        val service = service(price = 25.50)
        val request = store.createRequest(customer, service, customer.getString("phone1"))
        assertEquals("awaiting_payment", request.getString("status"))
        val notice = SmsTextAnalysis.BankNotice("علي محمد", 2_550)

        assertNull(store.acceptBankPayment("777000000", "مصرف الأمل", notice))
        assertNull(store.acceptBankPayment("+967777000001", "مصرف آخر", notice))
        assertNull(store.acceptBankPayment("+967777000001", "مصرف الأمل", notice.copy(accountName = "علي محمد صالح")))
        assertNull(store.acceptBankPayment("+967777000001", "مصرف الأمل", notice.copy(amountCents = 2_551)))
        assertEquals("awaiting_payment", store.getRequest(request.getLong("id"))!!.getString("status"))

        val accepted = store.acceptBankPayment("+967777000001", "مصرف الأمل", notice)
        assertNotNull(accepted)
        assertEquals("pending_provider", accepted!!.getString("status"))
        assertEquals("not configured", helper.getSetting("system_bank_expected_balance"))
        assertNull(store.acceptBankPayment("+967777000001", "مصرف الأمل", notice))
        assertEquals(1L, count("SELECT count(*) FROM sms_activation_requests WHERE status='pending_provider'"))
    }

    @Test
    fun `bank SMS processor reconciles the deposit before attempting USSD and blocks duplicate payment`() {
        shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.CALL_PHONE)
        val customer = customer(name = "علي محمد", phone = "777111222")
        val service = service(price = 25.50)
        val initial = store.createRequest(customer, service, customer.getString("phone1"))
        assertEquals("awaiting_payment", initial.getString("status"))

        SmsCommandProcessor.process(
            context,
            "+967777000001",
            "مصرف الأمل\nمن حساب: علي محمد\nلكم: 25.50\nرصيدكم لكم: 0.00"
        )

        assertEquals("", helper.getSetting("system_bank_expected_balance"))
        assertEquals("uncertain", store.getRequest(initial.getLong("id"))!!.getString("status"))
        assertEquals(0L, count("SELECT count(*) FROM activations"))
        assertTrue(store.createRequest(customer, service, customer.getString("phone1")).optBoolean("duplicate"))
    }

    @Test
    fun `ambiguous same-name same-amount payments are rejected without changing balance`() {
        val service = service(price = 10.00)
        val first = customer(name = "محمد أحمد", phone = "777111221")
        val second = customer(name = "محمد أحمد", phone = "777111223")
        val firstRequest = store.createRequest(first, service, first.getString("phone1"))
        val secondRequest = store.createRequest(second, service, second.getString("phone1"))
        assertFalse(firstRequest.optBoolean("duplicate"))
        assertFalse(secondRequest.optBoolean("duplicate"))

        val notice = SmsTextAnalysis.BankNotice("محمد أحمد", 1_000)
        assertNull(store.acceptBankPayment("777000001", "مصرف الأمل", notice))
        assertEquals("", helper.getSetting("system_bank_expected_balance"))
        assertEquals(2L, count("SELECT count(*) FROM sms_activation_requests WHERE status='awaiting_payment'"))
    }

    @Test
    fun `duplicate service requests are idempotent and credit is posted once after success`() {
        val customer = customer(name = "زبون آجل", phone = "777222333", allowCredit = true, creditLimit = 100.0)
        val service = service(price = 12.34)
        val request = store.createRequest(customer, service, customer.getString("phone1"))
        assertEquals("credit", request.getString("payment_method"))
        assertEquals(1_234L, request.getLong("amount_cents"))

        val duplicate = store.createRequest(customer, service, customer.getString("phone1"))
        assertTrue(duplicate.optBoolean("duplicate"))
        assertEquals(request.getLong("id"), duplicate.getLong("id"))

        val firstCompletion = store.completeSuccess(request.getLong("id"), "تم التفعيل بنجاح")
        val repeatedCompletion = store.completeSuccess(request.getLong("id"), "تم التفعيل بنجاح")
        assertNotNull(firstCompletion)
        assertNotNull(repeatedCompletion)
        assertEquals("success", repeatedCompletion!!.getString("status"))
        assertEquals(1L, count("SELECT count(*) FROM activations"))
        assertEquals(12.34, helper.getCustomerById(customer.getInt("id"))!!.getDouble("current_balance"), 0.001)
    }

    @Test
    fun `credit reservations enforce the limit across outstanding requests`() {
        val customer = customer(name = "حد ائتماني", phone = "777333444", allowCredit = true, creditLimit = 50.0)
        val first = store.createRequest(customer, service(price = 30.0), customer.getString("phone1"))
        val second = store.createRequest(customer, service(price = 25.0), customer.getString("phone1"))

        assertEquals("credit", first.getString("payment_method"))
        assertEquals("bank", second.getString("payment_method"))
        assertEquals("awaiting_payment", second.getString("status"))
    }

    @Test
    fun `credit limit comparison preserves cents and never rounds a due amount down`() {
        val customer = customer(
            name = "اختبار الكسور",
            phone = "777333445",
            allowCredit = true,
            creditLimit = 1.00,
            currentBalance = 0.29
        )
        val request = store.createRequest(customer, service(price = 0.72), customer.getString("phone1"))

        assertEquals("bank", request.getString("payment_method"))
        assertEquals("awaiting_payment", request.getString("status"))
    }

    @Test
    fun `credit reserved in retry-ready remains counted against limit`() {
        val customer = customer(name = "حجز أثناء الإعادة", phone = "777333446", allowCredit = true, creditLimit = 50.0)
        val first = store.createRequest(customer, service(price = 30.0), customer.getString("phone1"))
        val id = first.getLong("id")
        assertTrue(store.markDispatching(id, 1))
        assertTrue(store.markReconciliation(id))
        assertNotNull(store.prepareSafeRetry("777000001", 50_000))
        assertEquals("retry_ready", store.getRequest(id)!!.getString("status"))

        val second = store.createRequest(customer, service(price = 25.0), customer.getString("phone1"))
        assertEquals("bank", second.getString("payment_method"))
    }

    @Test
    fun `late provider success can reconcile an uncertain request without another activation`() {
        val customer = customer(name = "تأكيد متأخر", phone = "777444555", allowCredit = true, creditLimit = 100.0)
        val request = store.createRequest(customer, service(price = 8.0), customer.getString("phone1"))
        val id = request.getLong("id")
        assertTrue(store.markDispatching(id, 1))
        assertTrue(store.markReconciliation(id))
        assertTrue(store.markUncertain(id, "انتهت مهلة الرد"))

        val matched = store.findUniqueProviderRequest("777000001", "تم تفعيل الرقم 777444555 بنجاح")
        assertNotNull("A late trusted provider confirmation must remain reconcilable", matched)
        val completed = store.completeSuccess(id, "تم تفعيل الرقم 777444555 بنجاح")
        assertNotNull(completed)
        assertEquals("success", store.getRequest(id)!!.getString("status"))
        assertEquals(1L, count("SELECT count(*) FROM activations"))
    }

    @Test
    fun `provider success arriving in retry-ready state prevents a duplicate second USSD attempt`() {
        val customer = customer(name = "تأكيد وقت الإعادة", phone = "777444556", allowCredit = true, creditLimit = 100.0)
        val request = store.createRequest(customer, service(price = 8.0), customer.getString("phone1"))
        val id = request.getLong("id")
        assertTrue(store.markDispatching(id, 1))
        assertTrue(store.markReconciliation(id))
        val retryReady = store.prepareSafeRetry("777000001", 50_000)
        assertNotNull(retryReady)
        assertEquals("retry_ready", store.getRequest(id)!!.getString("status"))

        val lateSuccess = store.findUniqueProviderRequest("777000001", "Activated for 777444556")
        assertNotNull(lateSuccess)
        assertNotNull(store.completeSuccess(id, "Activated for 777444556"))
        assertFalse(store.markDispatching(id, 2))
        assertEquals(1L, count("SELECT count(*) FROM activations"))
        assertEquals("success", store.getRequest(id)!!.getString("status"))
    }

    @Test
    fun `provider response requires a unique request unless its SMS identifies the customer`() {
        val first = customer(name = "عميل أول", phone = "777555111", allowCredit = true, creditLimit = 100.0)
        val second = customer(name = "عميل ثان", phone = "777555222", allowCredit = true, creditLimit = 100.0)
        val service = service(price = 5.0)
        store.createRequest(first, service, first.getString("phone1"))
        store.createRequest(second, service, second.getString("phone1"))

        assertNull(store.findUniqueProviderRequest("777000001"))
        assertEquals("777555222", store.findUniqueProviderRequest("+967777000001", "Activated for 777555222")!!.getString("customer_phone"))
        assertNull(store.findUniqueProviderRequest("777000002", "Activated for 777555222"))
    }

    @Test
    fun `provider response with a different phone cannot be assigned to the only pending request`() {
        val customer = customer(name = "عميل مطابق", phone = "777999888", allowCredit = true, creditLimit = 100.0)
        store.createRequest(customer, service(price = 5.0), customer.getString("phone1"))

        assertNull(store.findUniqueProviderRequest("777000001", "Activated for +967 777 000 009"))
        assertEquals("777999888", store.findUniqueProviderRequest("777000001", "Activated for +967 777 999 888")!!.getString("customer_phone"))
    }

    @Test
    fun `automatic retry is allowed once only when supplier balance is unchanged`() {
        val customer = customer(name = "إعادة آمنة", phone = "777666777", allowCredit = true, creditLimit = 100.0)
        val request = store.createRequest(customer, service(price = 9.0), customer.getString("phone1"))
        val id = request.getLong("id")
        assertTrue(store.markDispatching(id, 1))
        assertTrue(store.markReconciliation(id))
        val retry = store.prepareSafeRetry("777000001", 50_000)
        assertNotNull(retry)
        assertEquals("retry_ready", retry!!.getString("status"))
        assertEquals(2, retry.getInt("attempt_count"))

        assertTrue(store.markDispatching(id, 2))
        assertTrue(store.markReconciliation(id))
        assertNull(store.prepareSafeRetry("777000001", 50_000))
        assertEquals("uncertain", store.getRequest(id)!!.getString("status"))
    }

    @Test
    fun `changed supplier balance blocks retry and marks request uncertain`() {
        val customer = customer(name = "رصيد متغير", phone = "777777111", allowCredit = true, creditLimit = 100.0)
        val request = store.createRequest(customer, service(price = 9.0), customer.getString("phone1"))
        val id = request.getLong("id")
        assertTrue(store.markDispatching(id, 1))
        assertTrue(store.markReconciliation(id))

        assertNull(store.prepareSafeRetry("777000001", 50_001))
        assertEquals("uncertain", store.getRequest(id)!!.getString("status"))
        assertEquals(1, store.getRequest(id)!!.getInt("attempt_count"))
    }

    @Test
    fun `request creation rejects missing activation template provider and bank prerequisites`() {
        val customer = customer(name = "تهيئة ناقصة", phone = "777888999")
        val missingTemplate = service(price = 5.0, command = "")
        assertTrue(store.createRequest(customer, missingTemplate, customer.getString("phone1")).has("error"))
        assertEquals(0L, count("SELECT count(*) FROM sms_activation_requests"))

        val validService = service(price = 5.0)
        helper.setSetting("activation_provider_phone", "")
        assertTrue(store.createRequest(customer, validService, customer.getString("phone1")).has("error"))
        assertEquals(0L, count("SELECT count(*) FROM sms_activation_requests"))

        helper.setSetting("activation_provider_phone", "+967777000001")
        helper.setSetting("system_bank_name", "")
        assertTrue(store.createRequest(customer, validService, customer.getString("phone1")).has("error"))
        assertEquals(0L, count("SELECT count(*) FROM sms_activation_requests"))
    }

    @Test
    fun `incoming message claiming is idempotent for the same sender body and timestamp`() {
        val store = SmsActivationStore(context)
        assertTrue(store.claimIncoming("+967777123456", "تفعيل باقة", 123_456L))
        assertFalse(store.claimIncoming("777123456", "تفعيل باقة", 123_456L))
        assertTrue(store.claimIncoming("777123456", "تفعيل باقة", 123_457L))
    }

    @Test
    fun `processor does not create an order or request payment without call permission`() {
        shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.CALL_PHONE)
        val customer = customer(name = "بلا صلاحية", phone = "777123987")
        service(price = 5.0)

        SmsCommandProcessor.process(context, "777123987", "تفعيل خدمة اختبار")

        assertEquals(0L, count("SELECT count(*) FROM sms_activation_requests"))
    }

    private fun setDefaultSettings() {
        helper.setSetting("system_bank_name", "مصرف الأمل")
        helper.setSetting("system_bank_account_name", "مؤسسة الاتصالات")
        helper.setSetting("system_bank_sender_phone", "+967777000001")
        helper.setSetting("activation_provider_phone", "+967777000001")
        helper.setSetting("activation_provider_balance", "500.00")
    }

    private fun customer(
        name: String,
        phone: String,
        allowCredit: Boolean = false,
        creditLimit: Double = 0.0,
        currentBalance: Double = 0.0
    ): JSONObject {
        val id = helper.addCustomer(JSONObject()
            .put("customer_code", "CUS-$phone")
            .put("full_name", name)
            .put("phone1", phone)
            .put("allow_credit", allowCredit)
            .put("credit_limit", creditLimit)
            .put("current_balance", currentBalance)
            .put("status", "active"))
        require(id > 0)
        return helper.getCustomerById(id.toInt())!!
    }

    private fun service(price: Double, command: String = "*123*(phone)#"): JSONObject {
        val code = "TEST-${System.nanoTime()}"
        val id = helper.addService(JSONObject()
            .put("service_code", code)
            .put("service_name", "Test $code")
            .put("service_name_ar", "خدمة اختبار")
            .put("service_type", "topup")
            .put("price", price)
            .put("activation_command", command)
            .put("provider", "Test Provider")
            .put("is_active", true)
            .put("status", "active"))
        require(id > 0)
        return helper.getServiceById(id.toInt())!!
    }

    private fun count(sql: String): Long = helper.writableDatabase.rawQuery(sql, null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }
}
