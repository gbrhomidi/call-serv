# ═══════════════════════════════════════════════════════════════
#  نظام تفعيل خدمات الاتصالات - قواعد ProGuard/R8 (مُحسّنة وآمنة)
#  الإصدار 3.0 - متوافق مع Android 14
# ═══════════════════════════════════════════════════════════════
#
#  ⚠️ تحذير: هذا الملف يستخدم مع isMinifyEnabled=true
#  لا تستخدم قواعد عامة جداً (مثل ** { *; }) لأنها تبطل فائدة التعتيم
#
#  التحسينات:
#  1. قواعد أكثر تحديداً وصرامة
#  2. حماية الفئات الحساسة فقط
#  3. دعم EncryptedSharedPreferences و Biometric
#  4. دمج قواعد WorkManager و Coroutines
#  5. إزالة القواعد العامة الخطيرة
#  6. تعليقات توثيقية واضحة
# ═══════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════
#  القواعد العامة - General Rules
# ═══════════════════════════════════════════════════════════════

# الحفاظ على معلومات السطر (لتتبع الأخطاء)
-keepattributes SourceFile,LineNumberTable
# إعادة تسمية SourceFile إلى "SourceFile" لحماية المعلومات
-renamesourcefileattribute SourceFile

# الحفاظ على التعليقات التوضيحية
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ═══════════════════════════════════════════════════════════════
#  فئات التطبيق الأساسية - Application Classes
# ═══════════════════════════════════════════════════════════════

# الحفاظ على فئة Application
-keep class com.aistudio.dieselstationsms.kxmpzq.MyApplication { *; }

# الحفاظ على DatabaseHelper (Singleton)
-keep class com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper {
    public static com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper getInstance(android.content.Context);
    public *** getReadableDatabase();
    public *** getWritableDatabase();
}

# الحفاظ على فئات الـ Service
-keep class com.aistudio.dieselstationsms.kxmpzq.service.SMSService {
    public *;
    protected *;
}

# الحفاظ على فئات الـ Receiver
-keep class com.aistudio.dieselstationsms.kxmpzq.sms.SmsReceiver { *; }
-keep class com.aistudio.dieselstationsms.kxmpzq.receiver.** { *; }

# الحفاظ على فئات الـ Utils
-keep class com.aistudio.dieselstationsms.kxmpzq.utils.** { *; }

# الحفاظ على فئات الـ SMS Modules (المعالجات، الأمان، إلخ)
-keep class com.aistudio.dieselstationsms.kxmpzq.sms.** { *; }

# الحفاظ على فئات الـ Data Classes المستخدمة في التخزين المؤقت
-keep class com.aistudio.dieselstationsms.kxmpzq.data.** { *; }

# ═══════════════════════════════════════════════════════════════
#  WebView - JavaScript Interface (الأهم)
# ═══════════════════════════════════════════════════════════════

# الحفاظ على واجهة JavaScript (التي تستخدمها الصفحة الرئيسية)
-keepclassmembers class com.aistudio.dieselstationsms.kxmpzq.MainActivity$WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# الحفاظ على جميع أساليب WebAppInterface (حتى لو لم تحمل @JavascriptInterface)
-keepclassmembers class com.aistudio.dieselstationsms.kxmpzq.MainActivity$WebAppInterface {
    <methods>;
}

# ═══════════════════════════════════════════════════════════════
#  Security - EncryptedSharedPreferences & Biometric
# ═══════════════════════════════════════════════════════════════

# الحفاظ على فئات AndroidX Security
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# الحفاظ على Tink (مستخدم في Security Crypto)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# الحفاظ على Biometric
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# الحفاظ على BiometricPrompt Callback
-keepclassmembers class * {
    @androidx.biometric.BiometricPrompt.AuthenticationCallback <methods>;
}

# ═══════════════════════════════════════════════════════════════
#  Kotlin Coroutines
# ═══════════════════════════════════════════════════════════════

-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# الحفاظ على Coroutine Scopes
-keepclassmembers class kotlinx.coroutines.CoroutineScope {
    <methods>;
}

# ═══════════════════════════════════════════════════════════════
#  WorkManager - Background Tasks (للمهام الخلفية)
# ═══════════════════════════════════════════════════════════════

# الحفاظ على فئات Worker
-keep class * extends androidx.work.Worker {
    <init>(...);
}
-keep class * extends androidx.work.CoroutineWorker {
    <init>(...);
}

# الحفاظ على WorkManager نفسها
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# ═══════════════════════════════════════════════════════════════
#  AndroidX Core & Lifecycle
# ═══════════════════════════════════════════════════════════════

# الحفاظ على ViewModel
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
    <methods>;
}

# الحفاظ على LiveData و StateFlow
-keep class androidx.lifecycle.LiveData { *; }
-keep class androidx.lifecycle.MutableLiveData { *; }
-keep class kotlinx.coroutines.flow.StateFlow { *; }

# ═══════════════════════════════════════════════════════════════
#  SQLite & Database (لحماية استعلامات قاعدة البيانات)
# ═══════════════════════════════════════════════════════════════

-keep class android.database.sqlite.SQLiteDatabase { *; }
-keep class android.database.Cursor { *; }
-keep class android.database.sqlite.SQLiteOpenHelper { *; }

# الحفاظ على فئات ContentValues
-keep class android.content.ContentValues { *; }

# ═══════════════════════════════════════════════════════════════
#  JSON (Moshi/Gson/org.json)
# ═══════════════════════════════════════════════════════════════

# الحفاظ على Moshi إذا استُخدم
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# الحفاظ على org.json
-keep class org.json.** { *; }

# ═══════════════════════════════════════════════════════════════
#  إزالة التحذيرات - Suppress Warnings
# ═══════════════════════════════════════════════════════════════

-dontwarn android.**
-dontwarn com.google.**
-dontwarn org.jetbrains.**
-dontwarn sun.misc.**
-dontwarn okio.**
-dontwarn javax.**
-dontwarn org.apache.**

# ═══════════════════════════════════════════════════════════════
#  تحسين الأداء - Optimization
# ═══════════════════════════════════════════════════════════════

# إزالة التعليقات والتحقق من null
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(...);
    static void checkNotNullParameter(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNullExpressionValue(...);
    static void checkReturnedValueIsNotNull(...);
    static void checkFieldIsNotNull(...);
}

# ═══════════════════════════════════════════════════════════════
#  نهاية الملف
# ═══════════════════════════════════════════════════════════════