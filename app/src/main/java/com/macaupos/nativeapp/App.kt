package com.macaupos.nativeapp

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局兜底:任何 launch 路徑掟出嚟嘅 exception 都記低完整 stack,
 * 等 `adb logcat -s MACAUPOS_CRASH` 排查;同時寫去 app 外部檔 `crash.txt`。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("MACAUPOS_CRASH", "Uncaught exception in thread ${t.name}", e)
            CrashLog.write(this, "MACAUPOS_CRASH/${t.name}", e)
            def?.uncaughtException(t, e)
        }
    }
}

object CrashLog {
    fun write(context: android.content.Context, tag: String, e: Throwable) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "crash.txt")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("=== $tag @ $ts (SDK ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL}) ===\n")
            file.appendText(Log.getStackTraceString(e) + "\n\n")
        } catch (ignored: Throwable) {
            // 寫唔到就當冇,logcat 仲有
        }
    }
}
