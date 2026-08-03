package tw.pp.kazi.ui

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout
import tw.pp.kazi.util.Logger

/**
 * 包在 ComposeView 外面的一層，專吃 Compose 焦點系統的
 * 「IllegalStateException: Dispatching … while focus system is invalidated.」
 *
 * 為什麼需要：Compose 1.7.x 的 FocusOwnerImpl 收到按鍵時，若焦點重算（invalidation）還沒跑完，
 * 前置檢查直接丟例外 → 整個 App 閃退。堆疊裡沒有任何 app 程式碼，屬於 Compose 自身的時序問題，
 * 從 composable 那端避不掉；慢的電視盒（實測 XGIMI、Android 8）在畫面剛換又連按方向鍵時很容易踩到。
 * 例外是在方法第一行的前置檢查丟的、還沒動到任何狀態 → 吞掉只是「這一次按鍵沒作用」，
 * 不會留下壞掉的焦點狀態。
 *
 * 為什麼要包成 View：preIme 那條路（ViewRootImpl → DecorView → 各層 ViewGroup）不經過
 * Activity.dispatchKeyEvent，只有插一層 ViewGroup 攔得到；順手把一般那條路也包起來
 * （同一個檔案裡三道一樣的檢查，按鍵那道也會炸）。
 *
 * ⚠ 只認這一種訊息，其他 IllegalStateException 照樣往上丟 —— 這層不是萬用 try/catch，
 * 拿來蓋住真的 bug 會讓問題更難查。
 */
class KeyEventGuardLayout(context: Context) : FrameLayout(context) {

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
        try {
            super.dispatchKeyEventPreIme(event)
        } catch (e: IllegalStateException) {
            skipOrRethrow(e, event)
        }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        try {
            super.dispatchKeyEvent(event)
        } catch (e: IllegalStateException) {
            skipOrRethrow(e, event)
        }

    private fun skipOrRethrow(e: IllegalStateException, event: KeyEvent): Boolean {
        if (e.message?.contains(FOCUS_INVALIDATED) != true) throw e
        Logger.w("焦點系統重算中，略過這次按鍵 keyCode=${event.keyCode}（${e.message}）")
        return false
    }

    private companion object {
        const val FOCUS_INVALIDATED = "focus system is invalidated"
    }
}
