package tw.pp.kazi.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 產生 Baseline Profile:把「冷啟動 → 首頁 → 用方向鍵在頂列/站點列移動焦點 → 進別的畫面」
 * 這條最常走的路跑一遍,ART 會記錄熱路徑、預先 AOT 編譯,讓弱電視盒首次操作不必邊跑邊 JIT。
 *
 * 跑法:`./gradlew :app:generateBaselineProfile`(需連著 API 28+ 裝置,例如 kazitv 模擬器)。
 * 產物會收進 app/src/release 的 generated baseline profile,release build 自動打包。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "tw.pp.kazi",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // 在首頁四處移動焦點:把頂列(搜尋/歷史/…/設定)、站點列、焦點處理的 Compose 程式碼都跑過
        repeat(2) {
            repeat(6) { device.pressDPadRight(); device.waitForIdle() }
            repeat(6) { device.pressDPadLeft(); device.waitForIdle() }
            device.pressDPadUp(); device.waitForIdle()
            device.pressDPadDown(); device.waitForIdle()
        }

        // 進一個子畫面再返回(走一次 NavHost / ScreenScaffold 的組合路徑)
        device.pressDPadUp(); device.waitForIdle()
        device.pressDPadCenter(); device.waitForIdle()
        device.pressBack(); device.waitForIdle()
    }
}
