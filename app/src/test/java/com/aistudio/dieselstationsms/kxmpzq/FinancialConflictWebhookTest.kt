package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinancialConflictWebhookTest {
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
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase("telecom_activator.db")
    }

    @Test
    fun `same event key is idempotent and creates one durable delivery`() {
        val first = store.enqueueWebhookDelivery("event-1", null, "bank_reconciliation_conflict", "hash", "https://example.test/hook")
        val replay = store.enqueueWebhookDelivery("event-1", null, "bank_reconciliation_conflict", "hash", "https://example.test/hook")

        assertTrue(first > 0)
        assertEquals(first, replay)
        assertEquals(1, store.webhookStats().getInt("total"))
        assertEquals("queued", store.listWebhookDeliveries().getJSONObject(0).getString("status"))
    }

    @Test
    fun `delivery lifecycle records retry then success and increments attempts`() {
        val id = store.enqueueWebhookDelivery("event-2", null, "provider_financial_conflict", "hash-2", "https://example.test/hook")
        assertEquals(1, store.markWebhookAttempt(id))
        store.markWebhookResult(id, "retry", 503, "HTTP 503")
        assertEquals(2, store.markWebhookAttempt(id))
        store.markWebhookResult(id, "delivered", 204)

        val row = store.listWebhookDeliveries().getJSONObject(0)
        assertEquals("delivered", row.getString("status"))
        assertEquals(2, row.getInt("attempt_count"))
        assertEquals(204, row.getInt("last_http_code"))
        assertEquals(1, store.webhookStats().getInt("delivered"))
        assertEquals(0, store.webhookStats().optInt("retry", 0))
    }

    @Test
    fun `request webhook lifecycle is visible in request timeline`() {
        helper.setSetting("system_bank_name", "مصرف الاختبار")
        helper.setSetting("system_bank_account_name", "حساب النظام")
        helper.setSetting("system_bank_sender_phone", "777000001")
        val customerId = helper.addCustomer(JSONObject()
            .put("customer_code", "WEBHOOK-CUSTOMER")
            .put("full_name", "عميل الاختبار")
            .put("phone1", "777123456")
            .put("status", "active"))
        val serviceId = helper.addService(JSONObject()
            .put("service_code", "WEBHOOK-SERVICE")
            .put("service_name", "خدمة الاختبار")
            .put("service_name_ar", "خدمة الاختبار")
            .put("service_type", "package")
            .put("price", 10.0)
            .put("activation_command", "*123*(phone)#")
            .put("status", "active")
            .put("is_active", true))
        val customer = helper.getCustomerById(customerId.toInt())!!
        val service = helper.getServiceById(serviceId.toInt())!!
        val request = store.createRequest(customer, service, "777123456")
        val requestId = request.getLong("id")
        val deliveryId = store.enqueueWebhookDelivery("event-request-1", requestId, "provider_financial_conflict", "hash-3", "https://example.test/hook")
        store.markWebhookAttempt(deliveryId)
        store.markWebhookResult(deliveryId, "retry", 500, "HTTP 500")

        val events = store.getRequestEvents(requestId)
        val types = (0 until events.length()).map { events.getJSONObject(it).getString("event_type") }
        assertTrue(types.contains("webhook_queued"))
        assertTrue(types.contains("webhook_retry"))
    }

    @Test
    fun `http policy retries transient responses and fails permanent responses`() {
        assertEquals("delivered", FinancialConflictWebhookPolicy.statusForHttp(200))
        assertEquals("delivered", FinancialConflictWebhookPolicy.statusForHttp(299))
        assertEquals("retry", FinancialConflictWebhookPolicy.statusForHttp(408))
        assertEquals("retry", FinancialConflictWebhookPolicy.statusForHttp(429))
        assertEquals("retry", FinancialConflictWebhookPolicy.statusForHttp(503))
        assertEquals("failed", FinancialConflictWebhookPolicy.statusForHttp(400))
        assertEquals("failed", FinancialConflictWebhookPolicy.statusForHttp(404))
    }
}
