# 咔滋影院 (kazi)

原生 Android 影片聚合播放器，後端串接 MacCMS 開放 API。一次搜多站、聚合同名來源、支援電視盒遙控與手機觸控雙界面。

> APK 自用，目前未發行 Play Store。要的話自己 build 一下。

## 主要功能

- **多站聚合搜尋**：同時打多個 MacCMS 站點，依片名聚合來源
- **支援排除詞**：例如 `慶餘年 -第二季 -預告`
- **繁簡轉換**：搜尋框旁邊一鍵轉簡體
- **影片播放**：Media3 ExoPlayer，HLS / DASH 支援，方向自動依影片比例旋轉
- **電視盒友善**：DPAD 控制、focus ring、RTC 三段播放速度
- **手機友善**：橫式自動全螢幕（藏狀態列）、左右滑進度、左右滑直亮度／音量、雙擊暫停、進度條可拖
- **觀看歷史**：自動記錄 resume position，回去點同一集會接續播放
- **我的收藏**：重點站台、重點片
- **遠端遙控**（LAN）：手機開瀏覽器掃 QR 即可在 TV 上送出搜尋／管站台
- **無痕模式**：開了之後不寫搜尋紀錄、不寫觀看歷史
- **狀態保留**：從詳情頁返回，搜尋結果與站台選擇都還在，不會重新打 API

## 專案結構

```
app/src/main/kotlin/tw/pp/kazi/
├── MainActivity.kt              入口
├── KaziApplication.kt           Application 持有 AppContainer
├── AppContainer.kt              共用單例：repos, scope, snapshots
├── data/                        domain models, API, 持久化
│   ├── MacCmsApi.kt             MacCMS API client
│   ├── SiteRepository.kt        站台 CRUD + 排序
│   ├── ConfigRepository.kt      設定（搜尋紀錄、view mode、LAN）
│   ├── HistoryRepository.kt     觀看歷史
│   ├── FavoriteRepository.kt    收藏
│   ├── SiteScanner.kt           批次健康檢查 / 掃站
│   └── Constants.kt             所有 magic value
├── lan/
│   └── LanServer.kt             NanoHTTPD HTTP 伺服器 + 內嵌 HTML 控制台
├── ui/
│   ├── KaziApp.kt               NavHost + CompositionLocal
│   ├── home/HomeScreen.kt
│   ├── search/SearchScreen.kt
│   ├── detail/DetailScreen.kt
│   ├── player/PlayerScreen.kt   ExoPlayer + 手勢 + DPAD
│   ├── setup/SetupScreen.kt     站點管理
│   ├── settings/SettingsScreen.kt
│   ├── lan/LanShareScreen.kt    遠端遙控啟用 + QR
│   ├── favorites/, history/, scan/, logs/
│   └── components/              AppButton, FocusableTag, PosterCard, ...
└── util/                        Logger, ChineseConverter (繁簡), Network, QrCode
```

## Build

```bash
./gradlew :app:assembleDebug
```

APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

公司網路擋 Gradle 下載？專案內已經 bundle 一份 Gradle zip（`gradle/*.zip`），首次 build 會自動用。

## Release（CI 自動發版）

打 `v*` 標籤就會觸發 GitHub Actions 自動 build APK 並掛到 GitHub Release：

```bash
git tag v0.2.0
git push origin v0.2.0
```

Workflow 會：

1. 把 `gradle-wrapper.properties` 從本機 `file://` 路徑改回公開 distribution URL
2. 用 tag 後面的數字蓋過 `versionName`（傳 `-PversionName=0.2.0`）
3. Build debug APK，重新命名為 `kazi-<version>.apk`
4. 建立 GitHub Release，附 APK 跟自動 changelog

也可以從 Actions 頁面手動觸發（不會建 release，只會留 artifact）。

### 環境

- compileSdk / targetSdk = 35（Android 15）
- minSdk = 21（Android 5.0）
- JVM 17
- Kotlin + Compose（手機）+ androidx.tv.material3（電視盒）

## 遠端遙控（LAN）使用方法

1. 進「設定」→「遠端遙控」→「啟用遠端遙控」
2. 手機跟 TV 連同個 WiFi
3. 手機掃畫面上的 QR（或手動輸入 `http://<TV-IP>:9090`）
4. 手機輸入關鍵字 → 送出 → TV 自動跳到搜尋結果
5. 也可以遠端管站台（新增 / 啟停 / 排序 / 刪除）
6. 不用了記得回去關，雖然閒置時開銷接近零

⚠ 沒有密碼保護，僅建議在自家 WiFi 用。

## 不要動的設定

- `gradle/*.zip` 是離線 Gradle 包（公司網路擋下載），`.gitignore` 已排除但檔案保留在本機
- `local.properties` 是 Android SDK path，每台機器不同，不進 git
