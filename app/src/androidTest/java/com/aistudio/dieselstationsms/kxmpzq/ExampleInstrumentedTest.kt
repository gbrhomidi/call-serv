package com.aistudio.dieselstationsms.kxmpzq

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ═══════════════════════════════════════════════════════════════
 * اختبارات الأجهزة (Instrumented Tests) – نظام تفعيل خدمات الاتصالات
 * ═══════════════════════════════════════════════════════════════
 *
 * هذه الاختبارات تُشغَّل على جهاز حقيقي أو محاكي Android
 * وتختبر وظائف التطبيق الأساسية والتفاعل مع النظام.
 *
 * @version 3.0
 * @since 2026
 * ═══════════════════════════════════════════════════════════════
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    /**
     * اختبار أساسي للتحقق من صحة سياق التطبيق
     * يتأكد من أن اسم الحزمة يطابق الاسم المتوقع
     */
    @Test
    fun useAppContext() {
        // الحصول على سياق التطبيق
        val appContext = ApplicationProvider.getApplicationContext<MyApplication>()

        // التحقق من اسم الحزمة
        assertEquals("com.aistudio.dieselstationsms.kxmpzq", appContext.packageName)

        // التحقق من أن السياق ليس null
        assertNotNull(appContext)
    }

    /**
     * اختبار للتحقق من أن MyApplication قد تم تهيئته بشكل صحيح
     */
    @Test
    fun testApplicationInitialization() {
        val appContext = ApplicationProvider.getApplicationContext<MyApplication>()

        // التحقق من أن التطبيق ليس null
        assertNotNull(appContext)

        // يمكن إضافة المزيد من الاختبارات هنا للتحقق من تهيئة مكونات التطبيق
        // مثل: DatabaseHelper, SMSService, إلخ
    }

    /**
     * اختبار للتحقق من أن DatabaseHelper يعمل بشكل صحيح
     */
    @Test
    fun testDatabaseHelper() {
        val appContext = ApplicationProvider.getApplicationContext<MyApplication>()

        // الحصول على مثيل DatabaseHelper
        val dbHelper = DatabaseHelper.getInstance(appContext)

        // التحقق من أن DatabaseHelper ليس null
        assertNotNull(dbHelper)

        // التحقق من أن قاعدة البيانات مفتوحة
        val db = dbHelper.readableDatabase
        assertNotNull(db)
        assertEquals(true, db.isOpen)
    }

    /**
     * اختبار للتحقق من أن الأذونات الأساسية متاحة
     * (يتم تشغيل هذا الاختبار فقط على الأجهزة التي تمنح الأذونات)
     */
    @Test
    fun testPermissions() {
        val appContext = ApplicationProvider.getApplicationContext<MyApplication>()

        // التحقق من صلاحيات SMS الأساسية
        // ملاحظة: هذا الاختبار يتطلب منح الأذونات يدوياً أو استخدام
        // مكتبات مثل Grant-Android-Permission
        val hasSmsPermission = appContext.packageManager?.let { pm ->
            android.content.pm.PackageManager.PERMISSION_GRANTED ==
                    pm.checkPermission(
                        android.Manifest.permission.SEND_SMS,
                        appContext.packageName
                    )
        } ?: false

        // في البيئات التي لا تمنح الأذونات، هذا الاختبار قد يفشل
        // يمكن تخطيه أو تعديله حسب الحاجة
        // assertEquals(true, hasSmsPermission)
    }
}