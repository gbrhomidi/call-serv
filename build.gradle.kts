// ================================================================
// build.gradle.kts (Module :app)
// تطبيق نظام إدارة خدمات الاتصالات عبر الرسائل النصية (SMS)
// ================================================================

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("dagger.hilt.android.plugin")
    id("com.google.devtools.ksp") version "1.9.0-1.0.13"   // لـ Room
}

android {
    namespace = "com.aistudio.dieselstationsms.kxmpzq"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aistudio.dieselstationsms.kxmpzq"
        minSdk = 24
        targetSdk = 34
        versionCode = 300
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // استبدل ببيانات التوقيع الخاصة بك
            storeFile = file("keystore/release.keystore")
            storePassword = "your_store_password"
            keyAlias = "your_key_alias"
            keyPassword = "your_key_password"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-Xopt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/gradle/incremental.annotation.processors"
        }
    }
}

dependencies {
    // ===== Core Android =====
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.10.0")

    // ===== Compose =====
    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime-livedata")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ===== Navigation Compose =====
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // ===== Hilt =====
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // ===== Room (SQLite) =====
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ===== Coroutines & Flow =====
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // ===== WorkManager (للخلفية) =====
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ===== Security (EncryptedSharedPreferences) =====
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ===== Biometric =====
    implementation("androidx.biometric:biometric:1.1.0")

    // ===== JSON =====
    implementation("org.json:json:20231013")

    // ===== Retrofit (للاستدعاءات المستقبلية) =====
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ===== Gson =====
    implementation("com.google.code.gson:gson:2.10.1")

    // ===== Testing =====
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ===== إعدادات التوقيع للتطوير =====
// يمكنك تعيين بيانات التوقيع عبر متغيرات البيئة
if (project.hasProperty("signing.storeFile")) {
    android.signingConfigs.getByName("release").apply {
        storeFile = file(project.property("signing.storeFile") as String)
        storePassword = project.property("signing.storePassword") as String
        keyAlias = project.property("signing.keyAlias") as String
        keyPassword = project.property("signing.keyPassword") as String
    }
}

// ===== دعم Java 8+ =====
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

// ===== تصدير ملفات الأصول (Assets) =====
// سيتم نسخ ملفات HTML و CSS و JS من مجلد assets إلى APK
android.sourceSets {
    getByName("main").assets.srcDirs("src/main/assets")
}

// ===== إعدادات ProGuard (اختياري) =====
// أضف قواعد ProGuard في ملف proguard-rules.pro