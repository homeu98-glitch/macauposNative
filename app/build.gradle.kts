import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

// anon key 由 local.properties 讀(不入版控;web bundle 已公開同一把 key)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.macaupos.nativeapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.macaupos.nativeapp"
        // P0 地基(2026-11-01):項目骨架 + 打印引擎移植 + Supabase client + 登入骨架。
        // minSdk 23 = Android 6.0(美團式舊收銀機 / D1 一體機)。
        // AGP 9.2.1 + Gradle 9.4.1 對 minSdk 23 已實測通過(見 macauPosAndroid 2026-11-01 分析)。
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "POS_URL", "\"https://macau-pos-system.vercel.app\"")
        // Supabase 直連(Macau-Ledger 專案,與 web 版同一個 DB)
        buildConfigField("String", "SUPABASE_URL", "\"https://zymdemjflsckicwcinxl.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProps.getProperty("supabase.anonKey") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Supabase PostgREST / Realtime / Vercel API 全走 OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR(設備綁定 / 掃碼;對齊 macauPosAndroid)
    implementation("com.google.zxing:core:3.5.3")

    // 廠商 ESC/POS SDK(與 macauPosAndroid 同一個 AAR,打印引擎已驗證)
    implementation(files("libs/printer-lib-3.5.3.aar"))
    // Sunmi 內置打印機 AIDL
    implementation("com.sunmi:printerlibrary:1.0.24")

    // Room(P1 offline-first 資料層;P0 先落地 sync_outbox 試金石,驗證 KSP+AGP9 內建 Kotlin)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
}
