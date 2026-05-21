package tw.pp.kazi.util

import android.content.Context
import android.os.Build
import tw.pp.kazi.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把未捕捉的當機寫進一個檔案，下次開 App 先把它顯示出來。
 *
 * 為什麼要這條：電視盒上的閃退常常在「啟動讀進合法資料、拿去渲染」時才炸，
 * 光看 logcat 要接 adb 很麻煩。把堆疊存檔 + 下次開機顯示，使用者直接拍照就能回報。
 * LogBuffer 是純記憶體、重啟就沒，救不到啟動崩潰，所以另外寫一份到磁碟。
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"
    private val TIME_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /**
     * 在全域當機處理器裡同步寫檔。此時 process 即將被殺，必須當下寫完，
     * 且全程包 runCatching —— 當機處理器自己再丟例外只會更糟。
     */
    fun save(context: Context, thread: Thread, error: Throwable) {
        runCatching {
            val report = buildString {
                appendLine("========== 咔滋影院 崩潰報告 ==========")
                appendLine("時間：${TIME_FMT.format(Date())}")
                appendLine("App：${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                appendLine("Android：${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("裝置：${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("執行緒：${thread.name}")
                appendLine("例外：${error.javaClass.name}: ${error.message}")
                appendLine("==========================================")
                appendLine()
                append(error.stackTraceToString())
            }
            file(context).writeText(report)
        }
    }

    /** 讀上次的崩潰報告；沒有則回 null */
    fun read(context: Context): String? = runCatching {
        val f = file(context)
        if (f.exists() && f.length() > 0L) f.readText() else null
    }.getOrNull()

    /** 使用者看完按關閉後清掉，避免每次開機都跳 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
