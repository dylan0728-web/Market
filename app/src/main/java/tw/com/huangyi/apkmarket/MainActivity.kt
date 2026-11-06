
package tw.com.huangyi.apkmarket

import android.app.Activity
import android.app.AlertDialog
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
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private var webView: WebView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val metadataExecutor = Executors.newSingleThreadExecutor()

    private var currentDownloadId = -1L
    private var currentDownloadUrl = ""
    private var currentDownloadName = ""

    private var pendingInstallUri: Uri? = null
    private var installerSettingsOpened = false
    private var isDownloadActive = false

    companion object {
        private const val MARKET_URL =
            "https://dylan0728-web.github.io/Market/"

        private const val APK_MIME =
            "application/vnd.android.package-archive"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLogin()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun baseLayout(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            setBackgroundColor(0xFFF4F7FB.toInt())
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun button(
        label: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            setOnClickListener { action() }
            isFocusable = true
        }

    // 登入畫面
    private fun showLogin() {
        webView?.destroy()
        webView = null

        root = baseLayout()
        root.gravity = Gravity.CENTER

        val title = text(
            "Market",
            30f,
            0xFF14243A.toInt(),
            true
        ).apply {
            gravity = Gravity.CENTER
        }

        val user = EditText(this).apply {
            hint = "測試帳號"
            setSingleLine(true)
            textSize = 18f
            setText("demo")
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }

        val pass = EditText(this).apply {
            hint = "測試密碼"
            setSingleLine(true)
            textSize = 18f
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("123456")
            imeOptions = EditorInfo.IME_ACTION_DONE
        }

        val login = button("登入並進入市場") {
            if (
                user.text.toString() == "demo" &&
                pass.text.toString() == "123456"
            ) {
                showMarket()
            } else {
                Toast.makeText(
                    this,
                    "測試帳號或密碼錯誤",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val note = text(
            "測試版本：暫不啟用機身序號及日期授權。\n測試帳號 demo／密碼 123456",
            13f,
            0xFF68788A.toInt()
        )

        root.addView(title, matchWrap())
        root.addView(
            user,
            marginParams(-1, dp(58), top = dp(20), bottom = dp(10))
        )
        root.addView(
            pass,
            marginParams(-1, dp(58), bottom = dp(14))
        )
        root.addView(
            login,
            marginParams(-1, dp(60), bottom = dp(14))
        )
        root.addView(note, marginParams(-1, dp(52)))

        setContentView(root)
        login.requestFocus()
    }

    // APK 市場
    private fun showMarket() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F7FB.toInt())
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
            setBackgroundColor(0xFF14243A.toInt())
        }

        val heading = text(
            "Market",
            18f,
            0xFFFFFFFF.toInt(),
            true
        )

        bar.addView(
            heading,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        bar.addView(
            button("重新整理") {
                webView?.reload()
            },
            LinearLayout.LayoutParams(-2, dp(48))
        )

        bar.addView(
            button("登出") {
                showLogin()
            },
            LinearLayout.LayoutParams(-2, dp(48))
        )

        statusView = text(
            "正在開啟市場網頁…",
            14f,
            0xFF52657A.toInt()
        ).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        val web = WebView(this)
        webView = web

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(
            AndroidMarketBridge(),
            "AndroidMarket"
        )

        web.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                if (newProgress in 1..99 && !isDownloadActive) {
                    statusView.text = "正在載入市場網頁…"
                }
            }
        }

        web.webViewClient = object : WebViewClient() {

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
            ) {
                if (!isDownloadActive) {
                    statusView.text = "正在載入市場網頁…"
                }
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                if (!isDownloadActive) {
                    statusView.text = "市場已載入"
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return !(
                    url.startsWith("https://") ||
                    url.startsWith("http://")
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {
                    statusView.text =
                        "網頁載入失敗：${error?.description ?: "未知錯誤"}"
                }
            }
        }

        root.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        web.loadUrl(MARKET_URL)
        web.requestFocus()
    }

    // 網頁與 Android 溝通
    inner class AndroidMarketBridge {

        @JavascriptInterface
        fun checkApp(url: String, fileName: String) {
            if (!isAllowedReleaseUrl(url)) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "下載網址不符合安全限制",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }

            metadataExecutor.execute {
                try {
                    val info = inspectOneApk(url, fileName)

                    runOnUiThread {
                        val packageName = info.optString("packageName")
                        val icon = info.optString("icon")

                        if (icon.isNotBlank()) {
                            sendIcon(url, icon)
                        }

                        if (
                            info.optBoolean("installed") &&
                            packageName.isNotBlank()
                        ) {
                            showInstalledDialog(
                                packageName,
                                info.optString("appName", fileName)
                            )
                        } else {
                            startDownload(url, fileName)
                        }
                    }

                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "讀取 APK 失敗，改為直接下載",
                            Toast.LENGTH_SHORT
                        ).show()

                        startDownload(url, fileName)
                    }
                }
            }
        }

        @JavascriptInterface
        fun loadApkIcon(url: String, fileName: String) {
            if (!isAllowedReleaseUrl(url)) return

            metadataExecutor.execute {
                try {
                    val info = inspectOneApk(url, fileName)
                    val icon = info.optString("icon")

                    if (icon.isNotBlank()) {
                        runOnUiThread {
                            sendIcon(url, icon)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(
                        "MarketAPK",
                        "讀取圖示失敗：$fileName",
                        e
                    )
                }
            }
        }

        @JavascriptInterface
        fun startDownload(url: String, fileName: String) {
            runOnUiThread {
                this@MainActivity.startDownload(url, fileName)
            }
        }
    }

    // 已安裝軟體選單
    private fun showInstalledDialog(
        packageName: String,
        appName: String
    ) {
        AlertDialog.Builder(this)
            .setTitle(appName)
            .setMessage("這個軟體已經安裝在裝置上。")
            .setPositiveButton("開啟軟體") { _, _ ->
                launchInstalledApp(packageName)
            }
            .setNegativeButton("卸載／刪除軟體") { _, _ ->
                uninstallApp(packageName)
            }
            .show()
    }

    // 開啟已安裝軟體
    private fun launchInstalledApp(packageName: String) {
        try {
            val intent =
                packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(
                    this,
                    "找不到此軟體的啟動畫面",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "無法開啟軟體：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Android 系統卸載確認
    private fun uninstallApp(packageName: String) {
        try {
            val intent = Intent(
                Intent.ACTION_DELETE,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "無法開啟卸載畫面：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 限制只接受本專案 GitHub Releases 的 APK
    private fun isAllowedReleaseUrl(value: String): Boolean {
        return try {
            val uri = Uri.parse(value)

            uri.scheme == "https" &&
                uri.host == "github.com" &&
                uri.path.orEmpty().startsWith(
                    "/dylan0728-web/Market/releases/download/"
                )
        } catch (_: Exception) {
            false
        }
    }

    // 讀取 APK 名稱、套件名稱及圖示
    private fun inspectOneApk(
        url: String,
        fileName: String
    ): JSONObject {

        val result = JSONObject()

        val folder = File(cacheDir, "apk_metadata")
        if (!folder.exists()) folder.mkdirs()

        val hash = sha256(url)
        val apkFile = File(folder, "$hash.apk")

        if (!apkFile.exists() || apkFile.length() == 0L) {
            val tempFile = File(folder, "$hash.part")

            downloadMetadataApk(url, tempFile)

            if (apkFile.exists()) apkFile.delete()

            if (!tempFile.renameTo(apkFile)) {
                tempFile.copyTo(apkFile, overwrite = true)
                tempFile.delete()
            }
        }

        val packageInfo = packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_META_DATA
        ) ?: throw Exception("無法解析 APK")

        val appInfo = packageInfo.applicationInfo
            ?: throw Exception("APK 缺少應用程式資訊")

        appInfo.sourceDir = apkFile.absolutePath
        appInfo.publicSourceDir = apkFile.absolutePath

        val appName =
            packageManager.getApplicationLabel(appInfo).toString()

        val packageName = packageInfo.packageName

        val installedInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        val installed = installedInfo != null

        val iconDrawable: Drawable =
            if (installed) {
                try {
                    packageManager.getApplicationIcon(packageName)
                } catch (_: Exception) {
                    appInfo.loadIcon(packageManager)
                }
            } else {
                appInfo.loadIcon(packageManager)
            }

        result.put("appName", appName)
        result.put("packageName", packageName)
        result.put("versionName", packageInfo.versionName ?: "")
        result.put("installed", installed)
        result.put("icon", drawableToBase64(iconDrawable))
        result.put("ok", true)

        return result
    }

    // 下載 APK 資訊並快取
    private fun downloadMetadataApk(
        url: String,
        destination: File
    ) {
        val connection =
            URL(url).openConnection() as HttpURLConnection

        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.setRequestProperty(
            "User-Agent",
            "HuangYi-APK-Market"
        )

        try {
            connection.connect()

            if (connection.responseCode !in 200..299) {
                throw Exception(
                    "下載 APK 失敗：HTTP ${connection.responseCode}"
                )
            }

            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))

        return bytes.joinToString("") {
            "%02x".format(it)
        }
    }

    // 將圖示轉成網頁可讀取的 Base64
    private fun drawableToBase64(
        drawable: Drawable
    ): String {
        val bitmap = Bitmap.createBitmap(
            128,
            128,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 128, 128)
        drawable.draw(canvas)

        val output = java.io.ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.PNG,
            100,
            output
        )

        bitmap.recycle()

        return Base64.encodeToString(
            output.toByteArray(),
            Base64.NO_WRAP
        )
    }

    private fun sendIcon(url: String, icon: String) {
        val web = webView ?: return

        val safeUrl = android.util.Base64.encodeToString(
            url.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val safeIcon = JSONObject.quote(
            "data:image/png;base64,$icon"
        )

        web.evaluateJavascript(
            "window.onApkIconResult && " +
                "window.onApkIconResult(" +
                "atob('${safeUrl}'),$safeIcon);",
            null
        )
    }

    // 開始下載 APK
    private fun startDownload(
        url: String,
        fileName: String
    ) {
        if (!isAllowedReleaseUrl(url)) {
            Toast.makeText(
                this,
                "下載網址不符合安全限制",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (isDownloadActive) {
            Toast.makeText(
                this,
                "目前已有下載正在進行，請稍候。",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            val safeName = fileName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .let {
                    if (it.lowercase().endsWith(".apk")) it
                    else "$it.apk"
                }

            val request = DownloadManager.Request(
                Uri.parse(url)
            )
                .setTitle(safeName)
                .setDescription("皇奕科技 APK 市場下載")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(
                    DownloadManager.Request
                        .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    safeName
                )

            val manager = getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager

            isDownloadActive = true
            currentDownloadUrl = url
            currentDownloadName = safeName

            pendingInstallUri = null
            installerSettingsOpened = false

            statusView.text = "開始下載：$safeName"

            currentDownloadId = manager.enqueue(request)

            Toast.makeText(
                this,
                "已開始下載：$safeName",
                Toast.LENGTH_SHORT
            ).show()

            checkDownloadProgress(currentDownloadId)

        } catch (e: Exception) {
            isDownloadActive = false
            currentDownloadId = -1L

            statusView.text = "下載啟動失敗：${e.message}"

            Toast.makeText(
                this,
                "無法啟動下載：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 每秒回傳該 APK 的下載進度
    private fun checkDownloadProgress(id: Long) {
        handler.postDelayed({

            if (
                id != currentDownloadId ||
                !isDownloadActive
            ) {
                return@postDelayed
            }

            try {
                val manager = getSystemService(
                    Context.DOWNLOAD_SERVICE
                ) as DownloadManager

                val cursor = manager.query(
                    DownloadManager.Query().setFilterById(id)
                )

                cursor.use {

                    if (!it.moveToFirst()) {
                        checkDownloadProgress(id)
                        return@use
                    }

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

                        DownloadManager.STATUS_PENDING -> {
                            statusView.text = "下載排隊中…"
                            sendDownloadProgress(0, "downloading")
                            checkDownloadProgress(id)
                        }

                        DownloadManager.STATUS_RUNNING -> {
                            val percent =
                                if (total > 0) {
                                    (downloaded * 100 / total)
                                        .toInt()
                                        .coerceIn(0, 100)
                                } else 0

                            statusView.text = "正在下載：$percent%"

                            sendDownloadProgress(
                                percent,
                                "downloading"
                            )

                            checkDownloadProgress(id)
                        }

                        DownloadManager.STATUS_PAUSED -> {
                            statusView.text = "下載暫停，等待網路恢復…"
                            checkDownloadProgress(id)
                        }

                        DownloadManager.STATUS_SUCCESSFUL -> {
                            isDownloadActive = false

                            sendDownloadProgress(100, "complete")

                            statusView.text =
                                "下載完成，準備安裝 APK。"

                            val apkUri =
                                manager.getUriForDownloadedFile(id)

                            currentDownloadId = -1L

                            if (apkUri != null) {
                                pendingInstallUri = apkUri
                                installerSettingsOpened = false
                                openApkInstaller()
                            } else {
                                statusView.text =
                                    "下載完成，但無法取得 APK 檔案。"
                            }
                        }

                        DownloadManager.STATUS_FAILED -> {
                            val reason = it.getInt(
                                it.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_REASON
                                )
                            )

                            isDownloadActive = false
                            currentDownloadId = -1L

                            sendDownloadProgress(0, "failed")

                            statusView.text =
                                "下載失敗，錯誤代碼：$reason"

                            Toast.makeText(
                                this@MainActivity,
                                "APK 下載失敗，請重新下載。",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

            } catch (e: Exception) {
                statusView.text =
                    "讀取下載進度失敗：${e.message}"

                if (
                    isDownloadActive &&
                    id == currentDownloadId
                ) {
                    checkDownloadProgress(id)
                }
            }

        }, 1000)
    }

    private fun sendDownloadProgress(
        percent: Int,
        state: String
    ) {
        val web = webView ?: return

        val safeUrl = JSONObject.quote(currentDownloadUrl)
        val safeState = JSONObject.quote(state)

        web.evaluateJavascript(
            "window.onApkDownloadProgress && " +
                "window.onApkDownloadProgress(" +
                "$safeUrl,$percent,$safeState);",
            null
        )
    }

    // Android 原生安裝 APK
    private fun openApkInstaller() {
        val uri = pendingInstallUri ?: return

        try {
            if (
                android.os.Build.VERSION.SDK_INT >= 26 &&
                !packageManager.canRequestPackageInstalls()
            ) {
                if (!installerSettingsOpened) {
                    installerSettingsOpened = true

                    statusView.text =
                        "請允許 Market 安裝未知來源應用程式。"

                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )

                    Toast.makeText(
                        this,
                        "請允許安裝未知應用程式，完成後返回 Market。",
                        Toast.LENGTH_LONG
                    ).show()
                }

                return
            }

            val intent = Intent(
                Intent.ACTION_INSTALL_PACKAGE
            ).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }

            startActivity(intent)

            statusView.text = "已開啟 APK 安裝畫面。"

            pendingInstallUri = null
            installerSettingsOpened = false

        } catch (e: Exception) {
            statusView.text =
                "無法開啟安裝畫面：${e.message}"

            Toast.makeText(
                this,
                "開啟安裝程式失敗：${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()

        if (
            pendingInstallUri != null &&
            android.os.Build.VERSION.SDK_INT >= 26 &&
            packageManager.canRequestPackageInstalls()
        ) {
            openApkInstaller()
        }
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(-1, -2)

    private fun marginParams(
        width: Int,
        height: Int,
        top: Int = 0,
        bottom: Int = 0
    ) = LinearLayout.LayoutParams(width, height).apply {
        topMargin = top
        bottomMargin = bottom
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val web = webView

        if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            showLogin()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        metadataExecutor.shutdownNow()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
