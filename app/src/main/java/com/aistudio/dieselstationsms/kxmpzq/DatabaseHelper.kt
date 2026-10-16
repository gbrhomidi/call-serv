// DatabaseHelper.kt
package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock

/**
 * ═══════════════════════════════════════════════════════════════
 * DatabaseHelper - قاعدة بيانات نظام تفعيل خدمات الاتصالات
 * ═══════════════════════════════════════════════════════════════
 * 
 * الإصدار 1.0 - مبني خصيصاً لمشروع Telecom Service Activator
 * 
 * الجداول:
 * 1. customers       - العملاء
 * 2. services        - الخدمات (شحن، إنترنت، رسائل، ...)
 * 3. suppliers       - الموردين (شركات الحوالات)
 * 4. transfers       - سجل الحوالات الواردة
 * 5. activations     - سجل تفعيل الخدمات
 * 6. sms_processed_hashes   - منع تكرار معالجة الرسائل
 * 7. sms_rate_limits        - حدود المعدل للرسائل
 * 8. sms_conversation_context - سياق المحادثة
 * 9. sms_customer_preferences - تفضيلات العميل
 * 10. sms_interaction_history - سجل التفاعلات
 * 11. sms_metrics            - مقاييس الأداء
 * 12. sms_otp_verifications  - تحققات OTP
 * 13. system_settings        - إعدادات النظام
 * 14. audit_log              - سجل التدقيق
 * ═══════════════════════════════════════════════════════════════
 */
