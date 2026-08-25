// ═══════════════════════════════════════════════════════════════
//  محطة أبو أحمد - BackupWorker (مُصحّح ومُحسّن بالكامل)
// ═══════════════════════════════════════════════════════════════
//
//  التحسينات:
//  1. إصلاح خطأ منطقي حرج في cleanupOldBackups()
//  2. إضافة التحقق من المساحة المتوفرة
//  3. إضافة التحقق من حجم البيانات
//  4. إضافة التحقق من صحة JSON
//  5. إضافة معالجة أخطاء محسّنة
//  6. إضافة إغلاق DatabaseHelper
//  7. إضافة تسجيل مفصّل للعمليات
//  8. إضافة دعم التشفير (هيكل جاهز)
// ═══════════════════════════════════════════════════════════════

package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BackupWorker"
        private const val BACKUP_PREFIX = "auto_backup_"
        private const val MAX_BACKUPS = 10
        // الحد الأدنى للمساحة المطلوبة (50 MB)
        private const val MIN_FREE_SPACE_MB = 50L
        // الحد الأقصى لحجم البيانات (10 MB)
        private const val MAX_DATA_SIZE_MB = 10L
        // تنسيق التاريخ للأسماء
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting automatic backup...")

        return try {
            // ✅ 1. التحقق من المساحة المتوفرة
            if (!hasEnoughSpace()) {
                Log.e(TAG, "Insufficient storage space for backup")
                return Result.failure(
                    workDataOf("error" to "Insufficient storage space")
                )
            }

            // ✅ 2. إنشاء DatabaseHelper مع try-with-resources
            val db = DatabaseHelper(applicationContext)
            try {
                // ✅ 3. تصدير البيانات
                val exportedData = db.exportAllData()

                // ✅ 4. التحقق من صحة البيانات
                if (!isValidExport(exportedData)) {
                    Log.e(TAG, "Invalid export data")
                    return Result.failure(
                        workDataOf("error" to "Invalid export data")
                    )
                }

                // ✅ 5. التحقق من حجم البيانات
                val jsonString = exportedData.toString(2)
                if (jsonString.length > MAX_DATA_SIZE_MB * 1024 * 1024) {
                    Log.e(TAG, "Backup data too large: ${jsonString.length} bytes")
                    return Result.failure(
                        workDataOf("error" to "Backup data exceeds maximum size")
                    )
                }

                // ✅ 6. تشفير البيانات
                val encrypted = encryptBackup(jsonString)

                // ✅ 7. إنشاء المجلد
                val dir = File(applicationContext.filesDir, "backups")
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IOException("Failed to create backup directory")
                }

                // ✅ 8. تنظيف النسخ القديمة (الإصلاح الحرج!)
                cleanupOldBackups(dir)

                // ✅ 9. حفظ الملف مع اسم منظم
                val timestamp = DATE_FORMAT.format(Date())
                val file = File(dir, "${BACKUP_PREFIX}${timestamp}.enc")
                file.writeText(encrypted)

                // ✅ 10. التحقق من نجاح الكتابة
                if (!file.exists() || file.length() == 0L) {
                    throw IOException("Failed to write backup file")
                }

                Log.d(TAG, "✅ Auto backup completed: ${file.absolutePath}")
                Log.d(TAG, "   Size: ${file.length()} bytes")
                Log.d(TAG, "   Total backups: ${getBackupCount(dir)}")

                Result.success(
                    workDataOf(
                        "backup_path" to file.absolutePath,
                        "backup_size" to file.length().toString(),
                        "backup_timestamp" to timestamp
                    )
                )

            } finally {
                // ✅ إغلاق قاعدة البيانات
                db.close()
            }

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError during backup", e)
            Result.failure(
                workDataOf("error" to "Memory limit exceeded")
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during backup", e)
            Result.failure(
                workDataOf("error" to "Permission denied")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed: ${e.message}", e)
            Result.failure(
                workDataOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  دوال مساعدة
    // ═══════════════════════════════════════════════════════════════

    /**
     * ✅ التحقق من وجود مساحة كافية في التخزين الداخلي.
     */
    private fun hasEnoughSpace(): Boolean {
        return try {
            val stat = StatFs(applicationContext.filesDir.path)
            val availableBytes = stat.availableBytes
            val requiredBytes = MIN_FREE_SPACE_MB * 1024 * 1024
            availableBytes >= requiredBytes
        } catch (e: Exception) {
            Log.w(TAG, "Could not check available space", e)
            true // نستمر بحذر
        }
    }

    /**
     * ✅ التحقق من صحة البيانات المُصدّرة.
     */
    private fun isValidExport(data: JSONObject): Boolean {
        return try {
            // التحقق من وجود الجداول الأساسية
            val requiredKeys = arrayOf(
                "customers",
                "refills",
                "transactions",
                "payments"
            )
            requiredKeys.all { key -> data.has(key) && data.get(key) is JSONArray }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * ✅ تشفير البيانات باستخدام Android Keystore (هيكل جاهز).
     * 
     * TODO: استبدل هذا بـ EncryptedFile من AndroidX Security
     * عند توفر مكتبة security-crypto.
     */
    private fun encryptBackup(data: String): String {
        return try {
            // TODO: تنفيذ التشفير الحقيقي باستخدام:
            // val masterKey = MasterKey.Builder(applicationContext)
            //     .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            //     .build()
            // val encryptedFile = EncryptedFile.Builder(
            //     applicationContext,
            //     file,
            //     masterKey,
            //     EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            // ).build()
            
            // حالياً: Base64 encoding كخطوة أولية
            android.util.Base64.encodeToString(
                data.toByteArray(Charsets.UTF_8),
                android.util.Base64.DEFAULT
            )
        } catch (e: Exception) {
            Log.w(TAG, "Encryption failed, saving plain text", e)
            data // fallback
        }
    }

    /**
     * ✅ إصلاح حرج: يحذف أقدم النسخ الاحتياطية.
     * 
     * المشكلة الأصلية: dropLast(MAX_BACKUPS) كان يحذف الأحدث!
     * الحل: نستخدم sortedByDescending (الأحدث أولاً) ثم drop(MAX_BACKUPS)
     */
    private fun cleanupOldBackups(dir: File) {
        try {
            val backups = dir.listFiles { f ->
                f.isFile && f.name.startsWith(BACKUP_PREFIX)
            } ?: return

            if (backups.size <= MAX_BACKUPS) {
                Log.d(TAG, "Backup count (${backups.size}) within limit, skipping cleanup")
                return
            }

            // ✅ الإصلاح: ترتيب تنازلي (الأحدث أولاً) + drop للاحتفاظ بالحد
            val toDelete = backups
                .sortedByDescending { it.lastModified() }  // الأحدث أولاً
                .drop(MAX_BACKUPS)                          // احتفظ بـ MAX_BACKUPS أحدث

            var deletedCount = 0
            var failedCount = 0

            toDelete.forEach { file ->
                try {
                    if (file.delete()) {
                        deletedCount++
                        Log.d(TAG, "Deleted old backup: ${file.name}")
                    } else {
                        failedCount++
                        Log.w(TAG, "Failed to delete old backup: ${file.name}")
                    }
                } catch (e: SecurityException) {
                    failedCount++
                    Log.e(TAG, "SecurityException deleting ${file.name}", e)
                }
            }

            Log.d(TAG, "Cleanup complete: $deletedCount deleted, $failedCount failed")

        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
            // لا نوقف العملية بسبب خطأ في التنظيف
        }
    }

    /**
     * ✅ يُرجع عدد النسخ الاحتياطية الحالية.
     */
    private fun getBackupCount(dir: File): Int {
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith(BACKUP_PREFIX)
        }?.size ?: 0
    }

    /**
     * ✅ دالة مساعدة لإنشاء WorkData.
     */
    private fun workDataOf(vararg pairs: Pair<String, String>): androidx.work.Data {
        return androidx.work.Data.Builder()
            .apply {
                pairs.forEach { (key, value) -> putString(key, value) }
            }
            .build()
    }
}
