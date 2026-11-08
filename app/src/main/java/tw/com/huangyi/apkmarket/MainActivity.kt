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
import android.util.Base64
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var loadingText: TextView

    private val handler = Handler(Looper.getMainLooper())

    private var pendingInstallUri: Uri? = null
    private var pendingInstallCheck = false

    private val activeDownloads = mutableMapOf<String, Long>()

    @Volatile
    private var authorized = false

    @Volatile
    private var specialAuthorized = false

    @Volatile
    private var authorizationDeviceMac = ""

    @Volatile
    private var authorizationErrorMessage = "尚未取得授權檢查結果"

    private val prefs by lazy {
        getSharedPreferences("apk_market_packages", Context.MODE_PRIVATE)
    }

    companion object {
        private const val MARKET_URL =
            "https://dylan0728-web.github.io/Market/"

        private const val MEGA_AUTH_URL =
            "https://mega.nz/file/EoNUVDqJ#DmbXUADAMda3Q0weOODFlBWVRus-qBPQIF0jOWzMeRY"

        private const val AUTH_ERROR =
            "此機器MAC授權到期或是未授權"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authorized = false
        specialAuthorized = false
        authorizationDeviceMac = ""
        authorizationErrorMessage = "正在檢查授權"

        showAuthorizationLoading()
        checkCloudAuthorization()
    }

    // =====================================================
    // 授權畫面
    // =====================================================

    private fun showAuthorizationLoading() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF101820.toInt())
        }

        val text = TextView(this).apply {
            text = "正在確認機器授權，請稍候..."
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(30, 30, 30, 30)
        }

        layout.addView(text)
        setContentView(layout)
    }

    private fun showAuthorizationDenied() {
        authorized = false
        specialAuthorized = false

        val mac = authorizationDeviceMac.ifBlank {
            "無法讀取 MAC"
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF101820.toInt())
            setPadding(30, 30, 30, 30)
        }

        val title = TextView(this).apply {
            text = AUTH_ERROR
            textSize = 23f
            setTextColor(0xFFFF6666.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(10, 10, 10, 25)
        }

        val detail = TextView(this).apply {
            text = "機器 MAC：$mac\n\n錯誤原因：\n$authorizationErrorMessage\n\n雲端授權資料中的期限：2027/12/31"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(10, 15, 10, 15)
        }

        layout.addView(title)
        layout.addView(detail)
        setContentView(layout)
    }

    // =====================================================
    // 雲端授權檢查
    // =====================================================

    private fun checkCloudAuthorization() {
        Thread {
            try {
                authorizationErrorMessage = "正在讀取機器 MAC"

                val deviceMac = readHardwareMac()
                authorizationDeviceMac = deviceMac

                if (deviceMac.isBlank()) {
                    throw Exception("無法讀取機器 MAC，請確認電視盒網卡")
                }

                authorizationErrorMessage = "正在連線 MEGA 下載授權檔"

                val authorizationText = downloadMegaAuthorizationText()

                if (authorizationText.isBlank()) {
                    throw Exception("MEGA 授權檔內容為空")
                }

                authorizationErrorMessage = "授權檔已下載，正在比對 MAC 與期限"

                val result = verifyAuthorization(
                    authorizationText,
                    deviceMac
                )

                if (!result.first) {
                    throw Exception(
                        authorizationErrorMessage.ifBlank {
                            "授權檔已成功讀取，但找不到符合的有效授權。"
                        }
                    )
                }

                authorized = true
                specialAuthorized = result.second
                authorizationErrorMessage = ""

                runOnUiThread {
                    showMarket()
                }

            } catch (e: Exception) {
                authorized = false
                specialAuthorized = false

                authorizationErrorMessage =
                    e.message ?: "未知授權錯誤"

                runOnUiThread {
                    showAuthorizationDenied()
                }
            }
        }.start()
    }

    // =====================================================
    // MEGA 授權檔下載及解密
    // =====================================================

    private fun downloadMegaAuthorizationText(): String {

        val linkParts = MEGA_AUTH_URL
            .substringAfter("/file/")
            .split("#")

        if (linkParts.size != 2) {
            throw Exception("MEGA 分享連結格式錯誤")
        }

        val fileHandle = linkParts[0]
        val keyString = linkParts[1]

        val fileKey = try {
            Base64.decode(
                keyString,
                Base64.URL_SAFE or
                    Base64.NO_WRAP or
                    Base64.NO_PADDING
            )
        } catch (e: Exception) {
            throw Exception("MEGA 授權金鑰解碼失敗：${e.message}")
        }

        if (fileKey.size != 32) {
            throw Exception("MEGA 授權金鑰格式錯誤")
        }

        val apiUrl =
            "https://g.api.mega.co.nz/cs?id=${System.currentTimeMillis()}"

        val command = JSONArray().put(
            JSONObject()
                .put("a", "g")
                .put("g", 1)
                .put("p", fileHandle)
        )

        val mediaType =
            "application/json; charset=utf-8".toMediaType()

        val apiRequest = Request.Builder()
            .url(apiUrl)
            .post(command.toString().toRequestBody(mediaType))
            .build()

        val downloadInfo = httpClient.newCall(apiRequest)
            .execute().use { response ->

                if (!response.isSuccessful) {
                    throw Exception("MEGA API 連線失敗，HTTP ${response.code}")
                }

                val body = response.body?.string()
                    ?: throw Exception("MEGA API 沒有回應")

                val array = try {
                    JSONArray(body)
                } catch (e: Exception) {
                    throw Exception("MEGA API 回傳資料格式錯誤：${e.message}")
                }

                val item = array.optJSONObject(0)
                    ?: throw Exception("MEGA 授權檔不存在或分享連結失效")

                val url = item.optString("g", "")
                val size = item.optLong("s", -1)

                if (url.isBlank() || size < 0) {
                    throw Exception("MEGA 授權檔資料錯誤，下載網址或檔案大小不存在")
                }

                Pair(url, size)
            }

        val originalDownloadUrl = downloadInfo.first

        val secureDownloadUrl =
            if (originalDownloadUrl.startsWith(
                    "http://",
                    ignoreCase = true
                )
            ) {
                originalDownloadUrl.replaceFirst(
                    Regex("^http://", RegexOption.IGNORE_CASE),
                    "https://"
                )
            } else {
                originalDownloadUrl
            }

        val fileRequest = Request.Builder()
            .url(secureDownloadUrl)
            .get()
            .build()

        val encryptedData = try {
            httpClient.newCall(fileRequest)
                .execute().use { response ->

                    if (!response.isSuccessful) {
                        throw Exception(
                            "MEGA 授權檔下載失敗，HTTP ${response.code}"
                        )
                    }

                    response.body?.bytes()
                        ?: throw Exception("授權檔內容為空")
                }
        } catch (e: Exception) {
            throw Exception(
                "MEGA 授權檔 HTTPS 下載失敗：${e.message}"
            )
        }

        if (encryptedData.size.toLong() != downloadInfo.second) {
            throw Exception(
                "MEGA 授權檔大小不正確，預期 ${downloadInfo.second} bytes，" +
                    "實際 ${encryptedData.size} bytes"
            )
        }

        val words = ByteBuffer.wrap(fileKey)
            .order(ByteOrder.BIG_ENDIAN)

        val keyWords = IntArray(8)

        for (i in 0 until 8) {
            keyWords[i] = words.int
        }

        val aesKey = ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(keyWords[0] xor keyWords[4])
            .putInt(keyWords[1] xor keyWords[5])
            .putInt(keyWords[2] xor keyWords[6])
            .putInt(keyWords[3] xor keyWords[7])
            .array()

        val iv = ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(keyWords[4])
            .putInt(keyWords[5])
            .putLong(0L)
            .array()

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            IvParameterSpec(iv)
        )

        val decryptedData = try {
            cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            throw Exception("MEGA 授權檔解密失敗：${e.message}")
        }

        return String(
            decryptedData,
            StandardCharsets.UTF_8
        )
    }

    // =====================================================
    // 授權比對：逐欄驗證日期及 MAC，並輸出診斷資訊
    // =====================================================

    private fun verifyAuthorization(
        content: String,
        deviceMac: String
    ): Pair<Boolean, Boolean> {

        val normalizedDeviceMac = normalizeMacAddress(deviceMac)

        if (normalizedDeviceMac.isBlank()) {
            authorizationErrorMessage =
                "機器 MAC 標準化失敗。\n原始 MAC：${deviceMac.take(80)}"

            return Pair(false, false)
        }

        val cleanContent = cleanAuthorizationText(content)

        if (cleanContent.isBlank()) {
            authorizationErrorMessage = "授權檔解密後內容為空。"
            return Pair(false, false)
        }

        var foundAuthorization = false
        var foundSpecialAuthorization = false

        var matchingMacFound = false
        var matchingMacDateError = ""
        var matchingMacExpired = ""
        var diagnosticRows = mutableListOf<String>()

        val lines = cleanContent
            .lineSequence()
            .toList()

        for ((index, line) in lines.withIndex()) {

            val row = cleanAuthorizationText(line).trim()

            if (row.isBlank()) continue

            val columns = row.split(",")

            if (columns.size < 2) {
                if (diagnosticRows.size < 4) {
                    diagnosticRows.add(
                        "第 ${index + 1} 行：欄位不足，內容=${row.take(100)}"
                    )
                }
                continue
            }

            val dateText = cleanAuthorizationText(
                columns[0]
            ).trim()

            val rawMac = cleanAuthorizationText(
                columns[1]
            ).trim()

            val macText = normalizeMacAddress(rawMac)

            if (macText.isBlank()) {
                if (diagnosticRows.size < 4) {
                    diagnosticRows.add(
                        "第 ${index + 1} 行：MAC 格式無效，原始 MAC=${rawMac.take(60)}"
                    )
                }
                continue
            }

            if (macText != normalizedDeviceMac) {
                if (diagnosticRows.size < 4) {
                    diagnosticRows.add(
                        "第 ${index + 1} 行：MAC 不相符，" +
                            "授權 MAC=$macText，機器 MAC=$normalizedDeviceMac"
                    )
                }
                continue
            }

            matchingMacFound = true

            // 日期必須為 yyyy/MM/dd
            if (!dateText.matches(Regex("\\d{4}/\\d{2}/\\d{2}"))) {
                matchingMacDateError =
                    "MAC 已相符，但日期格式錯誤：[$dateText]"
                continue
            }

            val dateParts = dateText.split("/")

            val year = dateParts[0].toIntOrNull()
            val month = dateParts[1].toIntOrNull()
            val day = dateParts[2].toIntOrNull()

            if (year == null || month == null || day == null) {
                matchingMacDateError =
                    "MAC 已相符，但日期無法轉換成數字：[$dateText]"
                continue
            }

            // 手動建立日期，禁止自動修正錯誤日期
            val expiryCalendar = Calendar.getInstance().apply {
                isLenient = false
                clear()
                set(
                    year,
                    month - 1,
                    day,
                    0,
                    0,
                    0
                )
                set(Calendar.MILLISECOND, 0)
            }

            try {
                // 強制驗證日期是否真實存在
                expiryCalendar.timeInMillis
            } catch (e: Exception) {
                matchingMacDateError =
                    "MAC 已相符，但日期不是有效日曆日期：[$dateText]"
                continue
            }

            // 到期日當天 23:59:59 前有效
            expiryCalendar.add(Calendar.DAY_OF_MONTH, 1)

            val now = Calendar.getInstance()

            if (!now.before(expiryCalendar)) {
                matchingMacExpired =
                    "MAC 已相符，但授權已到期：$dateText"
                continue
            }

            foundAuthorization = true

            val flag = if (columns.size >= 3) {
                cleanAuthorizationText(columns[2]).trim()
            } else {
                ""
            }

            if (flag.equals("S", ignoreCase = true)) {
                foundSpecialAuthorization = true
            }
        }

        if (foundAuthorization) {
            authorizationErrorMessage = ""
        } else {

            authorizationErrorMessage = buildString {

                append("授權檔已成功下載及解密。")
                append("\n目前機器 MAC：$normalizedDeviceMac")

                if (!matchingMacFound) {
                    append("\n\n診斷結果：找不到相同 MAC。")
                    append("\n以下是實際讀取的授權資料：")

                    if (diagnosticRows.isEmpty()) {
                        append("\n授權檔沒有可解析的資料列。")
                    } else {
                        diagnosticRows.forEach {
                            append("\n")
                            append(it)
                        }
                    }

                } else if (matchingMacDateError.isNotBlank()) {
                    append("\n\n診斷結果：")
                    append(matchingMacDateError)

                } else if (matchingMacExpired.isNotBlank()) {
                    append("\n\n診斷結果：")
                    append(matchingMacExpired)

                } else {
                    append("\n\nMAC 已找到，但沒有通過日期及授權條件。")
                    append("\n請檢查授權資料每一欄是否正確。")
                }

                append("\n\n授權資料行數：${lines.size}")
            }
        }

        return Pair(
            foundAuthorization,
            foundSpecialAuthorization
        )
    }

    // =====================================================
    // 授權文字清理
    // =====================================================

    private fun cleanAuthorizationText(value: String): String {
        return value
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\u2060", "")
            .replace("\u0000", "")
            .replace("\u00A0", " ")
            .replace("\u3000", " ")
    }

    // =====================================================
    // MAC 讀取及標準化
    // =====================================================

    private fun normalizeMacAddress(value: String): String {

        val cleaned = cleanAuthorizationText(value)
            .trim()
            .uppercase(Locale.ROOT)

        val mac = cleaned
            .replace(":", "")
            .replace("-", "")
            .replace(".", "")
            .replace(" ", "")
            .replace("\t", "")
            .replace("\r", "")
            .replace("\n", "")

        if (!mac.matches(Regex("[0-9A-F]{12}"))) {
            return ""
        }

        if (mac == "000000000000") return ""
        if (mac == "020000000000") return ""

        return mac
    }

    private fun readHardwareMac(): String {

        val preferredNames = listOf(
            "eth0",
            "wlan0",
            "wlan1",
            "en0"
        )

        for (name in preferredNames) {
            try {
                val addressFile = File(
                    "/sys/class/net/$name/address"
                )

                if (addressFile.exists() && addressFile.canRead()) {
                    val mac = normalizeMacAddress(
                        addressFile.readText().trim()
                    )

                    if (mac.isNotBlank()) return mac
                }

            } catch (e: Exception) {
                // 繼續嘗試其他網卡
            }
        }

        try {
            val netDirectory = File("/sys/class/net")
            val networkFiles = netDirectory.listFiles()

            if (networkFiles != null) {

                val excludedPrefixes = listOf(
                    "lo", "dummy", "tun", "tap", "rmnet",
                    "p2p", "sit", "ip6tnl", "veth",
                    "docker", "br-"
                )

                val candidates = networkFiles
                    .filter { file ->
                        val name = file.name.lowercase(Locale.ROOT)

                        !preferredNames.contains(name) &&
                            excludedPrefixes.none {
                                name.startsWith(it)
                            }
                    }
                    .sortedBy { file ->
                        val name = file.name.lowercase(Locale.ROOT)

                        when {
                            name.startsWith("eth") -> 0
                            name.startsWith("en") -> 1
                            name.startsWith("wlan") -> 2
                            name.startsWith("wifi") -> 3
                            else -> 4
                        }
                    }

                for (networkFile in candidates) {
                    try {
                        val addressFile = File(
                            networkFile,
                            "address"
                        )

                        if (!addressFile.exists() ||
                            !addressFile.canRead()
                        ) {
                            continue
                        }

                        val mac = normalizeMacAddress(
                            addressFile.readText().trim()
                        )

                        if (mac.isNotBlank()) return mac

                    } catch (e: Exception) {
                        // 繼續嘗試下一張網卡
                    }
                }
            }

        } catch (e: Exception) {
            // 繼續使用 NetworkInterface
        }

        try {
            val enumeration =
                NetworkInterface.getNetworkInterfaces()

            if (enumeration != null) {

                val allInterfaces = mutableListOf<NetworkInterface>()

                while (enumeration.hasMoreElements()) {
                    allInterfaces.add(enumeration.nextElement())
                }

                val excludedPrefixes = listOf(
                    "lo", "dummy", "tun", "tap", "rmnet",
                    "p2p", "sit", "ip6tnl", "veth",
                    "docker", "br-"
                )

                val candidates = allInterfaces
                    .filter { networkInterface ->

                        val name =
                            networkInterface.name.lowercase(Locale.ROOT)

                        try {
                            !networkInterface.isLoopback &&
                                !networkInterface.isVirtual &&
                                excludedPrefixes.none {
                                    name.startsWith(it)
                                }
                        } catch (e: Exception) {
                            false
                        }
                    }
                    .sortedBy { networkInterface ->
                        val name =
                            networkInterface.name.lowercase(Locale.ROOT)

                        when {
                            name.startsWith("eth") -> 0
                            name.startsWith("en") -> 1
                            name.startsWith("wlan") -> 2
                            name.startsWith("wifi") -> 3
                            else -> 4
                        }
                    }

                for (networkInterface in candidates) {
                    try {
                        val hardwareAddress =
                            networkInterface.hardwareAddress ?: continue

                        val rawMac = hardwareAddress.joinToString("") {
                            "%02X".format(it.toInt() and 0xFF)
                        }

                        val mac = normalizeMacAddress(rawMac)

                        if (mac.isNotBlank()) return mac

                    } catch (e: Exception) {
                        // 繼續嘗試下一張網卡
                    }
                }
            }

        } catch (e: Exception) {
            // 找不到網卡時回傳空字串
        }

        return ""
    }

    // =====================================================
    // 建立應用市場
    // =====================================================

    private fun showMarket() {

        if (!authorized) {
            showAuthorizationDenied()
            return
        }

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

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
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

        webView.loadUrl(MARKET_URL)
    }

    // =====================================================
    // JavaScript 與 Android 溝通
    // =====================================================

    inner class AndroidMarketBridge {

        @JavascriptInterface
        fun isAuthorized(): Boolean {
            return authorized
        }

        @JavascriptInterface
        fun hasSpecialAccess(): Boolean {
            return authorized && specialAuthorized
        }

        @JavascriptInterface
        fun getDeviceMac(): String {
            return authorizationDeviceMac.ifBlank {
                readHardwareMac()
            }
        }

        @JavascriptInterface
        fun inspectApks(jsonText: String) {

            if (!authorized) return

            try {
                val input = JSONArray(jsonText)

                val installedPackages =
                    packageManager.getInstalledPackages(0)

                val output = JSONArray()

                for (i in 0 until input.length()) {

                    val item = input.optJSONObject(i) ?: continue

                    val url = item.optString("url", "")
                    val fileName = item.optString("fileName", "")
                    val appName = item.optString("appName", "")

                    if (isSpecialApk(fileName) &&
                        !specialAuthorized
                    ) {
                        continue
                    }

                    var packageName = prefs.getString(
                        "package_$fileName",
                        ""
                    ) ?: ""

                    var installed = false
                    var installedLabel = appName

                    var installedVersion =
                        item.optString("versionName", "")

                    if (packageName.isNotBlank()) {
                        try {
                            val info = packageManager.getPackageInfo(
                                packageName,
                                0
                            )

                            installed = true

                            installedVersion =
                                info.versionName ?: installedVersion

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
                        ).map {
                            normalizeName(it)
                        }.filter {
                            it.isNotBlank()
                        }

                        for (info in installedPackages) {

                            val label = try {
                                packageManager.getApplicationLabel(
                                    info.applicationInfo!!
                                ).toString()
                            } catch (e: Exception) {
                                ""
                            }

                            val normalizedLabel = normalizeName(label)

                            if (
                                normalizedLabel.isNotBlank() &&
                                candidates.contains(normalizedLabel)
                            ) {
                                packageName = info.packageName
                                installed = true
                                installedLabel = label

                                installedVersion =
                                    info.versionName ?: installedVersion

                                prefs.edit()
                                    .putString(
                                        "package_$fileName",
                                        packageName
                                    )
                                    .apply()

                                break
                            }
                        }
                    }

                    val result = JSONObject()

                    result.put("url", url)
                    result.put("appName", installedLabel)
                    result.put(
                        "packageName",
                        if (installed) packageName else ""
                    )
                    result.put("versionName", installedVersion)
                    result.put("installed", installed)

                    output.put(result)
                }

                runOnUiThread {
                    if (authorized && ::webView.isInitialized) {
                        webView.evaluateJavascript(
                            "window.onApkMetadata && " +
                                "window.onApkMetadata($output);",
                            null
                        )
                    }
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
        fun checkInstalled(
            packageName: String,
            url: String
        ) {
            if (!authorized) return

            runOnUiThread {

                val installed = isPackageInstalled(packageName)

                val safeUrl = JSONObject.quote(url)
                val safePackage = JSONObject.quote(packageName)

                webView.evaluateJavascript(
                    "window.onAppCheckResult && " +
                        "window.onAppCheckResult(" +
                        "$safeUrl,$installed,$safePackage);",
                    null
                )
            }
        }

        @JavascriptInterface
        fun startDownload(
            url: String,
            fileName: String
        ) {
            runOnUiThread {

                if (!authorized) {
                    Toast.makeText(
                        this@MainActivity,
                        AUTH_ERROR,
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }

                if (isSpecialApk(fileName) &&
                    !specialAuthorized
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        "此軟體尚未取得特殊軟體授權",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }

                downloadApk(url, fileName)
            }
        }

        @JavascriptInterface
        fun launchApp(packageName: String) {
            if (!authorized) return

            runOnUiThread {
                launchInstalledApp(packageName)
            }
        }

        @JavascriptInterface
        fun uninstallApp(packageName: String) {
            if (!authorized) return

            runOnUiThread {
                uninstallInstalledApp(packageName)
            }
        }
    }

    // =====================================================
    // APK 名稱及特殊軟體判斷
    // =====================================================

    private fun isSpecialApk(fileName: String): Boolean {
        return fileName.trim().endsWith(
            "_S.apk",
            ignoreCase = true
        )
    }

    private fun normalizeName(value: String): String {
        return value.lowercase(Locale.ROOT)
            .filter {
                it.isLetterOrDigit()
            }
    }

    // =====================================================
    // 已安裝軟體
    // =====================================================

    private fun isPackageInstalled(
        packageName: String
    ): Boolean {

        if (packageName.isBlank()) return false

        return try {
            packageManager.getPackageInfo(
                packageName,
                0
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun launchInstalledApp(
        packageName: String
    ) {
        try {
            val intent =
                packageManager.getLaunchIntentForPackage(packageName)

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

    private fun uninstallInstalledApp(
        packageName: String
    ) {
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

    // =====================================================
    // APK 下載
    // =====================================================

    private fun downloadApk(
        url: String,
        fileName: String
    ) {

        if (!authorized) return

        if (isSpecialApk(fileName) &&
            !specialAuthorized
        ) {
            Toast.makeText(
                this,
                "此軟體尚未取得特殊軟體授權",
                Toast.LENGTH_LONG
            ).show()
            return
        }

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

            if (activeDownloads.containsKey(url)) {
                Toast.makeText(
                    this,
                    "這個 APK 正在下載中",
                    Toast.LENGTH_SHORT
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

                            val uri =
                                manager.getUriForDownloadedFile(id)

                            if (uri != null) {
                                openApkInstaller(uri)
                            }
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

    private fun saveApkPackageName(
        fileName: String
    ) {
        try {
            val apkFile = File(
                getExternalFilesDir(
                    Environment.DIRECTORY_DOWNLOADS
                ),
                File(fileName).name
            )

            if (!apkFile.exists()) return

            val packageInfo =
                packageManager.getPackageArchiveInfo(
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

    private fun sendProgress(
        url: String,
        percent: Int,
        state: String
    ) {
        val safeUrl = JSONObject.quote(url)
        val safeState = JSONObject.quote(state)

        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.onApkDownloadProgress && " +
                        "window.onApkDownloadProgress(" +
                        "$safeUrl,$percent,$safeState);",
                    null
                )
            }
        }
    }

    // =====================================================
    // APK 安裝
    // =====================================================

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

            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

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

    // =====================================================
    // 返回應用市場
    // =====================================================

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

                if (::webView.isInitialized && authorized) {
                    webView.evaluateJavascript(
                        "window.refreshInstalledState && " +
                            "window.refreshInstalledState();",
                        null
                    )
                }

            }, 1500)
        }
    }

    // =====================================================
    // 遙控器返回鍵
    // =====================================================

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {

        if (keyCode == KeyEvent.KEYCODE_BACK) {

            if (::webView.isInitialized &&
                webView.canGoBack()
            ) {
                webView.goBack()
            } else {
                finish()
            }

            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    // =====================================================
    // 關閉程式
    // =====================================================

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        authorized = false
        specialAuthorized = false

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroy()
    }
}
