// DatabaseHelper.kt
package com.aistudio.telecomsms

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
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
class DatabaseHelper private constructor(context: Context) : 
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "telecom_activator.db"
        const val VERSION = 1

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
        // سيتم تنفيذ الترحيلات عند الحاجة
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
        
        // الخدمات
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_code ON services(service_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_type ON services(service_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_status ON services(status)")
        
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
    }

    // ================================================================
    // 3. البيانات الأولية
    // ================================================================

    private fun insertInitialData(db: SQLiteDatabase) {
        // ==================== الخدمات الافتراضية ====================
        val services = listOf(
            arrayOf("TOPUP-500", "شحن 500 ريال", "500 ريال", "topup", "500.00", "*101*500#", "MTN"),
            arrayOf("TOPUP-1000", "شحن 1000 ريال", "1000 ريال", "topup", "1000.00", "*101*1000#", "MTN"),
            arrayOf("TOPUP-2000", "شحن 2000 ريال", "2000 ريال", "topup", "2000.00", "*101*2000#", "MTN"),
            arrayOf("INTERNET-1GB", "باقة إنترنت 1 جيجا", "1 جيجا إنترنت", "internet", "1500.00", "*105*1#", "MTN"),
            arrayOf("INTERNET-5GB", "باقة إنترنت 5 جيجا", "5 جيجا إنترنت", "internet", "5000.00", "*105*5#", "MTN"),
            arrayOf("INTERNET-10GB", "باقة إنترنت 10 جيجا", "10 جيجا إنترنت", "internet", "8000.00", "*105*10#", "MTN"),
            arrayOf("SMS-100", "باقة رسائل 100", "100 رسالة", "sms", "500.00", "*106*100#", "MTN"),
            arrayOf("SMS-500", "باقة رسائل 500", "500 رسالة", "sms", "2000.00", "*106*500#", "MTN"),
            arrayOf("CALLS-100", "باقة مكالمات 100 دقيقة", "100 دقيقة", "call", "1500.00", "*107*100#", "MTN"),
            arrayOf("CALLS-300", "باقة مكالمات 300 دقيقة", "300 دقيقة", "call", "4000.00", "*107*300#", "MTN"),
            arrayOf("PACKAGE-GOLD", "باقة الذهبية", "باقة شاملة", "package", "10000.00", "*108*GOLD#", "MTN"),
            arrayOf("PACKAGE-SILVER", "باقة الفضية", "باقة شاملة", "package", "5000.00", "*108*SILVER#", "MTN")
        )

        for (s in services) {
            db.execSQL("""
                INSERT OR IGNORE INTO services 
                (service_code, service_name, service_name_ar, service_type, price, activation_command, provider, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
            """, s)
        }

        // ==================== عملاء افتراضيون ====================
        db.execSQL("""
            INSERT OR IGNORE INTO customers 
            (customer_code, full_name, phone1, balance_deposit, allow_credit, credit_limit, current_balance, status)
            VALUES 
            ('CUS-001', 'أحمد محمد', '777000001', 10000.00, 1, 5000.00, 0.00, 'active'),
            ('CUS-002', 'خالد علي', '777000002', 5000.00, 1, 3000.00, 0.00, 'active'),
            ('CUS-003', 'سالم عبدالله', '777000003', 2000.00, 0, 0.00, 0.00, 'active'),
            ('CUS-004', 'محمد عبدالرحمن', '777000004', 0.00, 1, 2000.00, 1500.00, 'active')
        """)

        // ==================== موردون افتراضيون ====================
        db.execSQL("""
            INSERT OR IGNORE INTO suppliers 
            (supplier_code, name, phone1, our_balance, their_balance, status)
            VALUES 
            ('SUP-001', 'شركة الحوالات اليمنية', '771000001', 50000.00, 0.00, 'active'),
            ('SUP-002', 'الحوالات السريعة', '772000002', 30000.00, 0.00, 'active'),
            ('SUP-003', 'تحويلات الخير', '773000003', 20000.00, 0.00, 'active')
        """)

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
                "SELECT * FROM customers WHERE phone1 = ? OR phone2 = ? OR phone3 = ? AND is_deleted = 0 LIMIT 1",
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
                data.optString("full_name")?.let { put("full_name", it) }
                data.optString("full_name_ar")?.let { put("full_name_ar", it) }
                data.optString("phone1")?.let { put("phone1", normalizePhone(it)) }
                data.optString("phone2")?.let { put("phone2", normalizePhone(it)) }
                data.optString("phone3")?.let { put("phone3", normalizePhone(it)) }
                data.optDouble("balance_deposit")?.let { put("balance_deposit", it) }
                if (data.has("allow_credit")) put("allow_credit", if (data.optBoolean("allow_credit")) 1 else 0)
                data.optDouble("credit_limit")?.let { put("credit_limit", it) }
                data.optDouble("current_balance")?.let { put("current_balance", it) }
                data.optString("notes")?.let { put("notes", it) }
                data.optString("details")?.let { put("details", it) }
                data.optString("status")?.let { put("status", it) }
                put("updated_at", getCurrentDateTime())
            }
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
                        return@try false
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
                    return@try Pair(false, "العميل غير موجود")
                }
                val allowCredit = cursor.getInt(0) == 1
                val creditLimit = cursor.getDouble(1)
                val currentBalance = cursor.getDouble(2)

                if (!allowCredit) {
                    return@try Pair(false, "العميل لا يسمح له بالشراء بالأجل")
                }
                if (currentBalance + amount > creditLimit) {
                    return@try Pair(false, "تجاوز الحد الائتماني المسموح به (${creditLimit.toInt()} ريال)")
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
                data.optString("service_code")?.let { put("service_code", it) }
                data.optString("service_name")?.let { put("service_name", it) }
                data.optString("service_name_ar")?.let { put("service_name_ar", it) }
                data.optString("service_type")?.let { put("service_type", it) }
                data.optDouble("price")?.let { put("price", it) }
                data.optString("activation_code")?.let { put("activation_code", it) }
                data.optString("activation_command")?.let { put("activation_command", it) }
                data.optString("provider")?.let { put("provider", it) }
                data.optString("description")?.let { put("description", it) }
                data.optString("notes")?.let { put("notes", it) }
                if (data.has("is_active")) put("is_active", if (data.optBoolean("is_active")) 1 else 0)
                data.optString("status")?.let { put("status", it) }
                put("updated_at", getCurrentDateTime())
            }
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
                "SELECT * FROM suppliers WHERE phone1 = ? OR phone2 = ? OR phone3 = ? AND is_deleted = 0 LIMIT 1",
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
                data.optString("name")?.let { put("name", it) }
                data.optString("name_ar")?.let { put("name_ar", it) }
                data.optString("phone1")?.let { put("phone1", normalizePhone(it)) }
                data.optString("phone2")?.let { put("phone2", normalizePhone(it)) }
                data.optString("phone3")?.let { put("phone3", normalizePhone(it)) }
                data.optDouble("our_balance")?.let { put("our_balance", it) }
                data.optDouble("their_balance")?.let { put("their_balance", it) }
                data.optString("status")?.let { put("status", it) }
                data.optString("details")?.let { put("details", it) }
                data.optString("notes")?.let { put("notes", it) }
                put("updated_at", getCurrentDateTime())
            }
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
                        return@try -1
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

    private fun normalizePhone(phone: String): String {
        if (phone.isBlank()) return ""
        return phone.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
            .replace("+", "")
            .let {
                when {
                    it.startsWith("00966") -> it.substring(5)
                    it.startsWith("966") -> it.substring(3)
                    it.startsWith("00") -> it.substring(2)
                    else -> it
                }
            }
            .let {
                if (it.length == 9 && !it.startsWith("966")) "966$it" else it
            }
    }

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