package tw.pp.kazi

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tw.pp.kazi.util.Logger

class KaziApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        installUncaughtExceptionHandler()
        container = AppContainer(this)
        scope.launch { container.bootstrap() }
    }

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
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
