package tw.com.huangyi.apkmarket

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var loadingText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pendingInstallUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMarket()
    }

    private fun showMarket() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(0xFF101820.toInt())

        loadingText = TextView(this).apply {
            text = "正在開啟應用市場..."
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 20, 24, 20)
        }

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false

        webView.addJavascriptInterface(
            AndroidMarketBridge(),
            "AndroidMarket"
        )

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
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
        fun getDeviceMac(): String {
            return readHardwareMac()
        }

        @JavascriptInterface
        fun checkInstalled(packageName: String, url: String) {
            runOnUiThread {
                val installed = isPackageInstalled(packageName)

                val safeUrl = JSONObject.quote(url)
                val safePackage = JSONObject.quote(packageName)
                val safeLabel = JSONObject.quote(packageName)

                webView.evaluateJavascript(
                    "window.onAppCheckResult(" +
                        "$safeUrl,$installed,$safeLabel,\"\",$safePackage);",
                    null
                )
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

        @JavascriptInterface
        fun uninstallApp(packageName: String) {
            runOnUiThread {
                uninstallInstalledApp(packageName)
            }
        }
    }

    // 嘗試讀取設備真正的網路介面 MAC
    private fun readHardwareMac(): String {
        val interfaces = listOf("eth0", "wlan0", "en0")

        for (name in interfaces) {
            try {
                val networkInterface =
                    NetworkInterface.getByName(name) ?: continue

                val mac = networkInterface.hardwareAddress ?: continue

                val result = mac.joinToString("") {
                    "%02X".format(it.toInt() and 0xFF)
                }

                if (result.length == 12 &&
                    result != "000000000000" &&
                    result != "020000000000"
                ) {
                    return result
                }
            } catch (e: Exception) {
                // 繼續嘗試其他網路介面
            }
        }

        return ""
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
                startActivity(intent)
            } else {
                Toast.makeText(
                    this,
                    "此軟體沒有可啟動的畫面",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "無法開啟此軟體",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun uninstallInstalledApp(packageName: String) {
        if (packageName.isBlank()) return

        try {
            val intent = Intent(
                Intent.ACTION_DELETE,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "無法開啟卸載畫面",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun downloadApk(url: String, fileName: String) {
        try {
            if (!url.startsWith(
                    "https://github.com/dylan0728-web/Market/releases/download/"
                )
            ) {
                Toast.makeText(
                    this,
                    "下載網址不正確",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val safeName = File(fileName).name

            val request = DownloadManager.Request(Uri.parse(url))
            request.setTitle(safeName)
            request.setDescription("正在下載 APK")
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE
            )

            request.setDestinationInExternalFilesDir(
                this,
                Environment.DIRECTORY_DOWNLOADS,
                safeName
            )

            val manager = getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager

            val id = manager.enqueue(request)

            Toast.makeText(
                this,
                "開始下載：$safeName",
                Toast.LENGTH_SHORT
            ).show()

            checkDownloadProgress(id, url, safeName)

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "下載啟動失敗：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkDownloadProgress(
        id: Long,
        url: String,
        fileName: String
    ) {
        val manager = getSystemService(
            Context.DOWNLOAD_SERVICE
        ) as DownloadManager

        val task = object : Runnable {
            override fun run() {
                val cursor = manager.query(
                    DownloadManager.Query().setFilterById(id)
                ) ?: return

                cursor.use {
                    if (!it.moveToFirst()) return

                    val status = it.getInt(
                        it.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_STATUS
                        )
                    )

                    val total = it.getLong(
                        it.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                        )
                    )

                    val downloaded = it.getLong(
                        it.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                        )
                    )

                    when (status) {
                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_RUNNING -> {
                            val percent = if (total > 0) {
                                (downloaded * 100 / total).toInt()
                            } else {
                                0
                            }

                            sendProgress(url, percent, "downloading")
                            handler.postDelayed(this, 1000)
                        }

                        DownloadManager.STATUS_SUCCESSFUL -> {
                            sendProgress(url, 100, "complete")

                            val uri = manager.getUriForDownloadedFile(id)

                            if (uri != null) {
                                openApkInstaller(uri)
                            }
                        }

                        DownloadManager.STATUS_FAILED -> {
                            sendProgress(url, 0, "failed")

                            Toast.makeText(
                                this@MainActivity,
                                "下載失敗：$fileName",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        handler.post(task)
    }

    private fun sendProgress(
        url: String,
        percent: Int,
        state: String
    ) {
        val safeUrl = JSONObject.quote(url)
        val safeState = JSONObject.quote(state)

        runOnUiThread {
            webView.evaluateJavascript(
                "window.onApkDownloadProgress(" +
                    "$safeUrl,$percent,$safeState);",
                null
            )
        }
    }

    private fun openApkInstaller(uri: Uri) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallUri = uri

            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
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
            Toast.makeText(
                this,
                "無法開啟安裝畫面：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()

        val uri = pendingInstallUri

        if (
            uri != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallUri = null
            installApk(uri)
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
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

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroy()
    }
}
