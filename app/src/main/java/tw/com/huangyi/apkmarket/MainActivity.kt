
package tw.com.huangyi.apkmarket

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()

    private var currentDownloadId: Long = -1
    private var currentDownloadUrl = ""
    private var pendingInstallUri: Uri? = null
    private var checkingDownload = false
    private var marketVisible = false
    private var pageLoaded = false

    private val downloadManager by lazy {
        getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    private val apkMime = "application/vnd.android.package-archive"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMarket()
    }

    private fun makeRoot() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(18, 28, 38))
        root.gravity = Gravity.CENTER
        root.setPadding(12, 8, 12, 8)
        setContentView(root)
    }

    private fun showMarket() {
        marketVisible = true
        pageLoaded = false
        makeRoot()
        root.gravity = Gravity.TOP

        statusText = TextView(this)
        statusText.text = "正在開啟 APK 市場..."
        statusText.textSize = 15f
        statusText.setTextColor(Color.WHITE)
        root.addView(statusText)

        progressBar = ProgressBar(
            this, null,
            android.R.attr.progressBarStyleHorizontal
        )
        progressBar.max = 100
        progressBar.visibility = View.GONE

        root.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 8
        ))

        webView = WebView(this)
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false

        webView.addJavascriptInterface(
            AndroidMarketBridge(), "AndroidMarket"
        )

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                statusText.text = "APK 市場已載入，準備掃描"
            }
        }

        webView.webChromeClient = WebChromeClient()

        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        webView.loadUrl("https://dylan0728-web.github.io/Market/")
    }

    inner class AndroidMarketBridge {

        @JavascriptInterface
        fun startDownload(url: String, fileName: String) {
            runOnUiThread {
                beginDownload(url, fileName)
            }
        }

        @JavascriptInterface
        fun scanApks(json: String) {
            scanExecutor.execute {
                scanApkList(json)
            }
        }

        @JavascriptInterface
        fun launchApp(packageName: String) {
            runOnUiThread {
                launchInstalledApp(packageName)
            }
        }
    }

    private fun scanApkList(json: String) {
        try {
            val list = JSONArray(json)
            val total = list.length()

            for (i in 0 until total) {
                val item = list.getJSONObject(i)
                val url = item.optString("url")
                val fileName = item.optString("name")

                if (!url.startsWith("https://github.com/") ||
                    !fileName.lowercase().endsWith(".apk")) {
                    sendScanResult(url, "", false, "無法辨識")
                    continue
                }

                runOnUiThread {
                    statusText.text = "正在掃描 APK：${i + 1}/$total"
                }

                var packageName = ""
                var appLabel = fileName
                var installed = false

                val tempFile = File(
                    cacheDir,
                    "scan_${System.currentTimeMillis()}_$i.apk"
                )

                try {
                    downloadForScan(url, tempFile)

                    val packageInfo = packageManager.getPackageArchiveInfo(
                        tempFile.absolutePath,
                        PackageManager.GET_ACTIVITIES
                    )

                    if (packageInfo != null) {
                        packageName = packageInfo.packageName

                        val appInfo = packageInfo.applicationInfo
                        if (appInfo != null) {
                            appInfo.sourceDir = tempFile.absolutePath
                            appInfo.publicSourceDir = tempFile.absolutePath
                            appLabel = packageManager
                                .getApplicationLabel(appInfo).toString()
                        }

                        installed = isAppInstalled(packageName)
                    }
                } catch (_: Exception) {
                    // 單一 APK 掃描失敗，不影響其他 APK
                } finally {
                    tempFile.delete()
                }

                sendScanResult(
                    url,
                    packageName,
                    installed,
                    appLabel
                )
            }

            runOnUiThread {
                statusText.text = "APK 掃描完成，共 $total 個"
            }

        } catch (e: Exception) {
            runOnUiThread {
                statusText.text = "APK 掃描失敗：" + (e.message ?: "")
            }
        }
    }

    private fun downloadForScan(url: String, file: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "HuangYi-ApkMarket")

        try {
            connection.connect()

            if (connection.responseCode !in 200..299) {
                throw Exception("HTTP ${connection.responseCode}")
            }

            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        if (packageName.isBlank()) return false

        return try {
            val launchIntent =
                packageManager.getLaunchIntentForPackage(packageName)
                    ?: packageManager.getLeanbackLaunchIntentForPackage(packageName)

            launchIntent != null
        } catch (_: Exception) {
            false
        }
    }

    private fun sendScanResult(
        url: String,
        packageName: String,
        installed: Boolean,
        appLabel: String
    ) {
        val safeUrl = JSONObject.quote(url)
        val safePackage = JSONObject.quote(packageName)
        val safeLabel = JSONObject.quote(appLabel)

        runOnUiThread {
            if (!::webView.isInitialized) return@runOnUiThread

            webView.evaluateJavascript(
                "window.onApkScanResult && " +
                    "window.onApkScanResult(" +
                    "$safeUrl,$safePackage,$installed,$safeLabel);",
                null
            )
        }
    }

    private fun launchInstalledApp(packageName: String) {
        try {
            val intent =
                packageManager.getLaunchIntentForPackage(packageName)
                    ?: packageManager.getLeanbackLaunchIntentForPackage(packageName)

            if (intent == null) {
                Toast.makeText(
                    this, "找不到此軟體的啟動畫面",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "無法開啟軟體：" + (e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun beginDownload(url: String, fileName: String) {
        try {
            if (!url.startsWith("https://github.com/")) {
                Toast.makeText(
                    this, "下載網址不安全", Toast.LENGTH_LONG
                ).show()
                return
            }

            if (currentDownloadId != -1L) {
                Toast.makeText(
                    this, "目前已有下載正在進行", Toast.LENGTH_SHORT
                ).show()
                return
            }

            currentDownloadUrl = url

            val request = DownloadManager.Request(Uri.parse(url))
            request.setTitle(fileName)
            request.setDescription("正在下載 APK")
            request.setMimeType(apkMime)
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            request.setDestinationInExternalFilesDir(
                this, null, fileName.replace("/", "_")
            )

            currentDownloadId = downloadManager.enqueue(request)

            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            statusText.text = "正在下載：0%"

            Toast.makeText(
                this, "開始下載 $fileName", Toast.LENGTH_SHORT
            ).show()

            checkDownloadProgress()

        } catch (e: Exception) {
            Toast.makeText(
                this, "下載失敗：" + (e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkDownloadProgress() {
        if (checkingDownload || currentDownloadId == -1L) return
        checkingDownload = true

        handler.postDelayed(object : Runnable {
            override fun run() {
                val id = currentDownloadId

                if (id == -1L) {
                    checkingDownload = false
                    return
                }

                try {
                    val query = DownloadManager.Query().setFilterById(id)

                    downloadManager.query(query)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(
                                DownloadManager.COLUMN_STATUS
                            )
                            val downloadedIndex = cursor.getColumnIndex(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                            )
                            val totalIndex = cursor.getColumnIndex(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                            )

                            val status = cursor.getInt(statusIndex)
                            val downloaded = cursor.getLong(downloadedIndex)
                            val total = cursor.getLong(totalIndex)

                            if (total > 0) {
                                val percent = ((downloaded * 100) / total)
                                    .toInt().coerceIn(0, 100)

                                progressBar.progress = percent
                                statusText.text = "下載中：$percent%"

                                sendProgress(
                                    currentDownloadUrl,
                                    percent,
                                    "downloading"
                                )
                            }

                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                progressBar.progress = 100
                                sendProgress(currentDownloadUrl, 100, "complete")

                                val uri =
                                    downloadManager.getUriForDownloadedFile(id)

                                currentDownloadId = -1L
                                checkingDownload = false

                                if (uri != null) {
                                    openApkInstaller(uri)
                                } else {
                                    statusText.text = "下載完成，但無法取得檔案"
                                }
                                return
                            }

                            if (status == DownloadManager.STATUS_FAILED) {
                                currentDownloadId = -1L
                                checkingDownload = false

                                sendProgress(currentDownloadUrl, 0, "failed")
                                statusText.text = "下載失敗"

                                Toast.makeText(
                                    this@MainActivity,
                                    "APK 下載失敗",
                                    Toast.LENGTH_LONG
                                ).show()
                                return
                            }
                        }
                    }
                } catch (_: Exception) {
                    currentDownloadId = -1L
                    checkingDownload = false
                    statusText.text = "讀取下載進度失敗"
                    return
                }

                handler.postDelayed(this, 1000)
            }
        }, 500)
    }

    private fun sendProgress(
        url: String,
        percent: Int,
        state: String
    ) {
        if (!::webView.isInitialized) return

        val safeUrl = JSONObject.quote(url)
        val safeState = JSONObject.quote(state)

        webView.post {
            webView.evaluateJavascript(
                "window.onApkDownloadProgress && " +
                    "window.onApkDownloadProgress(" +
                    "$safeUrl,$percent,$safeState);",
                null
            )
        }
    }

    private fun openApkInstaller(uri: Uri) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 &&
                !packageManager.canRequestPackageInstalls()) {

                pendingInstallUri = uri

                Toast.makeText(
                    this,
                    "請允許此應用程式安裝未知應用程式",
                    Toast.LENGTH_LONG
                ).show()

                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return
            }

            pendingInstallUri = null

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, apkMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(intent)
            statusText.text = "請在系統畫面確認安裝"

        } catch (e: Exception) {
            pendingInstallUri = null
            Toast.makeText(
                this,
                "無法開啟安裝畫面：" + (e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()

        val uri = pendingInstallUri
        if (uri != null) {
            if (android.os.Build.VERSION.SDK_INT >= 26 &&
                !packageManager.canRequestPackageInstalls()) {
                return
            }

            pendingInstallUri = null
            handler.postDelayed({
                openApkInstaller(uri)
            }, 500)
            return
        }

        // 從安裝畫面返回市場時，重新掃描已安裝狀態
        if (marketVisible && pageLoaded && ::webView.isInitialized) {
            handler.postDelayed({
                if (marketVisible && ::webView.isInitialized) {
                    webView.evaluateJavascript(
                        "if(window.loadApps){loadApps();}", null
                    )
                }
            }, 1200)
        }
    }

    override fun onBackPressed() {
        // 市場首頁按返回直接退出，不再回到登入畫面
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        marketVisible = false
        handler.removeCallbacksAndMessages(null)
        scanExecutor.shutdownNow()

        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidMarket")
            webView.destroy()
        }

        super.onDestroy()
    }
}
