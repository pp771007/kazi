package tw.pp.kazi

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import tw.pp.kazi.ui.KaziApp
import tw.pp.kazi.ui.KeyEventGuardLayout

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KaziApplication).container

        fun applySecureFlag(on: Boolean) {
            if (on) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        // 無痕開啟時掛 FLAG_SECURE：最近清單縮圖變黑、擋截圖/螢幕錄影/投影。
        // 同步先套一次當前值，避免「Activity 已可見但 flow collector 還沒拉到值」中間短暫沒 flag 的空窗
        applySecureFlag(container.incognito.value)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.incognito.collect(::applySecureFlag)
            }
        }

        // 不用 setContent{}：ComposeView 要包在 KeyEventGuardLayout 裡，才攔得到 preIme 那條路的
        // 焦點按鍵例外（見該類別註解）。
        val composeView = ComposeView(this).apply {
            setContent { KaziApp(container) }
        }
        setContentView(KeyEventGuardLayout(this).apply { addView(composeView) })
    }
}
