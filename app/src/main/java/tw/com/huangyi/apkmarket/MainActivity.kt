package tw.com.huangyi.apkmarket

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var loadingText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var marketVisible = false
    private var pageLoaded = false
    private var pendingInstallUri: Uri? = null

    private val downloadIds = mutableMapOf<Long, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMarket()
    }

    private fun showMarket() {
        marketVisible = true

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(0xFF101820.toInt())

        loadingText = TextView(this)
        loadingText.text = "正在開啟 APK 市場..."
        loadingText.textSize = 18f
        loadingText.setTextColor(0xFFFFFFFF.toInt())
        loadingText.setPadding(24, 20, 24, 20)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = true

        webView.addJavascriptInterface(AndroidMarketBridge(), "AndroidMarket")

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                loadingText.visibility = View.GONE
            }
        }

        layout.addView(loadingText)
        layout.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(layout)

        webView.loadUrl("https://dylan0728-web.github.io/Market/")
    }

    inner class AndroidMarketBridge {

        @JavascriptInterface
        fun scanApks(json: String) {
            executor.execute {
                try {
                    val list = JSONArray(json)

                    for (i in 0 until list.length()) {
                        val item = list.getJSONObject(i)
                        val url = item.optString("url")
                        val name = item.optString("name")

                        scanOneApk(url, name)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "APK 清單讀取失敗",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun startDownload(url: String, fileName: String) {
            runOnUiThread {
                downloadApk(url, fileName)
            }
        }

        @JavascriptInterface
        fun launchApp(packageName: String) {
            runOnUiThread {
                launchInstalledApp(packageName)
            }
        }
    }

    private fun scanOneApk(url: String, fileName: String) {
        var packageName = ""
        var appLabel = fileName
        var installed = false

        try {
            if (!url.startsWith("https://github.com/")) {
                throw Exception("不允許的下載網址")
            }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode !in 200..299) {
                throw Exception("下載失敗")
            }

            val tempFile = File(cacheDir, "scan_${System.currentTimeMillis()}.apk")

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            connection.disconnect()

            val packageInfo = packageManager.getPackageArchiveInfo(
                tempFile.absolutePath,
                PackageManager.GET_META_DATA
            )

            if (packageInfo != null) {
                packageName = packageInfo.packageName

                val appInfo = packageInfo.applicationInfo
                if (appInfo != null) {
                    appInfo.sourceDir = tempFile.absolutePath
                    appInfo.publicSourceDir = tempFile.absolutePath

                    appLabel = packageManager.getApplicationLabel(appInfo).toString()
                }

                installed = isPackageInstalled(packageName)
            }

            tempFile.delete()

        } catch (e: Exception) {
            android.util.Log.e("APK_SCAN", "掃描失敗：$fileName", e)
        }

        val resultUrl = JSONObject.quote(url)
        val resultPackage = JSONObject.quote(packageName)
        val resultLabel = JSONObject.quote(appLabel)

        val javascript = """
            window.onApkScanResult(
                $resultUrl,
                $resultPackage,
                $installed,
                $resultLabel
            );
        """.trimIndent()

        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(javascript, null)
            }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        if (packageName.isBlank()) return false

        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun launchInstalledApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Toast.makeText(this, "此軟體沒有可啟動的畫面", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "無法開啟此軟體", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadApk(url: String, fileName: String) {
        try {
            if (!url.startsWith("https://github.com/")) {
                Toast.makeText(this, "下載網址不正確", Toast.LENGTH_LONG).show()
                return
            }

            val request = DownloadManager.Request(Uri.parse(url))
            request.setTitle(fileName)
            request.setDescription("正在下載 APK")
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE
            )
            request.setDestinationInExternalFilesDir(
                this,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)

            downloadIds[id] = url

            Toast.makeText(this, "開始下載：$fileName", Toast.LENGTH_SHORT).show()

            checkDownloadProgress(id, url, fileName)

        } catch (e: Exception) {
            Toast.makeText(this, "下載啟動失敗：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkDownloadProgress(id: Long, url: String, fileName: String) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val task = object : Runnable {
            override fun run() {
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = manager.query(query)

                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )

                    val total = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )

                    val downloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )

                    when (status) {
                        DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PENDING -> {
                            val percent = if (total > 0) {
                                (downloaded * 100 / total).toInt()
                            } else {
                                0
                            }

                            sendProgress(url, percent, "downloading")
                            handler.postDelayed(this, 1000)
                        }

                        DownloadManager.STATUS_SUCCESSFUL -> {
                            cursor.close()
                            sendProgress(url, 100, "complete")

                            val uri = manager.getUriForDownloadedFile(id)
                            if (uri != null) {
                                openApkInstaller(uri)
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "找不到下載完成的 APK",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            return
                        }

                        DownloadManager.STATUS_FAILED -> {
                            cursor.close()
                            sendProgress(url, 0, "failed")
                            Toast.makeText(
                                this@MainActivity,
                                "下載失敗：$fileName",
                                Toast.LENGTH_LONG
                            ).show()
                            return
                        }
                    }
                }

                cursor?.close()
            }
        }

        handler.post(task)
    }

    private fun sendProgress(url: String, percent: Int, state: String) {
        val safeUrl = JSONObject.quote(url)
        val safeState = JSONObject.quote(state)

        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.onApkDownloadProgress($safeUrl,$percent,$safeState);",
                    null
                )
            }
        }
    }

    private fun openApkInstaller(uri: Uri) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallUri = uri

            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )

            startActivity(intent)
            return
        }

        installApk(uri)
    }

    private fun installApk(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(
                uri,
                "application/vnd.android.package-archive"
            )
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "無法開啟安裝畫面：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()

        val uri = pendingInstallUri
        if (uri != null && packageManager.canRequestPackageInstalls()) {
            pendingInstallUri = null
            installApk(uri)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (::webView.isInitialized && webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroy()
    }
}
