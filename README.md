# 皇奕科技 APK 市場（測試版原始碼）

## 本版功能
- Android 登入頁面（本機測試帳密）
- 登入後在 App 內開啟指定 MEGA 公開資料夾
- 可使用 MEGA 網頁本身的檔案清單及下載介面
- 基本重新整理、登出、返回功能
- 目標包含 Android TV / 電視盒啟動器類別
- **尚未加入機身序號、開始日、到期日授權驗證**

## 測試登入
- 帳號：`demo`
- 密碼：`123456`

這只是方便第一階段測試的本機登入，不具備真正的安全性，不能用來控制付費客戶權限。

## MEGA 來源
`https://mega.nz/folder/R5cSWBSB#Sdi5JrEnsWi4KunYwnJ5GQ`

## 建置方式
1. 在電腦安裝 Android Studio（建議支援 Android Gradle Plugin 8.7.3 的版本）。
2. 解壓縮此 ZIP，使用 Android Studio 開啟 `HuangYiApkMarket` 資料夾。
3. 讓 Android Studio 下載 Gradle / Android SDK 相依套件。
4. 連接 Android TV 盒或選擇測試裝置，按 Run 測試。
5. 要輸出 APK：Build → Build APK(s)。

## 已知限制
- 此執行環境沒有 Android SDK/Gradle，故目前交付的是可匯入 Android Studio 的專案原始碼 ZIP，並非已編譯 APK。
- MEGA 網頁可能因裝置 WebView 版本、遙控器操作或 MEGA 網站更新而無法完整顯示；請先在目標 S905Y4 裝置實測。
- MEGA 使用部分加密/Blob 下載流程時，不一定會觸發 Android 原生 DownloadManager；這種情況請使用 MEGA 頁面內的下載按鈕，若仍失敗，下一版需改用 MEGA API/可直接下載的檔案索引方式。
- 請只散布你有權提供的 APK，並檢查來源檔案安全性。
