
package tw.com.huangyi.apkmarket

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
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
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.Typeface

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())

    private var currentDownloadId: Long = -1
    private var currentDownloadUrl = ""
    private var pendingInstallUri: Uri? = null
    private var checkingDownload = false
    private var marketVisible = false

    private val downloadManager by lazy {
        getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    private val apkMime = "application/vnd.android.package-archive"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLogin()
    }

    private fun makeRoot() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(18, 28, 38))
        root.gravity = Gravity.CENTER
        root.setPadding(35, 25, 35, 25)
        setContentView(root)
    }

    private fun showLogin() {
        marketVisible = false
        makeRoot()

        val title = TextView(this)
        title.text = "APK 應用程式下載"
        title.textSize = 27f
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        root.addView(title)

        val userInput = EditText(this)
        userInput.hint = "帳號"
        userInput.setSingleLine(true)
        userInput.setTextColor(Color.WHITE)
        userInput.setHintTextColor(Color.LTGRAY)
        userInput.setBackgroundColor(Color.rgb(48, 62, 75))
        userInput.setPadding(18, 12, 18, 12)

        val passInput = EditText(this)
        passInput.hint = "密碼"
        passInput.setSingleLine(true)
        passInput.inputType = 129
        passInput.setTextColor(Color.WHITE)
        passInput.setHintTextColor(Color.LTGRAY)
        passInput.setBackgroundColor(Color.rgb(48, 62, 75))
        passInput.setPadding(18, 12, 18, 12)

        val loginButton = Button(this)
        loginButton.text = "登入市場"

        val loginMessage = TextView(this)
        loginMessage.setTextColor(Color.YELLOW)
        loginMessage.textSize = 16f

        root.addView(userInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 58
        ).apply { topMargin = 25 })

        root.addView(passInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 58
        ).apply { topMargin = 15 })

        root.addView(loginButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 58
        ).apply { topMargin = 20 })

        root.addView(loginMessage)

        fun login() {
            if (userInput.text.toString() == "demo" &&
                passInput.text.toString() == "123456") {
                showMarket()
            } else {
                loginMessage.text = "帳號或密碼錯誤"
            }
        }

        loginButton.setOnClickListener { login() }

        passInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                login()
                true
            } else false
        }

        userInput.requestFocus()
    }

    private fun showMarket() {
        marketVisible = true
        makeRoot()
        root.gravity = Gravity.TOP

        statusText = TextView(this)
        statusText.text = "APK 市場"
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
            ViewGroup.LayoutParams.MATCH_PARENT, 8
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
                statusText.text = "APK 市場已載入"
            }
        }

        webView.webChromeClient = WebChromeClient()

        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
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
    }

    private fun beginDownload(url: String, fileName: String) {
        try {
            if (!url.startsWith("https://")) {
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
                this, "下載失敗："+e.message, Toast.LENGTH_LONG
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

                                sendProgress(
                                    currentDownloadUrl,
                                    100,
                                    "complete"
                                )

                                val uri =
                                    downloadManager.getUriForDownloadedFile(id)

                                currentDownloadId = -1L
                                checkingDownload = false

                                if (uri != null) {
                                    openApkInstaller(uri)
                                } else {
                                    statusText.text =
                                        "下載完成，但無法取得檔案"
                                }

                                return
                            }

                            if (status == DownloadManager.STATUS_FAILED) {

                                currentDownloadId = -1L
                                checkingDownload = false

                                sendProgress(
                                    currentDownloadUrl,
                                    0,
                                    "failed"
                                )

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

        val safeUrl = url
            .replace("\\", "\\\\")
            .replace("'", "\\'")

        webView.post {
            webView.evaluateJavascript(
                "window.onApkDownloadProgress && " +
                    "window.onApkDownloadProgress(" +
                    "'$safeUrl',$percent,'$state');",
                null
            )
        }
    }

    private fun openApkInstaller(uri: Uri) {
        try {

            // 尚未允許安裝未知應用程式時，先開啟權限設定
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

            // 關鍵修正：啟動安裝畫面前，先清除待安裝路徑
            // 避免安裝完成返回時 onResume 再次開啟同一個 APK
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
                "無法開啟安裝畫面："+e.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()

        // 只有從未知應用程式權限設定返回時，才會有待安裝檔案
        val uri = pendingInstallUri ?: return

        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !packageManager.canRequestPackageInstalls()) {
            return
        }

        // 先清除，確保只觸發一次
        pendingInstallUri = null

        handler.postDelayed({
            openApkInstaller(uri)
        }, 500)
    }

    override fun onBackPressed() {
        if (!marketVisible) {
            finish()
            return
        }

        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            showLogin()
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !marketVisible) {
            finish()
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidMarket")
            webView.destroy()
        }

        super.onDestroy()
    }
}
