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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var loadingText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pendingInstallUri: Uri? = null
    private var pendingInstallCheck = false
    private val activeDownloads = mutableMapOf<String, Long>()

    private val prefs by lazy {
        getSharedPreferences("apk_market_packages", Context.MODE_PRIVATE)
    }

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

        webView.addJavascriptInterface(AndroidMarketBridge(), "AndroidMarket")
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
        fun getDeviceMac(): String = readHardwareMac()

        @JavascriptInterface
        fun inspectApks(jsonText: String) {
            try {
                val input = JSONArray(jsonText)
                val installedPackages = packageManager.getInstalledPackages(0)
                val output = JSONArray()

                for (i in 0 until input.length()) {
                    val item = input.optJSONObject(i) ?: continue

                    val url = item.optString("url", "")
                    val fileName = item.optString("fileName", "")
                    val appName = item.optString("appName", "")

                    var packageName = prefs.getString(
                        "package_$fileName",
                        ""
                    ) ?: ""

                    var installed = false
                    var installedLabel = appName
                    var installedVersion = item.optString("versionName", "")

                    if (packageName.isNotBlank()) {
                        try {
                            val info = packageManager.getPackageInfo(packageName, 0)
                            installed = true
                            installedVersion = info.versionName ?: installedVersion

                            installedLabel = try {
                                packageManager.getApplicationLabel(
                                    info.applicationInfo!!
                                ).toString()
                            } catch (e: Exception) {
                                appName
                            }
                        } catch (e: Exception) {
                            installed = false
                        }
                    }

                    if (!installed) {
                        val candidates = listOf(
                            appName,
                            fileName.substringBeforeLast(".")
                        ).map { normalizeName(it) }
                            .filter { it.isNotBlank() }

                        for (info in installedPackages) {
                            val label = try {
                                packageManager.getApplicationLabel(
                                    info.applicationInfo!!
                                ).toString()
                            } catch (e: Exception) {
                                ""
                            }

                            val normalizedLabel = normalizeName(label)

                            if (normalizedLabel.isNotBlank() &&
                                candidates.contains(normalizedLabel)
                            ) {
                                packageName = info.packageName
                                installed = true
                                installedLabel = label
                                installedVersion = info.versionName ?: installedVersion

                                prefs.edit()
                                    .putString("package_$fileName", packageName)
                                    .apply()
                                break
                            }
                        }
                    }

                    val result = JSONObject()
                    result.put("url", url)
                    result.put("appName", installedLabel)
                    result.put("packageName", if (installed) packageName else "")
                    result.put("versionName", installedVersion)
                    result.put("installed", installed)

                    output.put(result)
                }

                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onApkMetadata && window.onApkMetadata($output);",
                        null
                    )
                }

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "讀取安裝資訊失敗：${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun checkInstalled(packageName: String, url: String) {
            runOnUiThread {
                val installed = isPackageInstalled(packageName)
                val safeUrl = JSONObject.quote(url)
                val safePackage = JSONObject.quote(packageName)

                webView.evaluateJavascript(
                    "window.onAppCheckResult && " +
                        "window.onAppCheckResult($safeUrl,$installed,$safePackage);",
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

    private fun normalizeName(value: String): String {
        return value.lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
    }

    private fun normalizeMacAddress(value: String): String {
        val mac = value.trim()
            .replace(":", "")
            .replace("-", "")
            .replace(".", "")
            .uppercase(Locale.ROOT)

        if (!mac.matches(Regex("[0-9A-F]{12}"))) return ""
        if (mac == "000000000000") return ""
        if (mac == "020000000000") return ""

        return mac
    }

    private fun readHardwareMac(): String {
        val interfaces = listOf("eth0", "wlan0", "wlan1", "en0")

        for (name in interfaces) {
            try {
                val addressFile = File("/sys/class/net/$name/address")

                if (addressFile.exists() && addressFile.canRead()) {
                    val mac = normalizeMacAddress(addressFile.readText())
                    if (mac.isNotBlank()) return mac
                }
            } catch (e: Exception) {
                // 繼續嘗試其他網卡
            }
        }

        for (name in interfaces) {
            try {
                val networkInterface = NetworkInterface.getByName(name)
                    ?: continue

                val hardwareAddress = networkInterface.hardwareAddress
                    ?: continue

                val rawMac = hardwareAddress.joinToString("") {
                    "%02X".format(it.toInt() and 0xFF)
                }

                val mac = normalizeMacAddress(rawMac)
                if (mac.isNotBlank()) return mac

            } catch (e: Exception) {
                // 繼續嘗試其他網卡
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
                Toast.makeText(this, "下載網址不正確", Toast.LENGTH_LONG).show()
                return
            }

            if (activeDownloads.containsKey(url)) {
                Toast.makeText(this, "這個 APK 正在下載中", Toast.LENGTH_SHORT).show()
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
            activeDownloads[url] = id

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
                            activeDownloads.remove(url)
                            saveApkPackageName(fileName)
                            sendProgress(url, 100, "complete")

                            val uri = manager.getUriForDownloadedFile(id)
                            if (uri != null) openApkInstaller(uri)
                        }

                        DownloadManager.STATUS_FAILED -> {
                            activeDownloads.remove(url)
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

    private fun saveApkPackageName(fileName: String) {
        try {
            val apkFile = File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                File(fileName).name
            )

            if (!apkFile.exists()) return

            val packageInfo = packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                0
            ) ?: return

            val packageName = packageInfo.packageName

            if (packageName.isNotBlank()) {
                prefs.edit()
                    .putString("package_$fileName", packageName)
                    .apply()
            }

        } catch (e: Exception) {
            // 無法解析時不影響 APK 安裝
        }
    }

    private fun sendProgress(url: String, percent: Int, state: String) {
        val safeUrl = JSONObject.quote(url)
        val safeState = JSONObject.quote(state)

        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.onApkDownloadProgress && " +
                        "window.onApkDownloadProgress($safeUrl,$percent,$safeState);",
                    null
                )
            }
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

            pendingInstallCheck = true
            startActivity(intent)

        } catch (e: Exception) {
            pendingInstallCheck = false

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
            return
        }

        if (pendingInstallCheck && ::webView.isInitialized) {
            pendingInstallCheck = false

            handler.postDelayed({
                if (::webView.isInitialized) {
                    webView.evaluateJavascript(
                        "window.refreshInstalledState && " +
                            "window.refreshInstalledState();",
                        null
                    )
                }
            }, 1500)
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

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroy()
    }
}
