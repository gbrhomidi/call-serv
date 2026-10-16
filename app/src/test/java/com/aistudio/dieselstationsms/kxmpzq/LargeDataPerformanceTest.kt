package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
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
import java.io.File
import kotlin.system.measureTimeMillis

/** Deterministic synthetic load test; it does not use or expose customer production data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LargeDataPerformanceTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase("telecom_activator.db")
        helper = DatabaseHelper.getInstance(context)
        helper.readableDatabase // Create the current schema before seeding the stress fixture.
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase("telecom_activator.db")
    }

    @Test
    fun largeDatabaseReportsAndStreamingExportsStayBoundedAndResponsive() {
        val customerRows = 6_000
        val serviceRows = 2_500
        val smsRows = 20_000
        val database = context.openOrCreateDatabase("telecom_activator.db", Context.MODE_PRIVATE, null)
        val seedMs = measureTimeMillis {
            database.beginTransaction()
            try {
                for (index in 1..customerRows) {
                    val values = ContentValues().apply {
                        put("customer_code", "LOAD-C-${index.toString().padStart(6, '0')}")
                        put("full_name", "Stress Customer ${index.toString().padStart(6, '0')}")
                        put("phone1", "77${index.toString().padStart(7, '0')}")
                        put("allow_credit", index % 2)
                        put("credit_limit", (index % 10_000).toDouble())
                        put("current_balance", (index % 2_000).toDouble())
                        put("status", if (index % 10 == 0) "inactive" else "active")
                    }
                    database.insertOrThrow("customers", null, values)
                }
                for (index in 1..serviceRows) {
                    val values = ContentValues().apply {
                        put("service_code", "LOAD-S-${index.toString().padStart(6, '0')}")
                        put("service_name", "Stress Service ${index.toString().padStart(6, '0')}")
                        put("service_type", listOf("topup", "internet", "sms", "call")[index % 4])
                        put("price", (index % 50_000 + 1).toDouble())
                        put("is_active", if (index % 8 == 0) 0 else 1)
                        put("status", if (index % 8 == 0) "inactive" else "active")
                    }
                    database.insertOrThrow("services", null, values)
                }
                val date = "2026-10-16"
                val now = System.currentTimeMillis()
                for (index in 1..smsRows) {
                    val values = ContentValues().apply {
                        put("event_type", when (index % 5) {
                            0 -> "activation_failed"
                            1 -> "bank_verified"
                            2 -> "sms_processed"
                            else -> "sms_received"
                        })
                        put("phone", "77${(index % 10_000).toString().padStart(7, '0')}")
                        put("details", "Synthetic load message row=$index with fixed fixture text")
                        put("timestamp", now - index * 1_000L)
                        put("date", date)
                    }
                    database.insertOrThrow("sms_metrics", null, values)
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
                database.close()
            }
        }

        val reports = TelecomWebApi(context)
        fun report(type: String) = JSONObject(
            reports.execute("generate_report", JSONObject().put("report_type", type).toString())
        )

        lateinit var customerList: JSONObject
        val customerReportMs = measureTimeMillis { customerList = report("full_customers") }
        assertTrue("Customer report failed: ${customerList.optString("error")}", customerList.optBoolean("success"))
        assertTrue(customerList.getJSONArray("data").length() <= 2_000)

        lateinit var serviceList: JSONObject
        val serviceReportMs = measureTimeMillis { serviceList = report("full_services") }
        assertTrue("Service report failed: ${serviceList.optString("error")}", serviceList.optBoolean("success"))
        assertTrue(serviceList.getJSONArray("data").length() <= 2_000)

        lateinit var smsSummary: JSONObject
        val smsSummaryMs = measureTimeMillis { smsSummary = report("sms_summary") }
        assertTrue("SMS summary failed: ${smsSummary.optString("error")}", smsSummary.optBoolean("success"))
        assertEquals(smsRows, smsSummary.getJSONArray("data").getJSONObject(0).getInt("total_messages"))

        lateinit var smsStatus: JSONObject
        val smsStatusMs = measureTimeMillis { smsStatus = report("sms_by_status") }
        assertTrue(smsStatus.optBoolean("success"))
        assertEquals(3, smsStatus.getJSONArray("data").length())

        fun export(collection: String, expectedRows: Int): Triple<Long, Long, File> {
            val file = File(context.cacheDir, "stress_${collection}_export.json")
            var rowCount = 0
            val elapsed = measureTimeMillis {
                file.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.append("{\"collection\":\"").append(collection).append("\",\"data\":[")
                    rowCount = helper.writeExportRows(collection, writer)
                    writer.append("],\"row_count\":").append(rowCount.toString()).append('}')
                }
            }
            assertEquals("Wrong exported row count for $collection", expectedRows, rowCount)
            assertTrue("Empty export for $collection", file.length() > 100)
            assertTrue(file.readText().endsWith("\"row_count\":$expectedRows}"))
            return Triple(elapsed, file.length(), file)
        }
        val (servicesExportMs, servicesExportBytes, servicesExportFile) = export("services", serviceRows + 12)
        val (customersExportMs, customersExportBytes, customersExportFile) = export("customers", customerRows + 4)
        val (smsExportMs, smsExportBytes, smsExportFile) = export("sms", smsRows)
        assertTrue("SMS export is unexpectedly small: $smsExportBytes bytes", smsExportBytes > 1_000_000)

        val indexes = mutableSetOf<String>()
        helper.readableDatabase.rawQuery("PRAGMA index_list('sms_metrics')", null).use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) indexes += cursor.getString(nameColumn)
        }
        assertTrue("Timestamp index missing: $indexes", "idx_metrics_timestamp_id" in indexes)

        // Loose ceiling catches pathological regressions while avoiding hardware-sensitive microbenchmarks.
        listOf(customerReportMs, serviceReportMs, smsSummaryMs, smsStatusMs, servicesExportMs, customersExportMs, smsExportMs).forEach { elapsed ->
            assertTrue("Operation exceeded 30 seconds: ${elapsed}ms", elapsed < 30_000)
        }
        println(
            "LARGE_DATA_PERF rows(customers=$customerRows, services=$serviceRows, sms=$smsRows) " +
                "seed=${seedMs}ms customers=${customerReportMs}ms services=${serviceReportMs}ms " +
                "smsSummary=${smsSummaryMs}ms smsStatus=${smsStatusMs}ms " +
                "exportsMs(services=$servicesExportMs,customers=$customersExportMs,sms=$smsExportMs) " +
                "exportsBytes(services=$servicesExportBytes,customers=$customersExportBytes,sms=$smsExportBytes)"
        )
        listOf(servicesExportFile, customersExportFile, smsExportFile).forEach { it.delete() }
    }
}
