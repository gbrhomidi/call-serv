// ═══════════════════════════════════════════════════════════════
//  نظام تفعيل خدمات الاتصالات - إعدادات Gradle
//  الإصدار 3.0 - Kotlin DSL
// ═══════════════════════════════════════════════════════════════
//
//  هذا الملف يحتوي على إعدادات المشروع الأساسية،
//  بما في ذلك إدارة المستودعات والإضافات (Plugins).
//
//  التحسينات:
//  1. استخدام Kotlin DSL (أكثر أماناً وقابلية للصيانة)
//  2. تنظيم المستودعات بشكل واضح
//  3. إضافة تعليقات توثيقية لكل قسم
//  4. إعدادات محسّنة لإدارة التبعيات
// ═══════════════════════════════════════════════════════════════

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    // وضع إدارة المستودعات - FAIL_ON_PROJECT_REPOS يمنع تعريف المستودعات في المشاريع الفرعية
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    
    repositories {
        google()
        mavenCentral()
        
        // مستودعات إضافية (يمكن إضافتها حسب الحاجة)
        // maven { url = uri("https://jitpack.io") }
        // maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    }
}

// اسم المشروع الرئيسي
rootProject.name = "TelecomServicesActivationSystem"

// تضمين وحدة التطبيق الرئيسية
include(":app")

// في حالة وجود وحدات إضافية في المستقبل، يمكن إضافتها هنا:
// include(":core")
// include(":data")
// include(":domain")
// include(":feature:auth")
// include(":feature:services")

// ═══════════════════════════════════════════════════════════════
//  نهاية الملف
// ═══════════════════════════════════════════════════════════════