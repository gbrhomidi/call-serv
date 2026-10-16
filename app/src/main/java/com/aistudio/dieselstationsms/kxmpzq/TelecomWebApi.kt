package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Whitelisted synchronous API used by the local WebView UI. */
internal class TelecomWebApi(context: Context) {
    private val appContext = context.applicationContext
    private val db get() = DatabaseHelper.getInstance(appContext)

    fun execute(action: String, rawParams: String): String {
        val params = try {
            if (rawParams.isBlank()) JSONObject() else JSONObject(rawParams)
        } catch (e: Exception) {
            return error("بيانات الطلب غير صالحة")
        }
        return try {
            val result: Any? = when (action) {
                "get_dashboard" -> dashboard()
                "get_daily_sms" -> dailySms()
                "get_services" -> mapArray(db.getServices(), ::serviceForUi)
                "save_service" -> saveService(params)
                "delete_service" -> deleteService(params)
                "get_customers" -> mapArray(db.getCustomers(), ::customerForUi)
                "save_customer" -> saveCustomer(params)
                "delete_customer" -> deleteCustomer(params)
                "get_suppliers" -> mapArray(db.getSuppliers(), ::supplierForUi)
                "save_supplier" -> saveSupplier(params)
                "delete_supplier" -> deleteSupplier(params)
                "get_sms" -> mapArray(db.getSmsMetrics(), ::smsForUi)
                "delete_sms" -> deleteSms(params)
                "get_notifications" -> notifications()
                "generate_report" -> report(params.optString("report_type"))
                "export_data" -> db.exportAllData()
                else -> throw IllegalArgumentException("العملية غير مدعومة: $action")
            }
            JSONObject().apply {
                put("success", true)
                if (result != null) put("data", result)
                successMessage(action)?.let { put("message", it) }
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Web API action failed: $action", e)
            error(readableError(e))
        }
    }

    private fun dashboard(): JSONArray {
        val today = dateString(Date())
        val stats = db.getDashboardStats()
        stats.put("sms_today", db.getSmsMetrics(fromDate = today, toDate = today).length())
        stats.put("total_due", stats.optDouble("total_balance", 0.0))
        return JSONArray().put(stats)
    }

    private fun dailySms(): JSONArray {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -6)
        val start = dateString(calendar.time)
        val end = dateString(Date())
        val counts = mutableMapOf<String, Int>()
        val rows = db.getSmsMetrics(fromDate = start, toDate = end)
        for (i in 0 until rows.length()) {
            rows.optJSONObject(i)?.optString("date")?.takeIf { it.isNotBlank() }?.let { date ->
                counts[date] = (counts[date] ?: 0) + 1
            }
        }
        val result = JSONArray()
        calendar.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(start) ?: Date()
        repeat(7) {
            val date = dateString(calendar.time)
            result.put(JSONObject().put("date", date).put("count", counts[date] ?: 0))
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }

    private fun saveService(input: JSONObject): JSONObject {
        val name = input.optString("name").trim()
        val code = input.optString("code").trim()
        val id = input.optInt("id", 0)
        val price = input.optDouble("price", 0.0)
        val status = input.optString("status", "active")
        require(name.isNotBlank() && code.isNotBlank()) { "اسم الخدمة وكودها مطلوبان" }
        require(name.length <= 200 && code.length <= 20) { "اسم الخدمة أو كودها أطول من المسموح" }
        require(price.isFinite() && price >= 0) { "سعر الخدمة غير صالح" }
        require(status == "active" || status == "inactive") { "حالة الخدمة غير صالحة" }
        val values = JSONObject().apply {
            put("service_code", code)
            put("service_name", name)
            put("service_name_ar", name)
            put("service_type", input.optString("service_type", "topup").ifBlank { "topup" })
            put("price", price)
            put("activation_code", code)
            put("description", input.optString("details"))
            put("status", status)
            put("is_active", status == "active")
        }
        val savedId = if (id > 0) {
            if (db.updateService(id, values) == 0) throw IllegalArgumentException("الخدمة غير موجودة")
            id.toLong()
        } else db.addService(values)
        if (savedId <= 0) throw IllegalStateException("تعذر حفظ الخدمة؛ تحقق من عدم تكرار الكود")
        return JSONObject().put("id", savedId).put("name", name).put("code", code)
    }

    private fun saveCustomer(input: JSONObject): JSONObject {
        val name = input.optString("name").trim()
        val phone = input.optString("phone1").trim()
        val id = input.optInt("id", 0)
        require(name.isNotBlank() && phone.isNotBlank()) { "اسم العميل ورقم هاتفه مطلوبان" }
        require(name.length <= 200 && phone.length <= 20) { "بيانات العميل أطول من المسموح" }
        val values = JSONObject().apply {
            put("full_name", name)
            put("full_name_ar", name)
            put("phone1", phone)
            put("phone2", input.optString("phone2"))
            put("phone3", input.optString("phone3"))
            put("balance_deposit", input.optDouble("deposit", 0.0))
            put("allow_credit", input.optBoolean("allowCredit", false))
            put("credit_limit", input.optDouble("creditLimit", 0.0))
            put("current_balance", input.optDouble("balance", 0.0))
            put("notes", input.optString("notes"))
            put("status", input.optString("status", "active"))
        }
        val savedId = if (id > 0) {
            if (db.updateCustomer(id, values) == 0) throw IllegalArgumentException("العميل غير موجود")
            id.toLong()
        } else db.addCustomer(values)
        if (savedId <= 0) throw IllegalStateException("تعذر حفظ العميل؛ تحقق من أن رقم الهاتف غير مستخدم")
        return JSONObject().put("id", savedId).put("name", name).put("phone1", phone)
    }

    private fun saveSupplier(input: JSONObject): JSONObject {
        val name = input.optString("name").trim()
        val phone = input.optString("phone1").trim()
        val id = input.optInt("id", 0)
        require(name.isNotBlank() && phone.isNotBlank()) { "اسم المورد ورقم هاتفه مطلوبان" }
        require(name.length <= 200 && phone.length <= 20) { "بيانات المورد أطول من المسموح" }
        val values = JSONObject().apply {
            put("name", name)
            put("name_ar", name)
            put("phone1", phone)
            put("phone2", input.optString("phone2"))
            put("phone3", input.optString("phone3"))
            put("our_balance", input.optDouble("ourBalance", 0.0))
            put("their_balance", input.optDouble("theirBalance", 0.0))
            put("status", input.optString("status", "active"))
            put("notes", input.optString("notes"))
        }
        val savedId = if (id > 0) {
            if (db.updateSupplier(id, values) == 0) throw IllegalArgumentException("المورد غير موجود")
            id.toLong()
        } else db.addSupplier(values)
        if (savedId <= 0) throw IllegalStateException("تعذر حفظ المورد؛ تحقق من أن رقم الهاتف غير مستخدم")
        return JSONObject().put("id", savedId).put("name", name).put("phone1", phone)
    }

    private fun deleteService(params: JSONObject): JSONObject {
        val id = requireId(params)
        if (db.deleteService(id) == 0) throw IllegalArgumentException("الخدمة غير موجودة")
        return JSONObject().put("id", id)
    }

    private fun deleteCustomer(params: JSONObject): JSONObject {
        val id = requireId(params)
        if (db.deleteCustomer(id) == 0) throw IllegalArgumentException("العميل غير موجود")
        return JSONObject().put("id", id)
    }

    private fun deleteSupplier(params: JSONObject): JSONObject {
        val id = requireId(params)
        if (db.deleteSupplier(id) == 0) throw IllegalArgumentException("المورد غير موجود")
        return JSONObject().put("id", id)
    }

    private fun deleteSms(params: JSONObject): JSONObject {
        val id = requireId(params)
        if (db.deleteSmsMetric(id) == 0) throw IllegalArgumentException("سجل الرسالة غير موجود")
        return JSONObject().put("id", id)
    }

    private fun serviceForUi(row: JSONObject) = JSONObject(row.toString()).apply {
        put("name", row.optString("service_name_ar").ifBlank { row.optString("service_name") })
        put("code", row.optString("service_code"))
        put("details", row.optString("description"))
    }

    private fun customerForUi(row: JSONObject) = JSONObject(row.toString()).apply {
        put("name", row.optString("full_name_ar").ifBlank { row.optString("full_name") })
        put("deposit", row.optDouble("balance_deposit", 0.0))
        put("allowCredit", row.optInt("allow_credit", 0) == 1)
        put("creditLimit", row.optDouble("credit_limit", 0.0))
        put("balance", row.optDouble("current_balance", 0.0))
    }

    private fun supplierForUi(row: JSONObject) = JSONObject(row.toString()).apply {
        put("ourBalance", row.optDouble("our_balance", 0.0))
        put("theirBalance", row.optDouble("their_balance", 0.0))
    }

    private fun smsForUi(row: JSONObject) = JSONObject().apply {
        val event = row.optString("event_type").lowercase(Locale.ROOT)
        put("id", row.optInt("id"))
        put("sender", row.optString("phone"))
        put("text", row.optString("details"))
        put("time", row.optString("created_at").ifBlank { row.optString("date") })
        put("status", when {
            event.contains("fail") -> "failed"
            event.contains("pending") -> "pending"
            event.contains("process") || event.contains("verified") || event.contains("success") || event.contains("sent") -> "processed"
            else -> "received"
        })
        put("event", row.optString("event_type"))
    }

    private fun notifications(): JSONArray {
        val metrics = db.getSmsMetrics()
        val result = JSONArray()
        for (i in 0 until minOf(metrics.length(), 30)) {
            val row = metrics.optJSONObject(i) ?: continue
            val event = row.optString("event_type")
            result.put(JSONObject().apply {
                put("id", row.optInt("id"))
                put("title", if (event.contains("FAIL", ignoreCase = true)) "تنبيه: تعذر تنفيذ عملية" else "نشاط SMS")
                put("message", row.optString("details").ifBlank { event })
                put("time", row.optString("created_at"))
            })
        }
        return result
    }

    private fun report(type: String): Any = when (type) {
        "services_summary", "services_by_status", "services_pricing", "full_services" -> db.getServiceReport()
        "customers_list", "customers_balance", "customers_credit", "full_customers" -> db.getCustomerReport()
        "suppliers_list", "suppliers_balance" -> db.getSupplierReport()
        "suppliers_transfers" -> db.getTransfers()
        "sms_summary", "sms_by_status", "sms_analysis", "full_sms" -> mapArray(db.getSmsMetrics(), ::smsForUi)
        "full_financial" -> db.getDashboardStats()
        else -> throw IllegalArgumentException("نوع التقرير غير معروف")
    }

    private fun <T> mapArray(source: JSONArray, transform: (JSONObject) -> T): JSONArray = JSONArray().also { target ->
        for (i in 0 until source.length()) source.optJSONObject(i)?.let { target.put(transform(it)) }
    }

    private fun requireId(params: JSONObject): Int = params.optInt("id", 0).also {
        require(it > 0) { "معرف السجل غير صالح" }
    }

    private fun successMessage(action: String): String? = when (action) {
        "save_service" -> "تم حفظ الخدمة"
        "save_customer" -> "تم حفظ العميل"
        "save_supplier" -> "تم حفظ المورد"
        "delete_service", "delete_customer", "delete_supplier", "delete_sms" -> "تم الحذف"
        else -> null
    }

    private fun error(message: String): String = JSONObject().apply {
        put("success", false)
        put("error", message)
        put("message", message)
    }.toString()

    private fun readableError(e: Exception): String = when (e) {
        is IllegalArgumentException -> e.message ?: "الطلب غير صالح"
        is SQLiteConstraintException -> "القيمة موجودة مسبقاً؛ تحقق من رقم الهاتف أو كود الخدمة"
        else -> "تعذر تنفيذ العملية. راجع البيانات وحاول مرة أخرى"
    }

    private fun dateString(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    companion object { private const val TAG = "TelecomWebApi" }
}
