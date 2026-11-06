
package tw.com.huangyi.apkmarket

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
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
import android.widget.TextView
import android.widget.Toast
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var loadingText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
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
            text = "正在開啟 APK 市場..."
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
        fun checkInstalled(packageName: String, url: String) {
            executor.execute {
                val metadata = inspectApk(url)
                val apkPackage = metadata.first
                val appLabel = metadata.second
                val icon = metadata.third

                val targetPackage = if (apkPackage.isNotBlank()) {
                    apkPackage
                } else {
                    packageName
                }

                val installed = isPackageInstalled(targetPackage)

                val safeUrl = JSONObject.quote(url)
                val safeLabel = JSONObject.quote(appLabel)
                val safeIcon = JSONObject.quote(icon)
                val safePackage = JSONObject.quote(targetPackage)

                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onAppCheckResult(" +
                            "$safeUrl,$installed,$safeLabel,$safeIcon,$safePackage);",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun loadApkIcon(url: String, fileName: String) {
            executor.execute {
                val metadata = inspectApk(url)
                val icon = metadata.third

                val safeUrl = JSONObject.quote(url)
                val safeIcon = JSONObject.quote(icon)

                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onApkIconResult($safeUrl,$safeIcon);",
                        null
                    )
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

        @JavascriptInterface
        fun uninstallApp(packageName: String) {
            runOnUiThread {
                uninstallInstalledApp(packageName)
            }
        }
    }

    private fun inspectApk(url: String): Triple<String, String, String> {
        return try {
            val apkFile = getCachedApk(url)

            val info = packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_META_DATA
            )

            if (info == null) {
                Triple("", "APK", "")
            } else {
                val appInfo = info.applicationInfo
                var label = info.packageName
                var icon = ""

                if (appInfo != null) {
                    appInfo.sourceDir = apkFile.absolutePath
                    appInfo.publicSourceDir = apkFile.absolutePath

                    label = packageManager.getApplicationLabel(appInfo).toString()
                    icon = drawableToBase64(
                        packageManager.getApplicationIcon(appInfo)
                    )
                }

                Triple(info.packageName, label, icon)
            }
        } catch (e: Exception) {
            android.util.Log.e("APK_MARKET", "APK 讀取失敗", e)
            Triple("", "APK", "")
        }
    }

    private fun getCachedApk(url: String): File {
        val folder = File(cacheDir, "apk_metadata")
        if (!folder.exists()) folder.mkdirs()

        val cacheFile = File(folder, "apk_${url.hashCode()}.apk")

        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile
        }

        if (!url.startsWith(
                "https://github.com/dylan0728-web/Market/releases/download/"
            )) {
            throw Exception("不允許的 APK 網址")
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true

        try {
            connection.connect()

            if (connection.responseCode !in 200..299) {
                throw Exception("APK 下載失敗")
            }

            val tempFile = File(folder, cacheFile.name + ".tmp")

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!tempFile.renameTo(cacheFile)) {
                tempFile.copyTo(cacheFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            connection.disconnect()
        }

        return cacheFile
    }

    private fun drawableToBase64(drawable: Drawable): String {
        val width = drawable.intrinsicWidth.coerceIn(1, 512)
        val height = drawable.intrinsicHeight.coerceIn(1, 512)

        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)

        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
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
            startActivity(
                Intent(
                    Intent.ACTION_DELETE,
                    Uri.parse("package:$packageName")
                )
            )
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
                )) {
                Toast.makeText(this, "下載網址不正確", Toast.LENGTH_LONG).show()
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
                            sendProgress(url, 100, "complete")

                            val uri = manager.getUriForDownloadedFile(id)
                            if (uri != null) openApkInstaller(uri)
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

    private fun sendProgress(url: String, percent: Int, state: String) {
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
        if (!packageManager.canRequestPackageInstalls()) {
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
