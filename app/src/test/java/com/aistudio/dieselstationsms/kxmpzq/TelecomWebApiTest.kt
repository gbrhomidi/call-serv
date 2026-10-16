package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelecomWebApiTest {
    private lateinit var context: Context
    private lateinit var api: TelecomWebApi
    private lateinit var db: DatabaseHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase("telecom_activator.db")
        db = DatabaseHelper.getInstance(context)
        api = TelecomWebApi(context)
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase("telecom_activator.db")
    }

    @Test
    fun `service customer supplier CRUD and dashboard use real database`() {
        val serviceCode = "TEST-${System.currentTimeMillis()}".takeLast(18)
        val service = call("save_service", JSONObject()
            .put("name", "اختبار خدمة")
            .put("code", serviceCode)
            .put("price", 125.5)
            .put("status", "active"))
        assertTrue(service.optBoolean("success"))
        val serviceId = service.getJSONObject("data").getInt("id")
        assertTrue(db.setActivationCommand(serviceCode, "*123*(phone)#"))

        val updatedService = call("save_service", JSONObject()
            .put("id", serviceId)
            .put("name", "خدمة معدلة")
            .put("code", serviceCode)
            .put("price", 140.0)
            .put("status", "active"))
        assertTrue(updatedService.optBoolean("success"))
        val storedService = db.getServiceById(serviceId)!!
        assertEquals("*123*(phone)#", storedService.getString("activation_command"))
        assertEquals("خدمة معدلة", storedService.getString("service_name"))

        val customerPhone = "77${(System.currentTimeMillis() % 10000000).toString().padStart(7, '0')}"
        val customer = call("save_customer", JSONObject()
            .put("name", "عميل اختبار")
            .put("phone1", customerPhone)
            .put("deposit", 250)
            .put("allowCredit", true)
            .put("creditLimit", 500)
            .put("balance", 10)
            .put("notes", "ملاحظة"))
        assertTrue(customer.optBoolean("success"))
        val customerId = customer.getJSONObject("data").getInt("id")
        val customerRow = call("get_customers").getJSONArray("data")
            .let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
            .first { it.optInt("id") == customerId }
        assertEquals("عميل اختبار", customerRow.getString("name"))
        assertEquals(250.0, customerRow.getDouble("deposit"), 0.001)
        assertTrue(customerRow.getBoolean("allowCredit"))

        val duplicatePhone = call("save_customer", JSONObject()
            .put("name", "رقم مكرر")
            .put("phone1", customerPhone))
        assertFalse(duplicatePhone.optBoolean("success"))
        assertTrue(duplicatePhone.optString("error").isNotBlank())

        val supplierPhone = "70${(System.currentTimeMillis() % 10000000).toString().padStart(7, '0')}"
        val supplier = call("save_supplier", JSONObject()
            .put("name", "مورد اختبار")
            .put("phone1", supplierPhone)
            .put("ourBalance", 300)
            .put("theirBalance", 80))
        assertTrue(supplier.optBoolean("success"))
        val supplierId = supplier.getJSONObject("data").getInt("id")
        val suppliers = call("get_suppliers").getJSONArray("data")
        val supplierRow = (0 until suppliers.length()).map { suppliers.getJSONObject(it) }
            .first { it.optInt("id") == supplierId }
        assertEquals(300.0, supplierRow.getDouble("ourBalance"), 0.001)

        assertTrue(call("delete_supplier", JSONObject().put("id", supplierId)).optBoolean("success"))
        assertTrue(call("delete_customer", JSONObject().put("id", customerId)).optBoolean("success"))
        assertTrue(call("delete_service", JSONObject().put("id", serviceId)).optBoolean("success"))
        assertTrue(call("get_dashboard").optJSONArray("data")!!.length() == 1)
        assertTrue(call("get_daily_sms").optJSONArray("data")!!.length() == 7)
    }

    @Test
    fun `sms log is displayed and can be deleted from actual metrics`() {
        val metricId = db.logSms("771234567", "حالة اختبار", "received", "processing")
        assertTrue(metricId > 0)
        val list = call("get_sms").getJSONArray("data")
        val row = (0 until list.length()).map { list.getJSONObject(it) }
            .first { it.optInt("id") == metricId.toInt() }
        assertEquals("771234567", row.getString("sender"))
        assertEquals("حالة اختبار", row.getString("text"))
        assertTrue(call("delete_sms", JSONObject().put("id", metricId)).optBoolean("success"))
        assertFalse((0 until call("get_sms").getJSONArray("data").length())
            .map { call("get_sms").getJSONArray("data").getJSONObject(it).optInt("id") }
            .contains(metricId.toInt()))
    }

    @Test
    fun `invalid action and invalid payload return explicit failures`() {
        assertFalse(call("unsupported", JSONObject()).optBoolean("success"))
        assertFalse(call("save_service", JSONObject().put("name", "بدون كود")).optBoolean("success"))
    }

    @Test
    fun `all reports used by the visible UI return successful real database results`() {
        val reportTypes = listOf(
            "full_customers", "full_services", "full_sms", "full_financial",
            "services_summary", "services_by_status", "services_pricing",
            "customers_list", "customers_balance", "customers_credit",
            "sms_summary", "sms_by_status", "sms_analysis"
        )
        reportTypes.forEach { type ->
            val result = call("generate_report", JSONObject().put("report_type", type))
            assertTrue("Report '$type' failed: ${result.optString("error")}", result.optBoolean("success"))
            assertTrue("Report '$type' omitted its data field", result.has("data"))
        }
    }

    @Test
    fun `version one database upgrade removes obsolete bank balance and adds performance indexes`() {
        db.setSetting("system_bank_expected_balance", "123.45")
        val customerCountBefore = db.readableDatabase.rawQuery("SELECT count(*) FROM customers", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
        db.close()
        context.openOrCreateDatabase("telecom_activator.db", Context.MODE_PRIVATE, null).use { legacy ->
            legacy.version = 1
        }
        DatabaseHelper.closeInstance()
        db = DatabaseHelper.getInstance(context)
        val upgraded = db.readableDatabase

        assertEquals("", db.getSetting("system_bank_expected_balance"))
        val customerCountAfter = upgraded.rawQuery("SELECT count(*) FROM customers", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
        assertEquals(customerCountBefore, customerCountAfter)
        val indexNames = mutableSetOf<String>()
        upgraded.rawQuery("PRAGMA index_list('sms_metrics')", null).use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) indexNames += cursor.getString(nameColumn)
        }
        assertTrue("Timestamp index missing after upgrade: $indexNames", "idx_metrics_timestamp_id" in indexNames)
    }

    private fun call(action: String, data: JSONObject = JSONObject()): JSONObject =
        JSONObject(api.execute(action, data.toString()))
}
