// macauposNative — 全原生 Android POS(離線優先,直連 Supabase)
// 工具鏈對齊 macauPosAndroid(AGP 9.2.1 + Gradle 9.4.1 + JDK 17),已實測可編。
plugins {
    id("com.android.application") version "9.2.1" apply false
    // Room 需要 KSP;KSP 版本對齊 AGP 9 內建 Kotlin(2.2.10)
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
