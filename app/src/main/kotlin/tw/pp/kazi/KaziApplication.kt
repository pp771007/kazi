package tw.pp.kazi

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tw.pp.kazi.data.HttpClients
import tw.pp.kazi.util.CrashLog
import tw.pp.kazi.util.Logger

class KaziApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        installUncaughtExceptionHandler()
        container = AppContainer(this)
        container.observeForegroundSync()
        scope.launch { container.bootstrap() }
    }

    // 海報圖也走 App 的 DNS 設定
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient { HttpClients.images }.build()

    override fun onTerminate() {
        // 註：實機上幾乎不會被呼叫（Android 直接殺 process），
        // 但模擬器會呼叫；留著確保乾淨收尾
        scope.cancel()
        container.stopLan()
        super.onTerminate()
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Logger.e("UNCAUGHT on ${thread.name}: ${error.javaClass.simpleName}", error)
                // 寫進磁碟，下次開 App 會顯示出來（LogBuffer 是純記憶體，救不到啟動崩潰）
                CrashLog.save(applicationContext, thread, error)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
