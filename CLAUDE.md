# kazi 專案守則

Android(手機 + 電視盒)MacCMS 影片 app,Kotlin + Jetpack Compose。手機 / 電視用 `WindowSize`(Compact/Medium/Expanded)區分,Expanded = 電視盒。

## ⚠️ 電視盒焦點(D-pad / TV focus)鐵則

這是這個專案最容易亂改到收不了場的地方。歷史上踩過兩類雷:
1. **「FocusRequester is not initialized」閃退** —— `focusProperties { down/up = someRequester }` 指到的 requester,當下沒掛在任何 composable 上(LazyRow 把它虛擬化掉、或還沒 compose),D-pad 一移動 Compose 解析落點就 crash。
2. **跳過中間列** —— 手動指定的落點配上一堆「可見了沒/掛上了沒」守衛,守衛判斷錯就 fall through,站點↓直接跳到影片格、跳過分類。

### 守則
- **不要手動用 `focusProperties { down=/up= }` 在「列與列之間」硬指 FocusRequester 落點。** 這是上面兩類 bug 的根源。
- **每一個可聚焦的橫向列(top bar / 站點 / 分類 …)= 一個 `Modifier.focusGroup()` + `Modifier.focusRestorer { 選中項可見 ? selectedFocus : FocusRequester.Default }`。** 列與列之間的上下移動,交給框架的空間搜尋自然處理 —— 實機驗證過:上下來回全對、從最右按鈕↓也會正確進到該列的選中項、記住上次位置、選中項被捲出畫面也不閃退。
- focusRestorer 的 fallback 一定要是「選中項真的 visible 才回 selectedFocus,否則回 `FocusRequester.Default`」。直接無條件回 selectedFocus 會在虛擬化時 crash。
- 橫向循環(first 按←跳 last)用 `onPreviewKeyEvent` + `scope.launch { listState.scrollToItem(...); runCatching { target.requestFocus() } }`(先捲到對端確保 compose,再 focus,且包 runCatching)。
- **動到焦點 / header / 版面結構前,先在模擬器實測一輪再 commit。** v0.8.0 因為把站點/分類列搬進可收合 header + 在 ScreenScaffold 包一層,動到頂列焦點結構 → 電視盒按鍵閃退,整個退回(v0.8.1)。
- **不要把「裡面有按鈕(AppButton)的 Row」包成 `focusGroup()` + `focusRestorer`(尤其是分頁 Pager)。** 這會讓按鈕看起來有 focus(白框在),但按 OK / DPAD_CENTER 點不動 —— group 把點擊事件吃掉了。「下一頁按了沒反應」這個 bug 重複發生過至少 3 次,每次都是這個原因。需要「進入某列預設停在某顆」時:LazyRow 的 chip 列用 focusRestorer 沒問題(實測可點);但按鈕 Row 不要用 focusGroup,改用其他不吃點擊的方式(例如靠版面幾何讓空間搜尋自然落點,或只在該顆掛 focusRequester 不包 group)。改完一定要在模擬器上「真的按一下 OK 確認會動作」,不能只看 focus 框在不在。

## 在模擬器上測電視盒焦點(可自驗,不必每次丟給使用者)

環境已備好:Android SDK 有 `cmdline-tools`,系統映像 `system-images;android-36;android-tv;x86_64`,AVD 名 `kazitv`(WHPX 加速可用)。`JAVA_HOME` 用 `C:\Program Files\Android\Android Studio\jbr`。

流程:
1. 開機(headless):`emulator -avd kazitv -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot`
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. **灌測試站點**:app 把站台存在 `filesDir/data/sites.json`(完整 `Site` schema,要 id/order)。用 `adb push` 到 `/data/local/tmp/`,再 `adb shell "run-as tw.pp.kazi sh -c 'cat > files/data/sites.json' < /data/local/tmp/sites.json"`,然後 force-stop + 重開。模擬器上站憑證鏈常驗不過 → 測試用把 `ssl_verify` 設 `false`(app 的 `forSite(false)` 走 trust-all)。
4. 用 `am start -n tw.pp.kazi/.MainActivity` 啟動(不要用 monkey,會進 touch mode)。
5. 模擬遙控器:`adb shell input keyevent` 19=↑ 20=↓ 21=← 22=→ 23=OK。
6. **看焦點在哪一定要用截圖**(`adb shell screencap -p /sdcard/s.png` + `adb pull`),看畫面上的白色 focus 邊框。**`uiautomator dump` 對 Compose 會誤報 focused 節點,不可信。**
7. Windows git-bash 對 adb 的裝置路徑會做 MSYS 路徑轉換 → 設 `MSYS_NO_PATHCONV=1`。

## 發版

commit → annotated tag `vX.Y.Z`(訊息「vX.Y.Z 說明」)→ GitHub Actions「Release APK」自動 build,versionName 由 tag 帶入,產物發到 GitHub Releases。新功能跳中版號、修 bug 跳小版號。