class DatabaseHelper(private val context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "telecom_activator.db"
        const val VERSION = 3

        // ==================== Thread-Safe Date Formats ====================
        private val dateFormatThreadLocal = ThreadLocal<SimpleDateFormat>()
        private val dateOnlyFormatThreadLocal = ThreadLocal<SimpleDateFormat>()

        private fun getDateTimeFormat(): SimpleDateFormat {
            var df = dateFormatThreadLocal.get()
            if (df == null) {
                df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                dateFormatThreadLocal.set(df)
            }
            return df
        }

        private fun getDateFormat(): SimpleDateFormat {
            var df = dateOnlyFormatThreadLocal.get()
            if (df == null) {
                df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                dateOnlyFormatThreadLocal.set(df)
            }
            return df
        }

        private fun getCurrentDateTime(): String = getDateTimeFormat().format(Date())
        private fun getCurrentDate(): String = getDateFormat().format(Date())

        // ==================== Singleton ====================
        @Volatile
        private var instance: DatabaseHelper? = null

        @JvmStatic
        fun getInstance(context: Context): DatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also {
                    instance = it
                }
            }
        }

        @JvmStatic
        fun closeInstance() {
            instance?.close()
            instance = null
        }
    }

    private val dbLock = ReentrantLock()

    // ================================================================
    // 1. إنشاء قاعدة البيانات (onCreate)
    // ================================================================

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            createAllTables(db)
            insertInitialData(db)
            db.setTransactionSuccessful()
            Log.d(TAG, "Database V$VERSION created successfully")
        } finally {
            db.endTransaction()
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createIndexes(db)
            db.delete("system_settings", "setting_key=?", arrayOf("system_bank_expected_balance"))
        }
        if (oldVersion < 3) {
            // Remove only rows shipped by the old demo seed. User-created rows remain intact.
            db.update("services", ContentValues().apply { put("is_deleted", 1); put("is_active", 0); put("status", "inactive") }, "service_code IN ('TOPUP-500','TOPUP-1000','TOPUP-2000','INTERNET-1GB','INTERNET-5GB','INTERNET-10GB','SMS-100','SMS-500','CALLS-100','CALLS-300','PACKAGE-GOLD','PACKAGE-SILVER')", null)
            db.update("customers", ContentValues().apply { put("is_deleted", 1); put("status", "inactive") }, "customer_code IN ('CUS-001','CUS-002','CUS-003','CUS-004')", null)
            db.update("suppliers", ContentValues().apply { put("is_deleted", 1); put("status", "inactive") }, "supplier_code IN ('SUP-001','SUP-002','SUP-003')", null)
        }
        Log.d(TAG, "Upgrading database from $oldVersion to $newVersion")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    // ================================================================
    // 2. إنشاء الجداول
    // ================================================================

    private fun createAllTables(db: SQLiteDatabase) {
        createCustomersTable(db)
        createServicesTable(db)
        createSuppliersTable(db)
        createTransfersTable(db)
        createActivationsTable(db)
        createSmsProcessedHashesTable(db)
        createSmsRateLimitsTable(db)
        createSmsConversationContextTable(db)
        createSmsCustomerPreferencesTable(db)
        createSmsInteractionHistoryTable(db)
        createSmsMetricsTable(db)
        createSmsOtpVerificationsTable(db)
        createSystemSettingsTable(db)
        createAuditLogTable(db)
        createIndexes(db)
    }

    // ==================== 2.1 العملاء ====================

    private fun createCustomersTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS customers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_code VARCHAR(20) UNIQUE NOT NULL,
                full_name VARCHAR(200) NOT NULL,
                full_name_ar VARCHAR(200),
                phone1 VARCHAR(20) UNIQUE NOT NULL,
                phone2 VARCHAR(20),
                phone3 VARCHAR(20),
                balance_deposit DECIMAL(15,2) DEFAULT 0,      -- الرصيد المودع
                allow_credit INTEGER DEFAULT 0,                -- 0=لا, 1=نعم
                credit_limit DECIMAL(15,2) DEFAULT 0,          -- المبلغ المسموح به للأجل
                current_balance DECIMAL(15,2) DEFAULT 0,       -- الرصيد الحالي (المدين)
                total_purchases DECIMAL(15,2) DEFAULT 0,
                total_payments DECIMAL(15,2) DEFAULT 0,
                loyalty_points INTEGER DEFAULT 0,
                notes TEXT,
                details TEXT,
                status VARCHAR(20) DEFAULT 'active',          -- active, inactive, blocked
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced'
            )
        """)
        Log.d(TAG, "Customers table created")
    }

    // ==================== 2.2 الخدمات ====================

    private fun createServicesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS services (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                service_code VARCHAR(20) UNIQUE NOT NULL,      -- مثلاً: TOPUP-1000, INTERNET-10GB
                service_name VARCHAR(200) NOT NULL,
                service_name_ar VARCHAR(200),
                service_type VARCHAR(20) NOT NULL,             -- topup, internet, sms, call, package
                price DECIMAL(12,2) NOT NULL,                  -- سعر الخدمة
                activation_code VARCHAR(100),                  -- كود التفعيل (مثل USSD أو رقم الخدمة)
                activation_command VARCHAR(200),               -- الأمر الكامل للتفعيل
                provider VARCHAR(50),                          -- شركة الاتصالات (mtn, yemen_mobile, etc)
                description TEXT,
                notes TEXT,
                is_active INTEGER DEFAULT 1,
                status VARCHAR(20) DEFAULT 'active',          -- active, inactive
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0
            )
        """)
        Log.d(TAG, "Services table created")
    }

    // ==================== 2.3 الموردين والحوالات ====================

    private fun createSuppliersTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS suppliers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                supplier_code VARCHAR(20) UNIQUE NOT NULL,
                name VARCHAR(200) NOT NULL,
                name_ar VARCHAR(200),
                phone1 VARCHAR(20) UNIQUE NOT NULL,
                phone2 VARCHAR(20),
                phone3 VARCHAR(20),
                our_balance DECIMAL(15,2) DEFAULT 0,           -- رصيدنا لديهم
                their_balance DECIMAL(15,2) DEFAULT 0,          -- رصيدهم لدينا
                status VARCHAR(20) DEFAULT 'active',           -- active, inactive
                details TEXT,
                notes TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0
            )
        """)
        Log.d(TAG, "Suppliers table created")
    }

    // ==================== 2.4 الحوالات ====================

    private fun createTransfersTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS transfers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transfer_code VARCHAR(30) UNIQUE NOT NULL,
                supplier_id INTEGER NOT NULL,
                customer_phone VARCHAR(20) NOT NULL,
                customer_name VARCHAR(200),
                amount DECIMAL(15,2) NOT NULL,
                fee DECIMAL(15,2) DEFAULT 0,
                net_amount DECIMAL(15,2) NOT NULL,
                sender_name VARCHAR(200),
                sender_phone VARCHAR(20),
                reference_number VARCHAR(50),
                transfer_date TEXT NOT NULL,
                status VARCHAR(20) DEFAULT 'pending',         -- pending, processed, failed
                processed_at TEXT,
                processed_by INTEGER,
                notes TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
            )
        """)
        Log.d(TAG, "Transfers table created")
    }

    // ==================== 2.5 سجل التفعيل ====================

    private fun createActivationsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS activations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                activation_code VARCHAR(30) UNIQUE NOT NULL,
                customer_id INTEGER NOT NULL,
                service_id INTEGER NOT NULL,
                phone_number VARCHAR(20) NOT NULL,
                amount DECIMAL(15,2) NOT NULL,
                payment_method VARCHAR(20) NOT NULL,           -- balance, credit, transfer
                transfer_id INTEGER,
                activation_command TEXT,
                status VARCHAR(20) DEFAULT 'pending',         -- pending, success, failed
                status_message TEXT,
                activated_at TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (customer_id) REFERENCES customers(id),
                FOREIGN KEY (service_id) REFERENCES services(id),
                FOREIGN KEY (transfer_id) REFERENCES transfers(id)
            )
        """)
        Log.d(TAG, "Activations table created")
    }

    // ==================== 2.6 جداول SMS (موروثة ومعدلة) ====================

    private fun createSmsProcessedHashesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_processed_hashes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_hash TEXT NOT NULL UNIQUE,
                phone TEXT NOT NULL,
                message_preview TEXT,
                processed_at INTEGER NOT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_hashes_hash ON sms_processed_hashes(message_hash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_hashes_phone ON sms_processed_hashes(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_hashes_time ON sms_processed_hashes(processed_at)")
    }

    private fun createSmsRateLimitsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_rate_limits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL UNIQUE,
                last_reply_at INTEGER DEFAULT 0,
                day_start INTEGER DEFAULT 0,
                daily_count INTEGER DEFAULT 0,
                warning_count INTEGER DEFAULT 0,
                blocked_until INTEGER DEFAULT 0,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rate_limits_phone ON sms_rate_limits(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rate_limits_blocked ON sms_rate_limits(blocked_until)")
    }

    private fun createSmsConversationContextTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_conversation_context (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL UNIQUE,
                last_topic TEXT DEFAULT '',
                last_intent TEXT DEFAULT '',
                timestamp INTEGER DEFAULT 0,
                pending_action TEXT DEFAULT '',
                awaiting_response INTEGER DEFAULT 0,
                data_json TEXT DEFAULT '{}',
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_context_phone ON sms_conversation_context(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_context_time ON sms_conversation_context(timestamp)")
    }

    private fun createSmsCustomerPreferencesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_customer_preferences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL UNIQUE,
                preferred_service VARCHAR(50) DEFAULT '',
                preferred_amount DECIMAL(15,2) DEFAULT 0,
                preferred_operator VARCHAR(50) DEFAULT '',
                last_order_date INTEGER DEFAULT 0,
                order_count INTEGER DEFAULT 0,
                language TEXT DEFAULT 'ar',
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prefs_phone ON sms_customer_preferences(phone)")
    }

    private fun createSmsInteractionHistoryTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_interaction_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                intent TEXT NOT NULL,
                message TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_interaction_phone ON sms_interaction_history(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_interaction_time ON sms_interaction_history(timestamp)")
    }

    private fun createSmsMetricsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_metrics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                phone TEXT DEFAULT '',
                details TEXT,
                timestamp INTEGER NOT NULL,
                date TEXT NOT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_type ON sms_metrics(event_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_date ON sms_metrics(date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_phone ON sms_metrics(phone)")
    }

    private fun createSmsOtpVerificationsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_otp_verifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL UNIQUE,
                otp_code TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                attempts INTEGER DEFAULT 0,
                max_attempts INTEGER DEFAULT 3,
                expires_at INTEGER NOT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_otp_phone ON sms_otp_verifications(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_otp_expires ON sms_otp_verifications(expires_at)")
    }

    // ==================== 2.7 إعدادات النظام ====================

    private fun createSystemSettingsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS system_settings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                setting_key TEXT UNIQUE NOT NULL,
                setting_value TEXT,
                data_type TEXT DEFAULT 'string',
                category TEXT DEFAULT 'general',
                description TEXT,
                is_editable INTEGER DEFAULT 1,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
    }

    // ==================== 2.8 سجل التدقيق ====================

    private fun createAuditLogTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER,
                action VARCHAR(100) NOT NULL,
                action_category VARCHAR(50),
                description TEXT,
                target_table VARCHAR(50),
                target_id INTEGER,
                ip_address VARCHAR(45),
                device_id VARCHAR(100),
                old_values TEXT,
                new_values TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at)")
    }

    // ==================== 2.9 الفهارس ====================

    private fun createIndexes(db: SQLiteDatabase) {
        // العملاء
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customers_phone1 ON customers(phone1)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customers_phone2 ON customers(phone2)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customers_status ON customers(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customers_deleted_name ON customers(is_deleted, full_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customers_credit ON customers(is_deleted, allow_credit, current_balance)")
        
        // الخدمات
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_code ON services(service_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_type ON services(service_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_status ON services(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_deleted_status_type ON services(is_deleted, status, service_type)")
        
        // الموردين
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_suppliers_phone1 ON suppliers(phone1)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_suppliers_status ON suppliers(status)")
        
        // الحوالات
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transfers_supplier ON transfers(supplier_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transfers_customer ON transfers(customer_phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transfers_status ON transfers(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transfers_date ON transfers(transfer_date)")
        
        // التفعيل
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_activations_customer ON activations(customer_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_activations_service ON activations(service_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_activations_status ON activations(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_activations_phone ON activations(phone_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_activations_service_status ON activations(service_id, status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_activations_customer_status ON activations(customer_id, status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_timestamp_id ON sms_metrics(timestamp DESC, id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_event_timestamp ON sms_metrics(event_type, timestamp DESC)")
    }

    // ================================================================
    // 3. البيانات الأولية
    // ================================================================

    private fun insertInitialData(db: SQLiteDatabase) {
        // لا تُزرع خدمات أو عملاء أو موردون افتراضيون. تُنشأ هذه السجلات من واجهة الإدارة فقط؛
        // إبقاء القاعدة فارغة يمنع إرسال خدمات وهمية أو ربط البنك بمورد تجريبي.
        // ==================== إعدادات النظام ====================
        val settings = listOf(
            arrayOf("sms_enabled", "1", "boolean", "sms", "تفعيل نظام SMS"),
            arrayOf("sms_security_mode", "strict", "string", "sms", "وضع أمان SMS (strict/relaxed)"),
            arrayOf("sms_rate_limit", "10", "integer", "sms", "الحد الأقصى للرسائل في الدقيقة"),
            arrayOf("sms_daily_limit", "100", "integer", "sms", "الحد الأقصى للرسائل اليومية"),
            arrayOf("sms_duplicate_retention_days", "7", "integer", "sms", "عدد أيام الاحتفاظ بمعرفات الرسائل"),
            arrayOf("default_credit_limit", "5000", "integer", "finance", "الحد الائتماني الافتراضي"),
            arrayOf("max_credit_limit", "50000", "integer", "finance", "الحد الأقصى للائتمان"),
            arrayOf("vip_min_balance", "5000", "integer", "finance", "الحد الأدنى للرصيد لتصنيف VIP"),
            arrayOf("vip_discount_percent", "10", "integer", "finance", "نسبة الخصم للعملاء VIP"),
            arrayOf("push_notifications_enabled", "0", "boolean", "notifications", "تفعيل إشعارات Push"),
            arrayOf("retention_days", "90", "integer", "system", "عدد أيام الاحتفاظ بالسجلات")
        )

        for (s in settings) {
            db.execSQL("""
                INSERT OR IGNORE INTO system_settings 
                (setting_key, setting_value, data_type, category, description)
                VALUES (?, ?, ?, ?, ?)
            """, s)
        }

        Log.d(TAG, "Initial data inserted successfully")
    }

    // ================================================================
    // 4. دوال العملاء (Customers)
    // ================================================================

    /**
     * الحصول على عميل برقم الهاتف
     * @param phone رقم الهاتف (سيتم تطبيعه تلقائياً)
     * @return JSONObject يحتوي بيانات العميل أو null
     */
    fun getCustomerByPhone(phone: String): JSONObject? {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM customers WHERE (phone1 = ? OR phone2 = ? OR phone3 = ?) AND status = 'active' AND is_deleted = 0 LIMIT 1",
                arrayOf(normalizedPhone, normalizedPhone, normalizedPhone)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * الحصول على عميل بالمعرف
     */
    fun getCustomerById(id: Int): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM customers WHERE id = ? AND is_deleted = 0",
                arrayOf(id.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * جلب جميع العملاء
     */
    fun getCustomers(status: String? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = if (status != null) {
                "SELECT * FROM customers WHERE status = ? AND is_deleted = 0 ORDER BY full_name"
            } else {
                "SELECT * FROM customers WHERE is_deleted = 0 ORDER BY full_name"
            }
            val args = if (status != null) arrayOf(status) else null
            db.rawQuery(sql, args).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * إضافة عميل جديد
     * @return معرف العميل أو -1 في حالة الفشل
     */
    fun addCustomer(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val code = data.optString("customer_code", "CUS-${System.currentTimeMillis()}")
            val values = ContentValues().apply {
                put("customer_code", code)
                put("full_name", data.optString("full_name", ""))
                put("full_name_ar", data.optString("full_name_ar", ""))
                put("phone1", normalizePhone(data.optString("phone1", "")))
                put("phone2", normalizePhone(data.optString("phone2", "")))
                put("phone3", normalizePhone(data.optString("phone3", "")))
                put("balance_deposit", data.optDouble("balance_deposit", 0.0))
                put("allow_credit", if (data.optBoolean("allow_credit", false)) 1 else 0)
                put("credit_limit", data.optDouble("credit_limit", 0.0))
                put("current_balance", data.optDouble("current_balance", 0.0))
                put("notes", data.optString("notes", ""))
                put("details", data.optString("details", ""))
                put("status", data.optString("status", "active"))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            val id = db.insert("customers", null, values)
            if (id > 0) logAudit("add_customer", "customers", id, "إضافة عميل: ${data.optString("full_name")}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تحديث بيانات عميل
     */
    fun updateCustomer(id: Int, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                if (data.has("full_name") && !data.isNull("full_name")) put("full_name", data.optString("full_name"))
                if (data.has("full_name_ar") && !data.isNull("full_name_ar")) put("full_name_ar", data.optString("full_name_ar"))
                if (data.has("phone1") && !data.isNull("phone1")) put("phone1", normalizePhone(data.optString("phone1")))
                if (data.has("phone2") && !data.isNull("phone2")) put("phone2", normalizePhone(data.optString("phone2")))
                if (data.has("phone3") && !data.isNull("phone3")) put("phone3", normalizePhone(data.optString("phone3")))
                if (data.has("balance_deposit") && !data.isNull("balance_deposit")) put("balance_deposit", data.optDouble("balance_deposit"))
                if (data.has("allow_credit")) put("allow_credit", if (data.optBoolean("allow_credit")) 1 else 0)
                if (data.has("credit_limit") && !data.isNull("credit_limit")) put("credit_limit", data.optDouble("credit_limit"))
                if (data.has("current_balance") && !data.isNull("current_balance")) put("current_balance", data.optDouble("current_balance"))
                if (data.has("notes") && !data.isNull("notes")) put("notes", data.optString("notes"))
                if (data.has("details") && !data.isNull("details")) put("details", data.optString("details"))
                if (data.has("status") && !data.isNull("status")) put("status", data.optString("status"))
                put("updated_at", getCurrentDateTime())
            }
            if (values.size() <= 1) return 0
            val rows = db.update("customers", values, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logAudit("update_customer", "customers", id.toLong(), "تحديث عميل: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * حذف عميل (حذف منطقي)
     */
    fun deleteCustomer(id: Int): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("is_deleted", 1)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("customers", values, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logAudit("delete_customer", "customers", id.toLong(), "حذف عميل: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تحديث رصيد العميل
     * @param customerId معرف العميل
     * @param amount المبلغ (موجب للإضافة، سالب للخصم)
     * @param reason سبب التحديث
     * @return نجاح العملية
     */
    fun updateCustomerBalance(customerId: Int, amount: Double, reason: String = "تحديث رصيد"): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                // قراءة الرصيد الحالي
                var currentBalance = 0.0
                var currentDeposit = 0.0
                db.rawQuery(
                    "SELECT current_balance, balance_deposit FROM customers WHERE id = ?",
                    arrayOf(customerId.toString())
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        currentBalance = cursor.getDouble(0)
                        currentDeposit = cursor.getDouble(1)
                    } else {
                        return false
                    }
                }

                val newBalance = currentBalance + amount
                val newDeposit = currentDeposit + amount

                // تحديث الرصيد
                val values = ContentValues().apply {
                    put("current_balance", newBalance)
                    put("balance_deposit", newDeposit)
                    put("updated_at", getCurrentDateTime())
                }
                val rows = db.update("customers", values, "id = ?", arrayOf(customerId.toString()))
                db.setTransactionSuccessful()
                logAudit("balance_update", "customers", customerId.toLong(), "$reason: $amount -> $newBalance")
                rows > 0
            } finally {
                db.endTransaction()
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * التحقق من صلاحية العميل للشراء بالأجل
     */
    fun canCustomerUseCredit(customerId: Int, amount: Double): Pair<Boolean, String> {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT allow_credit, credit_limit, current_balance FROM customers WHERE id = ? AND status = 'active' AND is_deleted = 0",
                arrayOf(customerId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return Pair(false, "العميل غير موجود")
                }
                val allowCredit = cursor.getInt(0) == 1
                val creditLimit = cursor.getDouble(1)
                val currentBalance = cursor.getDouble(2)

                if (!allowCredit) {
                    return Pair(false, "العميل لا يسمح له بالشراء بالأجل")
                }
                if (currentBalance + amount > creditLimit) {
                    return Pair(false, "تجاوز الحد الائتماني المسموح به (${creditLimit.toInt()} ريال)")
                }
                Pair(true, "مسموح")
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * البحث عن العملاء
     */
    fun searchCustomers(query: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val likeQuery = "%$query%"
            db.rawQuery(
                """
                SELECT * FROM customers 
                WHERE (full_name LIKE ? OR full_name_ar LIKE ? OR phone1 LIKE ? OR phone2 LIKE ? OR phone3 LIKE ? OR customer_code LIKE ?)
                AND is_deleted = 0
                ORDER BY full_name LIMIT 50
                """.trimIndent(),
                arrayOf(likeQuery, likeQuery, likeQuery, likeQuery, likeQuery, likeQuery)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 5. دوال الخدمات (Services)
    // ================================================================

    /**
     * الحصول على خدمة بالكود
     */
    fun getServiceByCode(code: String): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM services WHERE service_code = ? AND is_deleted = 0 LIMIT 1",
                arrayOf(code)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * الحصول على خدمة بالمعرف
     */
    fun getServiceById(id: Int): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM services WHERE id = ? AND is_deleted = 0",
                arrayOf(id.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * جلب جميع الخدمات
     */
    fun getServices(serviceType: String? = null, status: String? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            var sql = "SELECT * FROM services WHERE is_deleted = 0"
            val args = mutableListOf<String>()
            if (serviceType != null) {
                sql += " AND service_type = ?"
                args.add(serviceType)
            }
            if (status != null) {
                sql += " AND status = ?"
                args.add(status)
            }
            sql += " ORDER BY service_name"
            db.rawQuery(sql, args.toTypedArray()).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * إضافة خدمة جديدة
     */
    fun addService(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("service_code", data.optString("service_code", "SRV-${System.currentTimeMillis()}"))
                put("service_name", data.optString("service_name", ""))
                put("service_name_ar", data.optString("service_name_ar", ""))
                put("service_type", data.optString("service_type", "topup"))
                put("price", data.optDouble("price", 0.0))
                put("activation_code", data.optString("activation_code", ""))
                put("activation_command", data.optString("activation_command", ""))
                put("provider", data.optString("provider", ""))
                put("description", data.optString("description", ""))
                put("notes", data.optString("notes", ""))
                put("is_active", if (data.optBoolean("is_active", true)) 1 else 0)
                put("status", data.optString("status", "active"))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            val id = db.insert("services", null, values)
            if (id > 0) logAudit("add_service", "services", id, "إضافة خدمة: ${data.optString("service_name")}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تحديث خدمة
     */
    fun updateService(id: Int, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                if (data.has("service_code") && !data.isNull("service_code")) put("service_code", data.optString("service_code"))
                if (data.has("service_name") && !data.isNull("service_name")) put("service_name", data.optString("service_name"))
                if (data.has("service_name_ar") && !data.isNull("service_name_ar")) put("service_name_ar", data.optString("service_name_ar"))
                if (data.has("service_type") && !data.isNull("service_type")) put("service_type", data.optString("service_type"))
                if (data.has("price") && !data.isNull("price")) put("price", data.optDouble("price"))
                if (data.has("activation_code") && !data.isNull("activation_code")) put("activation_code", data.optString("activation_code"))
                if (data.has("activation_command") && !data.isNull("activation_command")) put("activation_command", data.optString("activation_command"))
                if (data.has("provider") && !data.isNull("provider")) put("provider", data.optString("provider"))
                if (data.has("description") && !data.isNull("description")) put("description", data.optString("description"))
                if (data.has("notes") && !data.isNull("notes")) put("notes", data.optString("notes"))
                if (data.has("is_active")) put("is_active", if (data.optBoolean("is_active")) 1 else 0)
                if (data.has("status") && !data.isNull("status")) put("status", data.optString("status"))
                put("updated_at", getCurrentDateTime())
            }
            if (values.size() <= 1) return 0
            val rows = db.update("services", values, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logAudit("update_service", "services", id.toLong(), "تحديث خدمة: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * حذف خدمة (حذف منطقي)
     */
    fun deleteService(id: Int): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("is_deleted", 1)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("services", values, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logAudit("delete_service", "services", id.toLong(), "حذف خدمة: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * الحصول على سعر الخدمة
     */
    fun getServicePrice(serviceCode: String): Double {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT price FROM services WHERE service_code = ? AND is_active = 1 AND is_deleted = 0",
                arrayOf(serviceCode)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun setActivationCommand(serviceCode: String, command: String): Boolean {
        if (serviceCode.isBlank() || command.length > 200) return false
        dbLock.lock()
        return try {
            val values = ContentValues().apply { put("activation_command", command.trim()); put("updated_at", getCurrentDateTime()) }
            writableDatabase.update("services", values, "service_code = ? AND is_deleted = 0", arrayOf(serviceCode)) > 0
        } finally { dbLock.unlock() }
    }

    /**
     * الحصول على كود التفعيل للخدمة
     */
    fun getActivationCommand(serviceCode: String): String? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT activation_command FROM services WHERE service_code = ? AND is_active = 1 AND is_deleted = 0",
                arrayOf(serviceCode)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * البحث عن الخدمات
     */
    fun searchServices(query: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val likeQuery = "%$query%"
            db.rawQuery(
                """
                SELECT * FROM services 
                WHERE (service_code LIKE ? OR service_name LIKE ? OR service_name_ar LIKE ? OR service_type LIKE ?)
                AND is_deleted = 0
                ORDER BY service_name LIMIT 50
                """.trimIndent(),
                arrayOf(likeQuery, likeQuery, likeQuery, likeQuery)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 6. دوال الموردين (Suppliers)
    // ================================================================

    /**
     * الحصول على مورد برقم الهاتف
     */
    fun getSupplierByPhone(phone: String): JSONObject? {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM suppliers WHERE (phone1 = ? OR phone2 = ? OR phone3 = ?) AND is_deleted = 0 LIMIT 1",
                arrayOf(normalizedPhone, normalizedPhone, normalizedPhone)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * الحصول على مورد بالمعرف
     */
    fun getSupplierById(id: Int): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM suppliers WHERE id = ? AND is_deleted = 0",
                arrayOf(id.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * جلب جميع الموردين
     */
    fun getSuppliers(status: String? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = if (status != null) {
                "SELECT * FROM suppliers WHERE status = ? AND is_deleted = 0 ORDER BY name"
            } else {
                "SELECT * FROM suppliers WHERE is_deleted = 0 ORDER BY name"
            }
            val args = if (status != null) arrayOf(status) else null
            db.rawQuery(sql, args).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * إضافة مورد جديد
     */
    fun addSupplier(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val code = data.optString("supplier_code", "SUP-${System.currentTimeMillis()}")
            val values = ContentValues().apply {
                put("supplier_code", code)
                put("name", data.optString("name", ""))
                put("name_ar", data.optString("name_ar", ""))
                put("phone1", normalizePhone(data.optString("phone1", "")))
                put("phone2", normalizePhone(data.optString("phone2", "")))
                put("phone3", normalizePhone(data.optString("phone3", "")))
                put("our_balance", data.optDouble("our_balance", 0.0))
                put("their_balance", data.optDouble("their_balance", 0.0))
                put("status", data.optString("status", "active"))
                put("details", data.optString("details", ""))
                put("notes", data.optString("notes", ""))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            val id = db.insert("suppliers", null, values)
            if (id > 0) logAudit("add_supplier", "suppliers", id, "إضافة مورد: ${data.optString("name")}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تحديث مورد
     */
    fun updateSupplier(id: Int, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                if (data.has("name") && !data.isNull("name")) put("name", data.optString("name"))
                if (data.has("name_ar") && !data.isNull("name_ar")) put("name_ar", data.optString("name_ar"))
                if (data.has("phone1") && !data.isNull("phone1")) put("phone1", normalizePhone(data.optString("phone1")))
                if (data.has("phone2") && !data.isNull("phone2")) put("phone2", normalizePhone(data.optString("phone2")))
                if (data.has("phone3") && !data.isNull("phone3")) put("phone3", normalizePhone(data.optString("phone3")))
                if (data.has("our_balance") && !data.isNull("our_balance")) put("our_balance", data.optDouble("our_balance"))
                if (data.has("their_balance") && !data.isNull("their_balance")) put("their_balance", data.optDouble("their_balance"))
                if (data.has("status") && !data.isNull("status")) put("status", data.optString("status"))
                if (data.has("details") && !data.isNull("details")) put("details", data.optString("details"))
                if (data.has("notes") && !data.isNull("notes")) put("notes", data.optString("notes"))
                put("updated_at", getCurrentDateTime())
            }
            if (values.size() <= 1) return 0
            val rows = db.update("suppliers", values, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logAudit("update_supplier", "suppliers", id.toLong(), "تحديث مورد: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تحديث رصيد المورد
     */
    fun updateSupplierBalance(supplierId: Int, amount: Double, isOurBalance: Boolean = true): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val column = if (isOurBalance) "our_balance" else "their_balance"
            val values = ContentValues().apply {
                put(column, amount)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("suppliers", values, "id = ?", arrayOf(supplierId.toString()))
            if (rows > 0) logAudit("balance_update", "suppliers", supplierId.toLong(), "تحديث رصيد: $amount")
            rows > 0
        } finally {
            dbLock.unlock()
        }
    }

    /** حذف منطقي لمورد */
    fun deleteSupplier(id: Int): Int {
        dbLock.lock()
        return try {
            val values = ContentValues().apply {
                put("is_deleted", 1)
                put("updated_at", getCurrentDateTime())
            }
            val rows = writableDatabase.update("suppliers", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) logAudit("delete_supplier", "suppliers", id.toLong(), "حذف مورد: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * البحث عن الموردين
     */
    fun searchSuppliers(query: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val likeQuery = "%$query%"
            db.rawQuery(
                """
                SELECT * FROM suppliers 
                WHERE (name LIKE ? OR name_ar LIKE ? OR phone1 LIKE ? OR phone2 LIKE ? OR phone3 LIKE ?)
                AND is_deleted = 0
                ORDER BY name LIMIT 50
                """.trimIndent(),
                arrayOf(likeQuery, likeQuery, likeQuery, likeQuery, likeQuery)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 7. دوال الحوالات (Transfers)
    // ================================================================

    /**
     * تسجيل حوالة واردة من مورد
     */
    fun recordTransfer(
        supplierId: Int,
        customerPhone: String,
        customerName: String?,
        amount: Double,
        fee: Double = 0.0,
        senderName: String? = null,
        referenceNumber: String? = null,
        transferDate: String = getCurrentDateTime()
    ): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val code = "TRF-${System.currentTimeMillis()}"
                val netAmount = amount - fee

                val values = ContentValues().apply {
                    put("transfer_code", code)
                    put("supplier_id", supplierId)
                    put("customer_phone", normalizePhone(customerPhone))
                    put("customer_name", customerName ?: "")
                    put("amount", amount)
                    put("fee", fee)
                    put("net_amount", netAmount)
                    put("sender_name", senderName ?: "")
                    put("reference_number", referenceNumber ?: "")
                    put("transfer_date", transferDate)
                    put("status", "pending")
                    put("created_at", getCurrentDateTime())
                    put("updated_at", getCurrentDateTime())
                }

                val transferId = db.insert("transfers", null, values)

                // تحديث رصيد المورد (يزيد رصيدنا لديهم)
                if (transferId > 0) {
                    db.execSQL(
                        "UPDATE suppliers SET our_balance = our_balance + ? WHERE id = ?",
                        arrayOf(amount, supplierId)
                    )
                }

                // البحث عن العميل
                val customer = getCustomerByPhone(customerPhone)
                if (customer != null) {
                    // تحديث رصيد العميل
                    val customerId = customer.optInt("id")
                    db.execSQL(
                        "UPDATE customers SET balance_deposit = balance_deposit + ? WHERE id = ?",
                        arrayOf(netAmount, customerId)
                    )
                    db.execSQL(
                        "UPDATE customers SET current_balance = current_balance + ? WHERE id = ?",
                        arrayOf(netAmount, customerId)
                    )
                }

                db.setTransactionSuccessful()
                logAudit("record_transfer", "transfers", transferId, "حوالة: $amount من $supplierId للعميل $customerPhone")
                transferId
            } finally {
                db.endTransaction()
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تحديث حالة حوالة
     */
    fun updateTransferStatus(transferId: Int, status: String, notes: String? = null): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("status", status)
                if (status == "processed") {
                    put("processed_at", getCurrentDateTime())
                }
                notes?.let { put("notes", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("transfers", values, "id = ?", arrayOf(transferId.toString()))
            if (rows > 0) logAudit("update_transfer", "transfers", transferId.toLong(), "تحديث حالة حوالة: $status")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * جلب جميع الحوالات
     */
    fun getTransfers(status: String? = null, supplierId: Int? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            var sql = "SELECT t.*, s.name as supplier_name FROM transfers t LEFT JOIN suppliers s ON t.supplier_id = s.id WHERE t.is_deleted = 0"
            val args = mutableListOf<String>()
            if (status != null) {
                sql += " AND t.status = ?"
                args.add(status)
            }
            if (supplierId != null) {
                sql += " AND t.supplier_id = ?"
                args.add(supplierId.toString())
            }
            sql += " ORDER BY t.created_at DESC"
            db.rawQuery(sql, args.toTypedArray()).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * جلب حوالات العميل
     */
    fun getCustomerTransfers(phone: String): JSONArray {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT t.*, s.name as supplier_name FROM transfers t LEFT JOIN suppliers s ON t.supplier_id = s.id WHERE t.customer_phone = ? AND t.is_deleted = 0 ORDER BY t.created_at DESC",
                arrayOf(normalizedPhone)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * جلب حوالات المورد
     */
    fun getSupplierTransfers(supplierId: Int): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM transfers WHERE supplier_id = ? AND is_deleted = 0 ORDER BY created_at DESC",
                arrayOf(supplierId.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 8. دوال التفعيل (Activations)
    // ================================================================

    /**
     * تفعيل خدمة لعميل
     */
    fun activateService(
        customerId: Int,
        serviceId: Int,
        phoneNumber: String,
        amount: Double,
        paymentMethod: String,
        transferId: Int? = null
    ): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                // الحصول على أمر التفعيل
                var activationCommand: String? = null
                db.rawQuery(
                    "SELECT activation_command FROM services WHERE id = ? AND is_active = 1",
                    arrayOf(serviceId.toString())
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        activationCommand = cursor.getString(0)
                    } else {
                        return -1
                    }
                }

                val code = "ACT-${System.currentTimeMillis()}"

                val values = ContentValues().apply {
                    put("activation_code", code)
                    put("customer_id", customerId)
                    put("service_id", serviceId)
                    put("phone_number", normalizePhone(phoneNumber))
                    put("amount", amount)
                    put("payment_method", paymentMethod)
                    if (transferId != null) put("transfer_id", transferId)
                    put("activation_command", activationCommand ?: "")
                    put("status", "pending")
                    put("created_at", getCurrentDateTime())
                }

                val activationId = db.insert("activations", null, values)

                if (activationId > 0) {
                    // تحديث رصيد العميل (خصم)
                    if (paymentMethod == "balance" || paymentMethod == "transfer") {
                        db.execSQL(
                            "UPDATE customers SET current_balance = current_balance - ?, total_purchases = total_purchases + ? WHERE id = ?",
                            arrayOf(amount, amount, customerId)
                        )
                    } else if (paymentMethod == "credit") {
                        db.execSQL(
                            "UPDATE customers SET current_balance = current_balance + ?, total_purchases = total_purchases + ? WHERE id = ?",
                            arrayOf(amount, amount, customerId)
                        )
                    }

                    // تحديث حالة الحوالة إذا كانت موجودة
                    if (transferId != null) {
                        updateTransferStatus(transferId, "processed", "تم التفعيل للخدمة $serviceId")
                    }
                }

                db.setTransactionSuccessful()
                logAudit("activate_service", "activations", activationId, "تفعيل خدمة $serviceId للعميل $customerId")
                activationId
            } finally {
                db.endTransaction()
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تحديث حالة التفعيل
     */
    fun updateActivationStatus(activationId: Int, status: String, statusMessage: String? = null): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("status", status)
                if (status == "success") {
                    put("activated_at", getCurrentDateTime())
                }
                statusMessage?.let { put("status_message", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("activations", values, "id = ?", arrayOf(activationId.toString()))
            if (rows > 0) logAudit("update_activation", "activations", activationId.toLong(), "تحديث حالة تفعيل: $status")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * جلب جميع عمليات التفعيل
     */
    fun getActivations(status: String? = null, customerId: Int? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            var sql = """
                SELECT a.*, c.full_name as customer_name, s.service_name, s.service_code 
                FROM activations a
                LEFT JOIN customers c ON a.customer_id = c.id
                LEFT JOIN services s ON a.service_id = s.id
                WHERE a.is_deleted = 0
            """.trimIndent()
            val args = mutableListOf<String>()
            if (status != null) {
                sql += " AND a.status = ?"
                args.add(status)
            }
            if (customerId != null) {
                sql += " AND a.customer_id = ?"
                args.add(customerId.toString())
            }
            sql += " ORDER BY a.created_at DESC"
            db.rawQuery(sql, args.toTypedArray()).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * جلب سجل تفعيل العميل
     */
    fun getCustomerActivations(customerId: Int): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """
                SELECT a.*, s.service_name, s.service_code 
                FROM activations a
                LEFT JOIN services s ON a.service_id = s.id
                WHERE a.customer_id = ? AND a.is_deleted = 0
                ORDER BY a.created_at DESC
                """.trimIndent(),
                arrayOf(customerId.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 9. دوال SMS (الأمان والمحادثات)
    // ================================================================

    // ==================== 9.1 منع التكرار ====================

    fun isSmsAlreadyProcessed(messageHash: String): Boolean {
        if (messageHash.isBlank()) return false
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT 1 FROM sms_processed_hashes WHERE message_hash = ? LIMIT 1",
                arrayOf(messageHash)
            ).use { cursor -> cursor.moveToFirst() }
        } finally {
            dbLock.unlock()
        }
    }

    fun markSmsProcessed(messageHash: String, phone: String, message: String) {
        if (messageHash.isBlank()) return
        dbLock.lock()
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("message_hash", messageHash)
                put("phone", normalizePhone(phone))
                put("message_preview", message.take(100))
                put("processed_at", System.currentTimeMillis())
                put("created_at", getCurrentDateTime())
            }
            db.insertWithOnConflict(
                "sms_processed_hashes", null, values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } finally {
            dbLock.unlock()
        }
    }

    fun cleanupSmsProcessedMessages(retentionDays: Int = 7): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60 * 60 * 1000)
            db.delete("sms_processed_hashes", "processed_at < ?", arrayOf(cutoff.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    // ==================== 9.2 حدود المعدل ====================

    fun getSmsRateLimit(phone: String): JSONObject? {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM sms_rate_limits WHERE phone = ?",
                arrayOf(normalizedPhone)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun updateSmsRateLimit(phone: String, data: JSONObject): Boolean {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                data.optLong("last_reply_at")?.let { put("last_reply_at", it) }
                data.optLong("day_start")?.let { put("day_start", it) }
                data.optInt("daily_count")?.let { put("daily_count", it) }
                data.optInt("warning_count")?.let { put("warning_count", it) }
                data.optLong("blocked_until")?.let { put("blocked_until", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("sms_rate_limits", values, "phone = ?", arrayOf(normalizedPhone))
            if (rows == 0) {
                val insertValues = ContentValues().apply {
                    put("phone", normalizedPhone)
                    data.optLong("last_reply_at")?.let { put("last_reply_at", it) }
                    data.optLong("day_start")?.let { put("day_start", it) }
                    data.optInt("daily_count")?.let { put("daily_count", it) }
                    data.optInt("warning_count")?.let { put("warning_count", it) }
                    data.optLong("blocked_until")?.let { put("blocked_until", it) }
                    put("updated_at", getCurrentDateTime())
                }
                db.insert("sms_rate_limits", null, insertValues)
            }
            true
        } finally {
            dbLock.unlock()
        }
    }

    // ==================== 9.3 سياق المحادثة ====================

    fun getSmsConversationContext(phone: String): JSONObject? {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM sms_conversation_context WHERE phone = ?",
                arrayOf(normalizedPhone)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun updateSmsConversationContext(phone: String, data: JSONObject): Boolean {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                data.optString("last_topic")?.let { put("last_topic", it) }
                data.optString("last_intent")?.let { put("last_intent", it) }
                data.optLong("timestamp")?.let { put("timestamp", it) }
                data.optString("pending_action")?.let { put("pending_action", it) }
                data.optInt("awaiting_response")?.let { put("awaiting_response", it) }
                data.optString("data_json")?.let { put("data_json", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("sms_conversation_context", values, "phone = ?", arrayOf(normalizedPhone))
            if (rows == 0) {
                val insertValues = ContentValues().apply {
                    put("phone", normalizedPhone)
                    data.optString("last_topic")?.let { put("last_topic", it) }
                    data.optString("last_intent")?.let { put("last_intent", it) }
                    data.optLong("timestamp")?.let { put("timestamp", it) }
                    data.optString("pending_action")?.let { put("pending_action", it) }
                    data.optInt("awaiting_response")?.let { put("awaiting_response", it) }
                    data.optString("data_json")?.let { put("data_json", it) }
                    put("updated_at", getCurrentDateTime())
                }
                db.insert("sms_conversation_context", null, insertValues)
            }
            true
        } finally {
            dbLock.unlock()
        }
    }

    // ==================== 9.4 تفضيلات العميل ====================

    fun getSmsCustomerPreferences(phone: String): JSONObject? {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM sms_customer_preferences WHERE phone = ?",
                arrayOf(normalizedPhone)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun updateSmsCustomerPreferences(phone: String, data: JSONObject): Boolean {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                data.optString("preferred_service")?.let { put("preferred_service", it) }
                data.optDouble("preferred_amount")?.let { put("preferred_amount", it) }
                data.optString("preferred_operator")?.let { put("preferred_operator", it) }
                data.optLong("last_order_date")?.let { put("last_order_date", it) }
                data.optInt("order_count")?.let { put("order_count", it) }
                data.optString("language")?.let { put("language", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("sms_customer_preferences", values, "phone = ?", arrayOf(normalizedPhone))
            if (rows == 0) {
                val insertValues = ContentValues().apply {
                    put("phone", normalizedPhone)
                    data.optString("preferred_service")?.let { put("preferred_service", it) }
                    data.optDouble("preferred_amount")?.let { put("preferred_amount", it) }
                    data.optString("preferred_operator")?.let { put("preferred_operator", it) }
                    data.optLong("last_order_date")?.let { put("last_order_date", it) }
                    data.optInt("order_count")?.let { put("order_count", it) }
                    data.optString("language")?.let { put("language", it) }
                    put("updated_at", getCurrentDateTime())
                }
                db.insert("sms_customer_preferences", null, insertValues)
            }
            true
        } finally {
            dbLock.unlock()
        }
    }

    // ==================== 9.5 سجل التفاعلات ====================

    fun insertSmsInteraction(phone: String, intent: String, message: String? = null): Long {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("phone", normalizedPhone)
                put("timestamp", System.currentTimeMillis())
                put("intent", intent)
                message?.let { put("message", it) }
                put("created_at", getCurrentDateTime())
            }
            db.insert("sms_interaction_history", null, values)
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsInteractionHistory(phone: String, limit: Int = 20): JSONArray {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM sms_interaction_history WHERE phone = ? ORDER BY timestamp DESC LIMIT ?",
                arrayOf(normalizedPhone, limit.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ==================== 9.6 المقاييس ====================

    fun insertSmsMetric(eventType: String, phone: String? = null, details: String? = null): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val dateStr = getCurrentDate()
            val values = ContentValues().apply {
                put("event_type", eventType)
                phone?.let { put("phone", normalizePhone(it)) }
                details?.let { put("details", details.take(200)) }
                put("timestamp", now)
                put("date", dateStr)
                put("created_at", getCurrentDateTime())
            }
            db.insert("sms_metrics", null, values)
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsMetrics(eventType: String? = null, fromDate: String? = null, toDate: String? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            var sql = "SELECT * FROM sms_metrics WHERE 1=1"
            val args = mutableListOf<String>()
            if (eventType != null) {
                sql += " AND event_type = ?"
                args.add(eventType)
            }
            if (fromDate != null) {
                sql += " AND date >= ?"
                args.add(fromDate)
            }
            if (toDate != null) {
                sql += " AND date <= ?"
                args.add(toDate)
            }
            sql += " ORDER BY timestamp DESC LIMIT 1000"
            db.rawQuery(sql, args.toTypedArray()).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /** Writes every row from a whitelisted collection as comma-separated JSON objects. */
    fun writeExportRows(collection: String, writer: Writer): Int {
        val sql = when (collection) {
            "services" -> "SELECT * FROM services WHERE is_deleted=0 ORDER BY id"
            "customers" -> "SELECT * FROM customers WHERE is_deleted=0 ORDER BY id"
            "sms" -> "SELECT * FROM sms_metrics ORDER BY timestamp DESC, id DESC"
            else -> throw IllegalArgumentException("نوع التصدير غير مدعوم")
        }
        dbLock.lock()
        return try {
            var written = 0
            readableDatabase.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (written > 0) writer.append(',')
                    val row = JSONObject()
                    for (column in 0 until cursor.columnCount) {
                        val value: Any = if (cursor.isNull(column)) JSONObject.NULL else when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(column), Base64.NO_WRAP)
                            else -> cursor.getString(column)
                        }
                        row.put(cursor.getColumnName(column), value)
                    }
                    writer.append(row.toString())
                    written++
                }
            }
            written
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteSmsMetric(id: Int): Int {
        dbLock.lock()
        return try {
            writableDatabase.delete("sms_metrics", "id = ?", arrayOf(id.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    // ==================== 9.7 OTP ====================

    fun createSmsOtp(phone: String, otpCode: String, expirySeconds: Int = 300): Boolean {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val expiresAt = now + (expirySeconds * 1000L)
            val values = ContentValues().apply {
                put("phone", normalizedPhone)
                put("otp_code", otpCode)
                put("timestamp", now)
                put("attempts", 0)
                put("max_attempts", 3)
                put("expires_at", expiresAt)
                put("created_at", getCurrentDateTime())
            }
            db.insertWithOnConflict(
                "sms_otp_verifications", null, values,
                SQLiteDatabase.CONFLICT_REPLACE
            ) > 0
        } finally {
            dbLock.unlock()
        }
    }

    fun verifySmsOtp(phone: String, otpCode: String): Boolean {
        dbLock.lock()
        val normalizedPhone = normalizePhone(phone)
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            db.rawQuery(
                "SELECT * FROM sms_otp_verifications WHERE phone = ? AND otp_code = ? AND expires_at > ?",
                arrayOf(normalizedPhone, otpCode, now.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    db.delete("sms_otp_verifications", "phone = ?", arrayOf(normalizedPhone))
                    true
                } else {
                    db.execSQL(
                        "UPDATE sms_otp_verifications SET attempts = attempts + 1 WHERE phone = ?",
                        arrayOf(normalizedPhone)
                    )
                    false
                }
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun cleanupExpiredSmsOtps(): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            db.delete("sms_otp_verifications", "expires_at < ?", arrayOf(now.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 10. دوال التقارير والإحصائيات
    // ================================================================

    /**
     * إحصائيات لوحة التحكم
     */
    fun getDashboardStats(): JSONObject {
        val stats = JSONObject()
        val db = readableDatabase

        // عدد العملاء
        db.rawQuery("SELECT COUNT(*) FROM customers WHERE is_deleted = 0 AND status = 'active'", null).use { cursor ->
            stats.put("total_customers", if (cursor.moveToFirst()) cursor.getInt(0) else 0)
        }

        // إجمالي الرصيد المودع
        db.rawQuery("SELECT COALESCE(SUM(balance_deposit), 0) FROM customers WHERE is_deleted = 0", null).use { cursor ->
            stats.put("total_deposit", if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0)
        }

        // إجمالي الرصيد الحالي (المدين)
        db.rawQuery("SELECT COALESCE(SUM(current_balance), 0) FROM customers WHERE is_deleted = 0", null).use { cursor ->
            stats.put("total_balance", if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0)
        }

        // إجمالي الخدمات
        db.rawQuery("SELECT COUNT(*) FROM services WHERE is_deleted = 0 AND is_active = 1", null).use { cursor ->
            stats.put("total_services", if (cursor.moveToFirst()) cursor.getInt(0) else 0)
        }

        // إجمالي الموردين
        db.rawQuery("SELECT COUNT(*) FROM suppliers WHERE is_deleted = 0 AND status = 'active'", null).use { cursor ->
            stats.put("total_suppliers", if (cursor.moveToFirst()) cursor.getInt(0) else 0)
        }

        // إجمالي الحوالات اليوم
        val today = getCurrentDate()
        db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM transfers WHERE date(transfer_date) = ? AND status = 'processed'",
            arrayOf(today)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                stats.put("today_transfers_count", cursor.getInt(0))
                stats.put("today_transfers_amount", cursor.getDouble(1))
            }
        }

        // إجمالي التفعيلات اليوم
        db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM activations WHERE date(created_at) = ? AND status = 'success'",
            arrayOf(today)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                stats.put("today_activations_count", cursor.getInt(0))
                stats.put("today_activations_amount", cursor.getDouble(1))
            }
        }

        // الحوالات المعلقة
        db.rawQuery("SELECT COUNT(*) FROM transfers WHERE status = 'pending'", null).use { cursor ->
            stats.put("pending_transfers", if (cursor.moveToFirst()) cursor.getInt(0) else 0)
        }

        // التفعيلات المعلقة
        db.rawQuery("SELECT COUNT(*) FROM activations WHERE status = 'pending'", null).use { cursor ->
            stats.put("pending_activations", if (cursor.moveToFirst()) cursor.getInt(0) else 0)
        }

        return stats
    }

    /** Purpose-built report queries: summary buttons aggregate in SQLite and detail views are bounded. */
    fun getOperationalReport(type: String): JSONArray {
        val sql = when (type) {
            "services_summary" -> """
                SELECT service_type, COUNT(*) AS service_count,
                       SUM(CASE WHEN status='active' AND is_active=1 THEN 1 ELSE 0 END) AS active_count,
                       ROUND(AVG(price), 2) AS average_price
                FROM services WHERE is_deleted=0 GROUP BY service_type ORDER BY service_type
            """.trimIndent()
            "services_by_status" -> """
                SELECT status, COUNT(*) AS service_count, ROUND(AVG(price), 2) AS average_price
                FROM services WHERE is_deleted=0 GROUP BY status ORDER BY status
            """.trimIndent()
            "services_pricing" -> """
                SELECT service_type, COUNT(*) AS service_count, MIN(price) AS minimum_price,
                       ROUND(AVG(price), 2) AS average_price, MAX(price) AS maximum_price
                FROM services WHERE is_deleted=0 GROUP BY service_type ORDER BY service_type
            """.trimIndent()
            "full_services" -> """
                SELECT s.id, s.service_code, s.service_name, s.service_type, s.price, s.is_active, s.status,
                       (SELECT COUNT(*) FROM activations a WHERE a.service_id=s.id AND a.status='success') AS activation_count,
                       (SELECT COALESCE(SUM(a.amount), 0) FROM activations a WHERE a.service_id=s.id AND a.status='success') AS total_revenue
                FROM services s WHERE s.is_deleted=0 ORDER BY s.service_name LIMIT 2000
            """.trimIndent()
            "customers_list", "full_customers" -> """
                SELECT id, customer_code, full_name, phone1, balance_deposit, current_balance,
                       allow_credit, credit_limit, status, total_purchases, total_payments
                FROM customers WHERE is_deleted=0 ORDER BY full_name LIMIT 2000
            """.trimIndent()
            "customers_balance" -> """
                SELECT CASE WHEN current_balance > 0 THEN 'مدين' WHEN current_balance < 0 THEN 'دائن' ELSE 'متوازن' END AS balance_status,
                       COUNT(*) AS customer_count, ROUND(SUM(current_balance), 2) AS total_balance,
                       ROUND(AVG(current_balance), 2) AS average_balance
                FROM customers WHERE is_deleted=0 GROUP BY balance_status ORDER BY balance_status
            """.trimIndent()
            "customers_credit" -> """
                SELECT full_name, phone1, allow_credit, credit_limit, current_balance,
                       ROUND(MAX(credit_limit-current_balance, 0), 2) AS available_credit,
                       CASE WHEN allow_credit=1 THEN 'مسموح' ELSE 'غير مسموح' END AS credit_status
                FROM customers WHERE is_deleted=0 ORDER BY allow_credit DESC, current_balance DESC LIMIT 2000
            """.trimIndent()
            "sms_summary" -> """
                SELECT COUNT(*) AS total_messages, COUNT(DISTINCT phone) AS unique_senders,
                       SUM(CASE WHEN lower(event_type) LIKE '%fail%' OR lower(event_type) LIKE '%reject%' THEN 1 ELSE 0 END) AS failed_messages,
                       MAX(timestamp) AS latest_timestamp
                FROM sms_metrics
            """.trimIndent()
            "sms_by_status" -> """
                SELECT CASE
                         WHEN lower(event_type) LIKE '%fail%' OR lower(event_type) LIKE '%reject%' OR lower(event_type) LIKE '%error%' THEN 'فاشلة'
                         WHEN lower(event_type) LIKE '%process%' OR lower(event_type) LIKE '%success%' OR lower(event_type) LIKE '%verified%' OR lower(event_type) LIKE '%sent%' THEN 'معالجة'
                         ELSE 'مستلمة/أخرى'
                       END AS status,
                       COUNT(*) AS message_count, COUNT(DISTINCT phone) AS unique_senders
                FROM sms_metrics GROUP BY status ORDER BY status
            """.trimIndent()
            "sms_analysis" -> """
                SELECT event_type, COUNT(*) AS message_count, COUNT(DISTINCT phone) AS unique_senders,
                       ROUND(AVG(length(COALESCE(details, ''))), 1) AS average_content_length,
                       MAX(timestamp) AS latest_timestamp
                FROM sms_metrics GROUP BY event_type ORDER BY message_count DESC, event_type LIMIT 200
            """.trimIndent()
            "full_sms" -> "SELECT * FROM sms_metrics ORDER BY timestamp DESC, id DESC LIMIT 1000"
            else -> throw IllegalArgumentException("نوع التقرير غير معروف")
        }
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(sql, null).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تقرير العملاء
     */
    fun getCustomerReport(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """
                SELECT 
                    id, customer_code, full_name, phone1, balance_deposit,
                    current_balance, allow_credit, credit_limit, status,
                    total_purchases, total_payments,
                    (SELECT COUNT(*) FROM activations a WHERE a.customer_id = c.id AND a.status = 'success') as activation_count,
                    (SELECT COALESCE(SUM(amount), 0) FROM transfers t WHERE t.customer_phone = c.phone1 AND t.status = 'processed') as total_transfer
                FROM customers c
                WHERE is_deleted = 0
                ORDER BY full_name
                """.trimIndent(),
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تقرير الخدمات
     */
    fun getServiceReport(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """
                SELECT 
                    s.id, s.service_code, s.service_name, s.service_type,
                    s.price, s.is_active, s.status,
                    (SELECT COUNT(*) FROM activations a WHERE a.service_id = s.id AND a.status = 'success') as activation_count,
                    (SELECT COALESCE(SUM(a.amount), 0) FROM activations a WHERE a.service_id = s.id AND a.status = 'success') as total_revenue
                FROM services s
                WHERE is_deleted = 0
                ORDER BY service_name
                """.trimIndent(),
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تقرير الموردين
     */
    fun getSupplierReport(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """
                SELECT 
                    s.id, s.supplier_code, s.name, s.phone1, s.our_balance,
                    s.their_balance, s.status,
                    (SELECT COUNT(*) FROM transfers t WHERE t.supplier_id = s.id AND t.status = 'processed') as transfer_count,
                    (SELECT COALESCE(SUM(t.amount), 0) FROM transfers t WHERE t.supplier_id = s.id AND t.status = 'processed') as total_transfer
                FROM suppliers s
                WHERE is_deleted = 0
                ORDER BY name
                """.trimIndent(),
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 11. دوال الإعدادات
    // ================================================================

    fun getSetting(key: String): String {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT setting_value FROM system_settings WHERE setting_key = ?",
                arrayOf(key)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun setSetting(key: String, value: String): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("setting_value", value)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("system_settings", values, "setting_key = ?", arrayOf(key))
            if (rows == 0) {
                val insertValues = ContentValues().apply {
                    put("setting_key", key)
                    put("setting_value", value)
                    put("data_type", "string")
                    put("category", "general")
                    put("created_at", getCurrentDateTime())
                    put("updated_at", getCurrentDateTime())
                }
                db.insert("system_settings", null, insertValues)
            }
            true
        } finally {
            dbLock.unlock()
        }
    }

    fun getAllSettings(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT * FROM system_settings ORDER BY category, setting_key", null)
                .use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 12. سجل التدقيق (Audit Log)
    // ================================================================

    private fun logAudit(action: String, table: String, recordId: Long, description: String) {
        try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("action", action)
                put("action_category", table)
                put("target_table", table)
                put("target_id", recordId)
                put("description", description)
                put("created_at", getCurrentDateTime())
            }
            db.insert("audit_log", null, values)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log audit: ${e.message}")
        }
    }

    fun getAuditLog(limit: Int = 100): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 13. دوال الصيانة
    // ================================================================

    fun cleanupOldData(retentionDays: Int): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cutoff = getCurrentDate()
            // حذف السجلات القديمة من audit_log
            db.delete("audit_log", "date(created_at) < date('now', '-$retentionDays days')", null)
        } finally {
            dbLock.unlock()
        }
    }

    fun vacuumDatabase() {
        dbLock.lock()
        try {
            writableDatabase.execSQL("VACUUM")
        } finally {
            dbLock.unlock()
        }
    }

    fun getDatabaseSize(): Long {
        return try {
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.length()
        } catch (e: Exception) {
            0L
        }
    }

    // ================================================================
    // 14. دوال مساعدة
    // ================================================================

    private fun normalizePhone(phone: String): String = SmsTextAnalysis.normalizePhone(phone)

    private fun cursorToJsonArray(cursor: Cursor): JSONArray {
        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                arr.put(cursorToJsonObject(it))
            }
        }
        return arr
    }

    private fun cursorToJsonObject(cursor: Cursor): JSONObject {
        val obj = JSONObject()
        for (i in 0 until cursor.columnCount) {
            val colName = cursor.getColumnName(i)
            try {
                when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_STRING -> obj.put(colName, cursor.getString(i) ?: "")
                    Cursor.FIELD_TYPE_INTEGER -> obj.put(colName, cursor.getInt(i))
                    Cursor.FIELD_TYPE_FLOAT -> obj.put(colName, cursor.getDouble(i))
                    Cursor.FIELD_TYPE_BLOB -> obj.put(colName, cursor.getBlob(i))
                    else -> obj.put(colName, "")
                }
            } catch (e: Exception) {
                obj.put(colName, "")
            }
        }
        return obj
    }

    // دوال لتوافق مع الكود الموجود في SMS modules
    fun recordEvent(eventType: String, phone: String? = null, details: String? = null, timestamp: Long = System.currentTimeMillis()): Long {
        return insertSmsMetric(eventType, phone, details)
    }

    fun logSms(phone: String, message: String, type: String, status: String): Long {
        return insertSmsMetric("SMS_${type.uppercase()}_${status.uppercase()}", phone, message.take(200))
    }

    // دوال لتوافق مع SmsCustomerResolver
    fun insertSaleTransaction(
        stationId: Int,
        shiftId: Int,
        customerPartyId: Int?,
        fuelTypeId: Int?,
        pumpId: Int?,
        nozzleId: Int?,
        liters: Double,
        pricePerLiter: Double,
        subtotal: Double,
        discountAmount: Double,
        taxAmount: Double,
        grossAmount: Double,
        netAmount: Double,
        paymentMethod: String,
        isCredit: Boolean,
        dueDate: String?,
        cashierId: Int,
        notes: String,
        deliveryLocation: String? = null,
        deliveryTime: String? = null,
        orderType: String = "sale"
    ): Long {
        // تم تبسيط هذه الدالة لتوافق مع نظام التفعيل
        // سيتم استخدامها فقط من SmsCustomerResolver
        return 0
    }
}
