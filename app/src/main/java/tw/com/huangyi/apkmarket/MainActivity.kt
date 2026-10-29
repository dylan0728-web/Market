
package tw.com.huangyi.apkmarket

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private var webView: WebView? = null

    companion object {
        private const val MARKET_URL =
            "https://dylan0728-web.github.io/Market/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        showLogin()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

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
            isFocusableInTouchMode = true
        }

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
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
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
            button("重新整理") { webView?.reload() },
            LinearLayout.LayoutParams(-2, dp(48))
        )

        bar.addView(
            button("登出") { showLogin() },
            LinearLayout.LayoutParams(-2, dp(48))
        )

        val progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        val status = text(
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

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                progress.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onConsoleMessage(
                message: android.webkit.ConsoleMessage
            ): Boolean {
                android.util.Log.e(
                    "MarketWeb",
                    "${message.message()} line=${message.lineNumber()}"
                )
                return true
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                status.text = "正在載入市場網頁…"
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                status.text = "網頁已載入；若清單未顯示，請檢查 MEGA 讀取狀態。"
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return !(url.startsWith("https://") ||
                    url.startsWith("http://"))
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    status.text =
                        "網頁載入失敗：${error?.description ?: "未知錯誤"}"
                }
            }
        }

        web.setDownloadListener(
            DownloadListener { url, userAgent, _, mimeType, _ ->
                try {
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setTitle("Market APK Download")
                        .setDescription("正在下載檔案")
                        .setNotificationVisibility(
                            DownloadManager.Request
                                .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)
                        .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            "market_download.apk"
                        )

                    if (!userAgent.isNullOrBlank()) {
                        request.addRequestHeader("User-Agent", userAgent)
                    }

                    if (!mimeType.isNullOrBlank()) {
                        request.setMimeType(mimeType)
                    }

                    val cookies =
                        CookieManager.getInstance().getCookie(url)

                    if (!cookies.isNullOrBlank()) {
                        request.addRequestHeader("Cookie", cookies)
                    }

                    val manager =
                        getSystemService(Context.DOWNLOAD_SERVICE)
                            as DownloadManager

                    manager.enqueue(request)

                    Toast.makeText(
                        this,
                        "已交由系統下載管理器處理",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "無法啟動下載：${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        root.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)

        web.loadUrl(MARKET_URL)
        web.requestFocus()
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
}
