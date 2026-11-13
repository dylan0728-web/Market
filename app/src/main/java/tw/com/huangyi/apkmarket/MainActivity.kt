package tw.com.huangyi.apkmarket

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    private val handler = Handler(Looper.getMainLooper())

    private var pendingInstallUri: Uri? = null
    private var pendingInstallCheck = false

    private val activeDownloads = mutableSetOf<String>()

    @Volatile
    private var marketUpdateCheckStarted = false

    @Volatile
    private var marketUpdateDownloading = false

    @Volatile
    private var authorized = false

    @Volatile
    private var specialAuthorized = false

    @Volatile
    private var specialAccessLevel = ""

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

        private const val MEGA_AUTH_FOLDER_URL =
            "https://mega.nz/folder/0lFhiRBT#QKMgWwfILTnEDGAqgxG8IQ"

        private const val AUTH_ERROR =
            "此機器MAC授權到期或是未授權"

        private const val MARKET_UPDATE_API =
            "https://api.github.com/repos/dylan0728-web/Market/releases/tags/market-update"

        private const val MARKET_UPDATE_PREFIX =
            "https://github.com/dylan0728-web/Market/releases/download/"

        private const val MARKET_UPDATE_ASSET =
            "HY.apk"

        private const val MARKET_UPDATE_FILE =
            "HY應用市場.apk"

        private const val ACTION_UNINSTALL_RESULT =
            "tw.com.huangyi.apkmarket.UNINSTALL_RESULT"

        private const val ACTION_INSTALL_RESULT =
            "tw.com.huangyi.apkmarket.INSTALL_RESULT"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val authorizationHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleUninstallResult(intent)
        handleInstallResult(intent)

        authorized = false
        specialAuthorized = false
        specialAccessLevel = ""
        authorizationDeviceMac = ""
        authorizationErrorMessage = "正在檢查授權"

        showAuthorizationLoading()
        checkCloudAuthorization()
    }

    // 開啟時只顯示深色背景，不顯示任何載入或授權文字
    private fun showAuthorizationLoading() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF101820.toInt())
        }

        setContentView(layout)
    }

    // 背景授權檢查，不顯示進度文字
    private fun updateAuthorizationLoading(message: String) {
        // 保留呼叫位置，避免影響原有授權檢查流程
    }

    private fun showAuthorizationDenied() {
        authorized = false
        specialAuthorized = false
        specialAccessLevel = ""

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
            text = "此機器Mac未授權"
            textSize = 32f
            setTextColor(0xFFFF3333.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(10, 10, 10, 25)
        }

        val detail = TextView(this).apply {
            text = "機器Mac：$mac"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(10, 10, 10, 15)
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

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    authorized = true
                    specialAccessLevel = result.second
                    specialAuthorized = specialAccessLevel.isNotBlank()
                    authorizationErrorMessage = ""
                    showMarket()
                }

            } catch (e: Exception) {
                authorized = false
                specialAuthorized = false
                specialAccessLevel = ""
                authorizationErrorMessage = e.message ?: "未知授權錯誤"

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showAuthorizationDenied()
                    }
                }
            }
        }.start()
    }

    // =====================================================
    // MEGA 公開資料夾：尋找並下載最新 USER.txt
    // =====================================================

    private fun downloadMegaAuthorizationText(): String {
        val linkParts = MEGA_AUTH_FOLDER_URL
            .substringAfter("/folder/", "")
            .split("#", limit = 2)

        if (linkParts.size != 2 || linkParts[0].isBlank()) {
            throw Exception("MEGA 權限資料夾連結格式錯誤")
        }

        val folderHandle = linkParts[0].trim()
        val folderKey = decodeMegaBase64(linkParts[1].trim())

        if (folderKey.size != 16) {
            throw Exception("MEGA 資料夾金鑰格式錯誤")
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val listApiUrl = "https://g.api.mega.co.nz/cs?id=${System.currentTimeMillis()}&n=$folderHandle"

        val listCommand = JSONArray().put(
            JSONObject().put("a", "f").put("c", 1).put("r", 1).put("ca", 1)
        )

        val listRequest = Request.Builder()
            .url(listApiUrl)
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header("User-Agent", "HY-App-Market-Authorization/1.0")
            .post(listCommand.toString().toRequestBody(mediaType))
            .build()

        val nodes = authorizationHttpClient.newCall(listRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("MEGA 權限資料夾讀取失敗，HTTP ${response.code}")
            }

            val body = response.body?.string()
                ?: throw Exception("MEGA 權限資料夾沒有回應")

            val result = try {
                JSONArray(body)
            } catch (e: Exception) {
                throw Exception("MEGA 資料夾清單格式錯誤：${e.message}")
            }

            val first = result.opt(0)

            if (first is Number) {
                throw Exception("MEGA 資料夾讀取錯誤，代碼：${first.toInt()}")
            }

            val root = result.optJSONObject(0)
                ?: throw Exception("MEGA 沒有回傳權限資料夾內容")

            root.optJSONArray("f")
                ?: throw Exception("MEGA 權限資料夾檔案清單不存在")
        }

        var userNode: JSONObject? = null
        var userNodeKey: ByteArray? = null
        var newestUserTimestamp = Long.MIN_VALUE

        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (node.optInt("t", -1) != 0) continue

            val encryptedAttributes = node.optString("a", "")
            val compoundKey = node.optString("k", "")

            if (encryptedAttributes.isBlank() || compoundKey.isBlank()) continue

            val encryptedNodeKey = try {
                decodeMegaBase64(compoundKey.substringAfterLast(":", compoundKey))
            } catch (e: Exception) {
                continue
            }

            val decryptedNodeKey = try {
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(folderKey, "AES")
                )
                cipher.doFinal(encryptedNodeKey)
            } catch (e: Exception) {
                continue
            }

            if (decryptedNodeKey.size != 32) continue

            val words = ByteBuffer.wrap(decryptedNodeKey).order(ByteOrder.BIG_ENDIAN)
            val keyWords = IntArray(8) { words.int }

            val fileAesKey = ByteBuffer.allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(keyWords[0] xor keyWords[4])
                .putInt(keyWords[1] xor keyWords[5])
                .putInt(keyWords[2] xor keyWords[6])
                .putInt(keyWords[3] xor keyWords[7])
                .array()

            val attributes = try {
                val encrypted = decodeMegaBase64(encryptedAttributes)
                if (encrypted.isEmpty() || encrypted.size % 16 != 0) continue

                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(fileAesKey, "AES"),
                    IvParameterSpec(ByteArray(16))
                )

                String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                continue
            }

            if (!attributes.startsWith("MEGA")) continue

            val fileName = try {
                JSONObject(attributes.substring(4).trimEnd('\u0000'))
                    .optString("n", "")
            } catch (e: Exception) {
                ""
            }

            if (fileName.equals("USER.txt", ignoreCase = true)) {
                val nodeTimestamp = node.optLong("ts", 0L)

                if (userNode == null || nodeTimestamp > newestUserTimestamp) {
                    userNode = node
                    userNodeKey = decryptedNodeKey
                    newestUserTimestamp = nodeTimestamp
                }
            }
        }

        val targetNode = userNode
            ?: throw Exception("MEGA 權限資料夾內找不到 USER.txt")

        val nodeKey = userNodeKey
            ?: throw Exception("USER.txt 金鑰不存在，無法解密")

        val nodeHandle = targetNode.optString("h", "")

        if (nodeHandle.isBlank()) {
            throw Exception("USER.txt 節點代碼不存在")
        }

        val downloadCommand = JSONArray().put(
            JSONObject().put("a", "g").put("g", 1).put("n", nodeHandle)
        )

        val downloadApiUrl = "https://g.api.mega.co.nz/cs?id=${System.currentTimeMillis()}&n=$folderHandle"

        val downloadApiRequest = Request.Builder()
            .url(downloadApiUrl)
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header("User-Agent", "HY-App-Market-Authorization/1.0")
            .post(downloadCommand.toString().toRequestBody(mediaType))
            .build()

        val downloadInfo = authorizationHttpClient.newCall(downloadApiRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("MEGA USER.txt 下載資訊取得失敗，HTTP ${response.code}")
            }

            val body = response.body?.string()
                ?: throw Exception("MEGA USER.txt 下載資訊沒有回應")

            val array = try {
                JSONArray(body)
            } catch (e: Exception) {
                throw Exception("MEGA USER.txt 下載資訊格式錯誤：${e.message}")
            }

            val item = array.optJSONObject(0)
                ?: throw Exception("MEGA USER.txt 不存在或無法存取")

            val url = item.optString("g", "")
            val size = item.optLong("s", -1L)

            if (url.isBlank() || size < 0) {
                throw Exception("MEGA USER.txt 下載網址或檔案大小無效")
            }

            Pair(url, size)
        }

        val secureUrl = if (downloadInfo.first.startsWith("http://", true)) {
            downloadInfo.first.replaceFirst(
                Regex("^http://", RegexOption.IGNORE_CASE),
                "https://"
            )
        } else {
            downloadInfo.first
        }

        val fileRequest = Request.Builder()
            .url(secureUrl)
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header("User-Agent", "HY-App-Market-Authorization/1.0")
            .get()
            .build()

        val encryptedData = authorizationHttpClient.newCall(fileRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("MEGA USER.txt 下載失敗，HTTP ${response.code}")
            }

            response.body?.bytes()
                ?: throw Exception("MEGA USER.txt 內容為空")
        }

        if (encryptedData.size.toLong() != downloadInfo.second) {
            throw Exception(
                "MEGA USER.txt 檔案大小不符，預期 ${downloadInfo.second} bytes，實際 ${encryptedData.size} bytes"
            )
        }

        val words = ByteBuffer.wrap(nodeKey).order(ByteOrder.BIG_ENDIAN)
        val keyWords = IntArray(8) { words.int }

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

        val contentCipher = Cipher.getInstance("AES/CTR/NoPadding")
        contentCipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            IvParameterSpec(iv)
        )

        val plainBytes = try {
            contentCipher.doFinal(encryptedData)
        } catch (e: Exception) {
            throw Exception("MEGA USER.txt 內容解密失敗：${e.message}")
        }

        return String(plainBytes, StandardCharsets.UTF_8)
    }

    private fun decodeMegaBase64(value: String): ByteArray {
        return try {
            Base64.decode(
                value,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
        } catch (e: Exception) {
            throw Exception("MEGA 金鑰或資料解碼失敗：${e.message}")
        }
    }

    // =====================================================
    // 授權比對：第三欄支援空白、S、SSS
    // =====================================================

    private fun verifyAuthorization(
        content: String,
        deviceMac: String
    ): Pair<Boolean, String> {

        val normalizedDeviceMac = normalizeMacAddress(deviceMac)

        if (normalizedDeviceMac.isBlank()) {
            authorizationErrorMessage =
                "機器 MAC 標準化失敗。\n原始 MAC：${deviceMac.take(80)}"
            return Pair(false, "")
        }

        val cleanContent = cleanAuthorizationText(content)

        if (cleanContent.isBlank()) {
            authorizationErrorMessage = "授權檔解密後內容為空。"
            return Pair(false, "")
        }

        var foundAuthorization = false
        var foundSpecialLevel = ""
        var matchingMacFound = false
        var matchingMacDateError = ""
        var matchingMacExpired = ""

        val diagnosticRows = mutableListOf<String>()
        val lines = cleanContent.lineSequence().toList()

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

            val dateText = cleanAuthorizationText(columns[0]).trim()
            val rawMac = cleanAuthorizationText(columns[1]).trim()
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
                        "第 ${index + 1} 行：MAC 不相符，授權 MAC=$macText，機器 MAC=$normalizedDeviceMac"
                    )
                }
                continue
            }

            matchingMacFound = true

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

            val expiryCalendar = Calendar.getInstance().apply {
                isLenient = false
                clear()
                set(year, month - 1, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }

            try {
                expiryCalendar.timeInMillis
            } catch (e: Exception) {
                matchingMacDateError =
                    "MAC 已相符，但日期不是有效日曆日期：[$dateText]"
                continue
            }

            expiryCalendar.add(Calendar.DAY_OF_MONTH, 1)

            if (!Calendar.getInstance().before(expiryCalendar)) {
                matchingMacExpired = "MAC 已相符，但授權已到期：$dateText"
                continue
            }

            foundAuthorization = true

            val flag = if (columns.size >= 3) {
                cleanAuthorizationText(columns[2]).trim().uppercase(Locale.ROOT)
            } else {
                ""
            }

            when (flag) {
                "SSS" -> foundSpecialLevel = "SSS"
                "S" -> {
                    if (foundSpecialLevel != "SSS") {
                        foundSpecialLevel = "S"
                    }
                }
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

        return Pair(foundAuthorization, foundSpecialLevel)
    }

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

        val macBuilder = StringBuilder(12)

        for (char in cleaned) {
            when {
                char in '0'..'9' || char in 'A'..'F' -> macBuilder.append(char)
                char == ':' || char == '-' || char == '.' || char.isWhitespace() -> Unit
                else -> return ""
            }
        }

        val mac = macBuilder.toString()

        if (mac.length != 12) return ""
        if (!mac.matches(Regex("[0-9A-F]{12}"))) return ""
        if (mac == "000000000000") return ""
        if (mac == "020000000000") return ""

        return mac
    }

    private fun readHardwareMac(): String {
        val preferredNames = listOf("eth0", "wlan0", "wlan1", "en0")

        for (name in preferredNames) {
            try {
                val addressFile = File("/sys/class/net/$name/address")

                if (addressFile.exists() && addressFile.canRead()) {
                    val mac = normalizeMacAddress(addressFile.readText().trim())
                    if (mac.isNotBlank()) return mac
                }
            } catch (e: Exception) {
            }
        }

        try {
            val networkFiles = File("/sys/class/net").listFiles()

            if (networkFiles != null) {
                val excludedPrefixes = listOf(
                    "lo", "dummy", "tun", "tap", "rmnet",
                    "p2p", "sit", "ip6tnl", "veth", "docker", "br-"
                )

                val candidates = networkFiles
                    .filter { file ->
                        val name = file.name.lowercase(Locale.ROOT)
                        !preferredNames.contains(name) &&
                            excludedPrefixes.none { name.startsWith(it) }
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
                        val addressFile = File(networkFile, "address")
                        if (!addressFile.exists() || !addressFile.canRead()) continue

                        val mac = normalizeMacAddress(addressFile.readText().trim())
                        if (mac.isNotBlank()) return mac
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
        }

        try {
            val enumeration = NetworkInterface.getNetworkInterfaces()

            if (enumeration != null) {
                val allInterfaces = mutableListOf<NetworkInterface>()

                while (enumeration.hasMoreElements()) {
                    allInterfaces.add(enumeration.nextElement())
                }

                val excludedPrefixes = listOf(
                    "lo", "dummy", "tun", "tap", "rmnet",
                    "p2p", "sit", "ip6tnl", "veth", "docker", "br-"
                )

                val candidates = allInterfaces
                    .filter { networkInterface ->
                        val name = networkInterface.name.lowercase(Locale.ROOT)

                        try {
                            !networkInterface.isLoopback &&
                                !networkInterface.isVirtual &&
                                excludedPrefixes.none { name.startsWith(it) }
                        } catch (e: Exception) {
                            false
                        }
                    }
                    .sortedBy { networkInterface ->
                        val name = networkInterface.name.lowercase(Locale.ROOT)
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
                    }
                }
            }
        } catch (e: Exception) {
        }

        return ""
    }

    // =====================================================
    // 建立應用市場：不顯示載入文字，直接載入網頁
    // =====================================================

    private fun showMarket() {
        if (!authorized || normalizeMacAddress(authorizationDeviceMac).isBlank()) {
            authorized = false
            specialAuthorized = false
            specialAccessLevel = ""
            showAuthorizationDenied()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101820.toInt())
        }

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false

        webView.addJavascriptInterface(AndroidMarketBridge(), "AndroidMarket")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()

        layout.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        setContentView(layout)
        webView.loadUrl(MARKET_URL)

        checkMarketUpdate()
    }

    // =====================================================
    // HY 市場自動更新檢查
    // =====================================================

    private fun getInstalledMarketVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun checkMarketUpdate() {
        if (!authorized || marketUpdateCheckStarted) return

        marketUpdateCheckStarted = true

        Thread {
            try {
                val freshApiUrl =
                    "$MARKET_UPDATE_API?check=${System.currentTimeMillis()}"

                val request = Request.Builder()
                    .url(freshApiUrl)
                    .get()
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Cache-Control", "no-cache, no-store")
                    .header("Pragma", "no-cache")
                    .header("User-Agent", "HY-App-Market")
                    .build()

                val updateInfo = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("GitHub 更新資訊 HTTP ${response.code}")
                    }

                    val body = response.body?.string()
                        ?: throw IOException("GitHub 沒有回傳更新資訊")

                    val release = JSONObject(body)

                    if (release.optBoolean("draft", false)) {
                        throw IOException("更新版本尚未正式發布")
                    }

                    val releaseBody = release.optString("body", "")

                    val versionCodeMatch = Regex(
                        "(?im)^\\s*versionCode\\s*=\\s*(\\d+)\\s*$"
                    ).find(releaseBody)

                    val versionNameMatch = Regex(
                        "(?im)^\\s*versionName\\s*=\\s*(.+?)\\s*$"
                    ).find(releaseBody)

                    val dateMatch = Regex(
                        "(?im)^\\s*date\\s*=\\s*(\\d{4}/\\d{2}/\\d{2})\\s*$"
                    ).find(releaseBody)

                    val remoteVersionCode =
                        versionCodeMatch?.groupValues?.get(1)?.toLongOrNull()
                            ?: throw IOException("更新版本號格式錯誤")

                    val remoteVersionName =
                        versionNameMatch?.groupValues?.get(1)?.trim()
                            ?: throw IOException("更新版本名稱不存在")

                    val remoteDate =
                        dateMatch?.groupValues?.get(1)
                            ?: "版本日期未提供"

                    val assets = release.optJSONArray("assets")
                        ?: throw IOException("更新 APK 檔案清單不存在")

                    var apkUrl = ""

                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue

                        if (
                            asset.optString("name") == MARKET_UPDATE_ASSET &&
                            asset.optLong("size", 0L) > 0L
                        ) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }

                    if (
                        apkUrl.isBlank() ||
                        !apkUrl.startsWith(MARKET_UPDATE_PREFIX)
                    ) {
                        throw IOException("找不到正確的市場更新 APK")
                    }

                    val installedVersionCode = getInstalledMarketVersionCode()

                    if (remoteVersionCode > installedVersionCode) {
                        Triple(
                            remoteVersionName,
                            remoteDate,
                            apkUrl
                        )
                    } else {
                        null
                    }
                }

                if (updateInfo != null) {
                    runOnUiThread {
                        if (
                            isFinishing ||
                            isDestroyed ||
                            !authorized
                        ) return@runOnUiThread

                        showMarketUpdateDialog(
                            updateInfo.first,
                            updateInfo.second,
                            updateInfo.third
                        )
                    }
                }

            } catch (e: Exception) {
                // 更新檢查失敗不影響市場正常使用
            }
        }.start()
    }

    private fun showMarketUpdateDialog(
        versionName: String,
        versionDate: String,
        apkUrl: String
    ) {
        if (!authorized || isFinishing || isDestroyed) return

        AlertDialog.Builder(this)
            .setTitle("發現應用市場新版")
            .setMessage(
                "最新版本：$versionName\n" +
                    "版本日期：$versionDate\n\n" +
                    "是否立即更新應用市場？"
            )
            .setPositiveButton("立即更新") { _, _ ->
                downloadMarketUpdate(apkUrl)
            }
            .setNegativeButton("稍後更新") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun downloadMarketUpdate(apkUrl: String) {
        if (!authorized) return

        if (!apkUrl.startsWith(MARKET_UPDATE_PREFIX)) {
            Toast.makeText(
                this,
                "市場更新網址不正確",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (marketUpdateDownloading) return

        marketUpdateDownloading = true

        Toast.makeText(
            this,
            "正在下載應用市場更新，請稍候...",
            Toast.LENGTH_LONG
        ).show()

        Thread {
            val downloadDirectory =
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir

            val apkFile = File(downloadDirectory, MARKET_UPDATE_FILE)
            val tempFile = File(downloadDirectory, "$MARKET_UPDATE_FILE.part")

            try {
                downloadWithRetry(
                    apkUrl,
                    tempFile,
                    MARKET_UPDATE_FILE
                )

                if (tempFile.length() <= 0L) {
                    throw IOException("市場更新檔案是空的")
                }

                if (apkFile.exists() && !apkFile.delete()) {
                    throw IOException("無法覆蓋舊的市場更新檔")
                }

                if (!tempFile.renameTo(apkFile)) {
                    tempFile.copyTo(apkFile, overwrite = true)
                    tempFile.delete()
                }

                if (!apkFile.exists() || apkFile.length() <= 0L) {
                    throw IOException("市場更新檔儲存失敗")
                }

                val archiveInfo = packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    0
                ) ?: throw IOException("下載檔案不是有效的 APK")

                if (archiveInfo.packageName != packageName) {
                    throw IOException("更新 APK 套件名稱不符，已停止安裝")
                }

                val uri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    apkFile
                )

                runOnUiThread {
                    marketUpdateDownloading = false

                    if (!authorized || isFinishing || isDestroyed) return@runOnUiThread

                    Toast.makeText(
                        this,
                        "市場更新檔下載完成，準備安裝",
                        Toast.LENGTH_SHORT
                    ).show()

                    openApkInstaller(uri)
                }

            } catch (e: Exception) {
                tempFile.delete()

                runOnUiThread {
                    marketUpdateDownloading = false

                    Toast.makeText(
                        this,
                        "市場更新下載失敗：${e.message ?: "未知錯誤"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    // =====================================================
    // JavaScript 與 Android 溝通
    // =====================================================

    inner class AndroidMarketBridge {

        @JavascriptInterface
        fun isAuthorized(): Boolean = authorized

        @JavascriptInterface
        fun hasSpecialAccess(): Boolean = authorized && specialAccessLevel.isNotBlank()

        @JavascriptInterface
        fun getSpecialAccessLevel(): String {
            return if (authorized) specialAccessLevel else ""
        }

        @JavascriptInterface
        fun getDeviceMac(): String {
            return authorizationDeviceMac.ifBlank { readHardwareMac() }
        }

        @JavascriptInterface
        fun getAppVersionName(): String {
            return try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
            } catch (e: Exception) {
                "未知"
            }
        }

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

                    if (!canAccessApk(fileName)) continue

                    var packageName = prefs.getString("package_$fileName", "") ?: ""
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
                        ).map { normalizeName(it) }.filter { it.isNotBlank() }

                        for (info in installedPackages) {
                            val label = try {
                                packageManager.getApplicationLabel(
                                    info.applicationInfo!!
                                ).toString()
                            } catch (e: Exception) {
                                ""
                            }

                            if (
                                normalizeName(label).isNotBlank() &&
                                candidates.contains(normalizeName(label))
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
                    if (authorized && ::webView.isInitialized) {
                        webView.evaluateJavascript(
                            "window.onApkMetadata && window.onApkMetadata($output);",
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
        fun checkInstalled(packageName: String, url: String) {
            runOnUiThread {
                val installed = isPackageInstalled(packageName)
                val safeUrl = JSONObject.quote(url)
                val safePackage = JSONObject.quote(packageName)

                webView.evaluateJavascript(
                    "window.onAppCheckResult && window.onAppCheckResult($safeUrl,$installed,$safePackage);",
                    null
                )
            }
        }

        @JavascriptInterface
        fun startDownload(url: String, fileName: String) {
            runOnUiThread {
                if (!canAccessApk(fileName)) {
                    Toast.makeText(
                        this@MainActivity,
                        "無法下載此應用程式",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }

                downloadApk(url, fileName)
            }
        }

        @JavascriptInterface
        fun launchApp(packageName: String) {
            runOnUiThread { launchInstalledApp(packageName) }
        }

        @JavascriptInterface
        fun showInstalledActions(
            packageName: String,
            fileName: String,
            url: String,
            appName: String
        ) {
            runOnUiThread {
                this@MainActivity.showInstalledActions(
                    packageName,
                    fileName,
                    url,
                    appName
                )
            }
        }

        @JavascriptInterface
        fun uninstallApp(packageName: String) {
            runOnUiThread { uninstallInstalledApp(packageName) }
        }

        @JavascriptInterface
        fun reinstallApp(packageName: String, fileName: String, url: String) {
            runOnUiThread { reinstallInstalledApp(packageName, fileName, url) }
        }
    }

    // =====================================================
    // APK 權限判斷
    // =====================================================

    private fun requiredSpecialLevel(fileName: String): String {
        val name = fileName.trim()

        return when {
            name.endsWith("_SSS.apk", ignoreCase = true) -> "SSS"
            name.endsWith("_S.apk", ignoreCase = true) -> "S"
            else -> ""
        }
    }

    private fun canAccessApk(fileName: String): Boolean {
        if (!authorized) return false

        return when (requiredSpecialLevel(fileName)) {
            "SSS" -> specialAccessLevel == "SSS"
            "S" -> specialAccessLevel == "S" || specialAccessLevel == "SSS"
            else -> true
        }
    }

    private fun normalizeName(value: String): String {
        return value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }

    // =====================================================
    // 已安裝軟體：美化操作視窗
    // =====================================================

    private fun showInstalledActions(
        packageName: String,
        fileName: String,
        url: String,
        appName: String
    ) {
        if (!authorized) {
            Toast.makeText(
                this,
                "目前設備尚未取得市場授權",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (packageName.isBlank() || fileName.isBlank()) {
            Toast.makeText(
                this,
                "應用程式資料不完整",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!canAccessApk(fileName)) {
            Toast.makeText(
                this,
                "目前設備沒有此 APK 的使用權限",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!isPackageInstalled(packageName)) {
            Toast.makeText(
                this,
                "找不到已安裝的應用程式，正在更新清單",
                Toast.LENGTH_SHORT
            ).show()
            refreshInstalledState()
            return
        }

        val density = resources.displayMetrics.density

        fun dp(value: Int): Int {
            return (value * density).toInt()
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF17232F.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF34495E.toInt())
            }
        }

        val title = TextView(this).apply {
            text = appName.ifBlank { "應用程式操作" }
            textSize = 23f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(5))
        }

        val subtitle = TextView(this).apply {
            text = "請選擇要執行的操作"
            textSize = 15f
            setTextColor(0xFF9EADB9.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(18))
        }

        layout.addView(title)
        layout.addView(subtitle)

        val dialog = AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(true)
            .create()

        fun addAction(
            text: String,
            color: Int,
            action: () -> Unit
        ) {
            val button = TextView(this).apply {
                this.text = text
                textSize = 19f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                minimumHeight = dp(54)
                setPadding(dp(12), dp(8), dp(12), dp(8))

                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), 0xFF526779.toInt())
                }

                setOnFocusChangeListener { view, hasFocus ->
                    view.scaleX = if (hasFocus) 1.03f else 1f
                    view.scaleY = if (hasFocus) 1.03f else 1f

                    view.background =
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(
                                if (hasFocus) {
                                    0xFF36536B.toInt()
                                } else {
                                    color
                                }
                            )
                            cornerRadius = dp(10).toFloat()
                            setStroke(
                                dp(if (hasFocus) 2 else 1),
                                if (hasFocus) {
                                    0xFF65C8FF.toInt()
                                } else {
                                    0xFF526779.toInt()
                                }
                            )
                        }
                }

                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                bottomMargin = dp(10)
            }

            layout.addView(button, params)
        }

        addAction("市場開啟", 0xFF245B80.toInt()) {
            launchInstalledApp(packageName)
        }

        addAction("重裝", 0xFF286B55.toInt()) {
            reinstallInstalledApp(packageName, fileName, url)
        }

        addAction("卸載", 0xFF853C42.toInt()) {
            uninstallInstalledApp(packageName)
        }

        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF17232F.toInt())
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), 0xFF34495E.toInt())
                }
            )

            setLayout(
                dp(420),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        handler.post {
            if (dialog.isShowing && layout.childCount > 2) {
                layout.getChildAt(2).requestFocus()
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
                startActivity(intent)
            } else {
                Toast.makeText(
                    this,
                    "此軟體沒有可啟動的畫面",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "無法開啟此軟體", Toast.LENGTH_SHORT).show()
        }
    }

    // =====================================================
    // 使用 PackageInstaller 正式解除安裝
    // =====================================================

    private fun openAppUninstallConfirmation(
        targetPackageName: String,
        reinstallUri: Uri? = null
    ) {
        try {
            val callbackIntent = Intent(
                this,
                MainActivity::class.java
            ).apply {
                action = ACTION_UNINSTALL_RESULT
                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }

            prefs.edit()
                .putString("pending_uninstall_package", targetPackageName)
                .putString(
                    "pending_reinstall_uri",
                    reinstallUri?.toString() ?: ""
                )
                .putBoolean("pending_is_reinstall", reinstallUri != null)
                .apply()

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }

            val callback = PendingIntent.getActivity(
                this,
                targetPackageName.hashCode(),
                callbackIntent,
                flags
            )

            packageManager.packageInstaller.uninstall(
                targetPackageName,
                callback.intentSender
            )

            Toast.makeText(
                this,
                if (reinstallUri != null) {
                    "請在系統畫面確認解除安裝，完成後會接續安裝新版"
                } else {
                    "請在系統畫面確認解除安裝"
                },
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            clearPendingUninstall()

            Toast.makeText(
                this,
                "無法啟動解除安裝：${e.message ?: "未知錯誤"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun uninstallInstalledApp(packageName: String) {
        if (packageName.isBlank()) return

        if (!isPackageInstalled(packageName)) {
            Toast.makeText(
                this,
                "此應用程式已經卸載",
                Toast.LENGTH_SHORT
            ).show()
            refreshInstalledState()
            return
        }

        openAppUninstallConfirmation(packageName)
    }

    // =====================================================
    // 重裝 APP：先下載，再使用 PackageInstaller 卸載
    // =====================================================

    private fun reinstallInstalledApp(
        packageName: String,
        fileName: String,
        url: String
    ) {
        if (packageName.isBlank() || fileName.isBlank()) return

        if (!canAccessApk(fileName)) {
            Toast.makeText(this, "目前設備沒有此 APK 的使用權限", Toast.LENGTH_LONG).show()
            return
        }

        val safeName = File(fileName).name
        if (!safeName.endsWith(".apk", true)) {
            Toast.makeText(this, "APK 檔名不正確", Toast.LENGTH_LONG).show()
            return
        }

        val downloadDirectory =
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val apkFile = File(downloadDirectory, safeName)

        if (apkFile.exists() && apkFile.length() > 0L) {
            beginReinstall(packageName, apkFile)
            return
        }

        if (!url.startsWith(
                "https://github.com/dylan0728-web/Market/releases/download/"
            )) {
            Toast.makeText(this, "重裝下載網址不正確", Toast.LENGTH_LONG).show()
            return
        }

        if (activeDownloads.contains(url)) {
            Toast.makeText(this, "此 APK 正在下載中，請稍候", Toast.LENGTH_SHORT).show()
            return
        }

        activeDownloads.add(url)
        Thread {
            val tempFile = File(downloadDirectory, "$safeName.part")
            try {
                downloadWithRetry(url, tempFile, safeName)
                if (tempFile.length() <= 0L) throw IOException("下載檔案是空的")
                if (apkFile.exists() && !apkFile.delete()) {
                    throw IOException("無法覆蓋舊 APK 檔案")
                }
                if (!tempFile.renameTo(apkFile)) {
                    tempFile.copyTo(apkFile, overwrite = true)
                    tempFile.delete()
                }
                if (!apkFile.exists() || apkFile.length() <= 0L) {
                    throw IOException("APK 儲存失敗")
                }
                saveApkPackageName(safeName)

                runOnUiThread {
                    activeDownloads.remove(url)
                    beginReinstall(packageName, apkFile)
                }
            } catch (e: Exception) {
                tempFile.delete()
                runOnUiThread {
                    activeDownloads.remove(url)
                    Toast.makeText(
                        this,
                        "重裝檔案下載失敗：${e.message ?: "未知錯誤"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    // 重裝流程：先確認 APK 正確，再決定安裝或卸載後重裝
    private fun beginReinstall(packageName: String, apkFile: File) {
        try {
            val archiveInfo = packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                0
            ) ?: throw IOException("下載檔案不是有效的 APK")

            if (archiveInfo.packageName != packageName) {
                throw IOException(
                    "APK 套件名稱不符：預期 $packageName，實際 ${archiveInfo.packageName}"
                )
            }

            val apkUri = FileProvider.getUriForFile(
                this,
                "${this.packageName}.fileprovider",
                apkFile
            )

            if (!isPackageInstalled(packageName)) {
                openApkInstaller(apkUri)
                return
            }

            openAppUninstallConfirmation(packageName, apkUri)

        } catch (e: Exception) {
            clearPendingUninstall()

            Toast.makeText(
                this,
                "無法開啟重裝流程：${e.message ?: "未知錯誤"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =====================================================
    // PackageInstaller 卸載結果回呼
    // =====================================================

    private fun clearPendingUninstall() {
        prefs.edit()
            .remove("pending_uninstall_package")
            .remove("pending_reinstall_uri")
            .remove("pending_is_reinstall")
            .apply()
    }

    private fun handleUninstallResult(intent: Intent?) {
        if (intent?.action != ACTION_UNINSTALL_RESULT) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    Intent.EXTRA_INTENT,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            }

            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(confirmIntent)
            } else {
                clearPendingUninstall()
                Toast.makeText(
                    this,
                    "無法開啟系統卸載確認畫面",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val reinstall = prefs.getBoolean(
            "pending_is_reinstall",
            false
        )

        val reinstallUriText = prefs.getString(
            "pending_reinstall_uri",
            ""
        ) ?: ""

        clearPendingUninstall()

        if (status == PackageInstaller.STATUS_SUCCESS) {
            if (reinstall && reinstallUriText.isNotBlank()) {
                val reinstallUri = Uri.parse(reinstallUriText)

                handler.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        try {
                            openApkInstaller(reinstallUri)
                        } catch (e: Exception) {
                            Toast.makeText(
                                this,
                                "無法開啟新版安裝畫面：${e.message ?: "未知錯誤"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }, 1000L)
            } else {
                Toast.makeText(
                    this,
                    "卸載成功",
                    Toast.LENGTH_SHORT
                ).show()
            }

            refreshInstalledState()
        } else {
            val message = intent.getStringExtra(
                PackageInstaller.EXTRA_STATUS_MESSAGE
            ) ?: "使用者取消或系統無法完成卸載"

            Toast.makeText(
                this,
                "卸載未完成：$message",
                Toast.LENGTH_LONG
            ).show()

            refreshInstalledState()
        }
    }

    // =====================================================
    // 重裝專用安裝流程：保留舊函式，不再用於重裝
    // =====================================================

    private fun installApkWithoutOpenScreen(uri: Uri) {
        Thread {
            var sessionId = -1

            try {
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )

                val session = packageManager.packageInstaller
                sessionId = session.createSession(params)

                session.openSession(sessionId).use { installSession ->
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("無法讀取重裝 APK")

                    input.use { source ->
                        installSession.openWrite(
                            "HY_REINSTALL.apk",
                            0,
                            -1
                        ).use { output ->
                            source.copyTo(output)
                            installSession.fsync(output)
                        }
                    }

                    val callbackIntent = Intent(
                        this,
                        MainActivity::class.java
                    ).apply {
                        action = ACTION_INSTALL_RESULT
                        addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }

                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            PendingIntent.FLAG_MUTABLE
                        } else {
                            0
                        }

                    val callback = PendingIntent.getActivity(
                        this,
                        (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
                        callbackIntent,
                        flags
                    )

                    installSession.commit(callback.intentSender)
                }

            } catch (e: Exception) {
                if (sessionId >= 0) {
                    try {
                        packageManager.packageInstaller.abandonSession(sessionId)
                    } catch (ignored: Exception) {
                    }
                }

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(
                            this,
                            "新版安裝失敗：${e.message ?: "未知錯誤"}",
                            Toast.LENGTH_LONG
                        ).show()
                        refreshInstalledState()
                    }
                }
            }
        }.start()
    }

    private fun handleInstallResult(intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    Intent.EXTRA_INTENT,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            }

            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(confirmIntent)
            } else {
                Toast.makeText(
                    this,
                    "無法開啟系統安裝確認畫面",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            // 安裝成功不顯示底部 Toast 提示
        } else {
            val message = intent.getStringExtra(
                PackageInstaller.EXTRA_STATUS_MESSAGE
            ) ?: "系統無法完成安裝"

            Toast.makeText(
                this,
                "新版安裝未完成：$message",
                Toast.LENGTH_LONG
            ).show()
        }

        refreshInstalledState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUninstallResult(intent)
        handleInstallResult(intent)
    }

    // =====================================================
    // 更新網頁中的已安裝狀態
    // =====================================================

    private fun refreshInstalledState() {
        if (!::webView.isInitialized || !authorized) return

        handler.postDelayed({
            if (
                !isFinishing &&
                !isDestroyed &&
                ::webView.isInitialized &&
                authorized
            ) {
                webView.evaluateJavascript(
                    "window.refreshInstalledState && window.refreshInstalledState();",
                    null
                )
            }
        }, 500L)
    }

    // =====================================================
    // APK 下載：OkHttp 直接下載，避開 DownloadManager
    // =====================================================

    private fun downloadApk(url: String, fileName: String) {
        if (!canAccessApk(fileName)) {
            Toast.makeText(
                this,
                "無法下載此應用程式",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!url.startsWith(
                "https://github.com/dylan0728-web/Market/releases/download/"
            )) {
            Toast.makeText(this, "下載網址不正確", Toast.LENGTH_LONG).show()
            return
        }

        if (activeDownloads.contains(url)) {
            Toast.makeText(
                this,
                "這個 APK 正在下載中",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val safeName = File(fileName).name

        if (safeName.isBlank() || !safeName.endsWith(".apk", true)) {
            Toast.makeText(this, "APK 檔名不正確", Toast.LENGTH_LONG).show()
            return
        }

        if (!canAccessApk(safeName)) {
            Toast.makeText(this, "無法下載此應用程式", Toast.LENGTH_LONG).show()
            return
        }

        activeDownloads.add(url)
        sendProgress(url, 0, "downloading")

        Thread {
            val downloadDirectory =
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir

            val apkFile = File(downloadDirectory, safeName)
            val tempFile = File(downloadDirectory, "$safeName.part")

            try {
                downloadWithRetry(url, tempFile, safeName)

                if (tempFile.length() <= 0L) {
                    throw IOException("下載檔案是空的")
                }

                if (apkFile.exists() && !apkFile.delete()) {
                    throw IOException("無法覆蓋舊 APK 檔案")
                }

                if (!tempFile.renameTo(apkFile)) {
                    tempFile.copyTo(apkFile, overwrite = true)
                    tempFile.delete()
                }

                if (!apkFile.exists() || apkFile.length() <= 0L) {
                    throw IOException("APK 儲存失敗")
                }

                saveApkPackageName(safeName)

                val uri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    apkFile
                )

                runOnUiThread {
                    activeDownloads.remove(url)
                    sendProgress(url, 100, "complete")
                    openApkInstaller(uri)
                }

            } catch (e: Exception) {
                tempFile.delete()

                runOnUiThread {
                    activeDownloads.remove(url)
                    sendProgress(url, 0, "failed")

                    Toast.makeText(
                        this,
                        "下載失敗：$safeName\n${e.message ?: "未知錯誤"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    // =====================================================
    // OkHttp 下載及錯誤重試
    // =====================================================

    private fun downloadWithRetry(
        url: String,
        destination: File,
        fileName: String
    ) {
        var lastError: Exception? = null

        for (attempt in 1..3) {
            try {
                destination.delete()

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
                    )
                    .header("Accept", "application/octet-stream,*/*")
                    .header("Connection", "keep-alive")
                    .build()

                httpClient.newCall(request).execute().use { response ->

                    if (!response.isSuccessful) {
                        val code = response.code
                        val message = "HTTP $code"

                        if (
                            code == 502 ||
                            code == 503 ||
                            code == 504 ||
                            code == 408 ||
                            code == 429
                        ) {
                            throw RetryableDownloadException(message)
                        }

                        throw IOException("伺服器拒絕下載：$message")
                    }

                    val body = response.body
                        ?: throw IOException("伺服器沒有傳回檔案")

                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    var lastUpdateTime = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(64 * 1024)

                            while (true) {
                                if (Thread.currentThread().isInterrupted) {
                                    throw IOException("下載已中斷")
                                }

                                val count = input.read(buffer)

                                if (count == -1) break

                                output.write(buffer, 0, count)
                                downloadedBytes += count

                                val now = System.currentTimeMillis()

                                if (now - lastUpdateTime >= 500) {
                                    lastUpdateTime = now

                                    val percent = if (totalBytes > 0) {
                                        (downloadedBytes * 100L / totalBytes)
                                            .toInt()
                                            .coerceIn(0, 99)
                                    } else {
                                        0
                                    }

                                    sendProgress(
                                        url,
                                        percent,
                                        "downloading"
                                    )
                                }
                            }

                            output.flush()
                        }
                    }

                    if (destination.length() <= 0L) {
                        throw IOException("下載檔案是空的")
                    }

                    if (
                        totalBytes > 0 &&
                        destination.length() != totalBytes
                    ) {
                        throw RetryableDownloadException(
                            "檔案大小不完整：${destination.length()}/$totalBytes"
                        )
                    }
                }

                return

            } catch (e: RetryableDownloadException) {
                lastError = e
                destination.delete()

                if (attempt < 3) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "$fileName 下載連線異常，正在重試（$attempt/3）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    Thread.sleep(attempt * 1500L)
                }

            } catch (e: IOException) {
                lastError = e
                destination.delete()

                if (attempt < 3) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "$fileName 網路中斷，正在重試（$attempt/3）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    Thread.sleep(attempt * 1500L)
                }
            }
        }

        throw IOException(
            "重試 3 次仍無法下載：${lastError?.message ?: "未知錯誤"}",
            lastError
        )
    }

    private class RetryableDownloadException(
        message: String
    ) : IOException(message)

    // =====================================================
    // 儲存 APK 套件名稱
    // =====================================================

    private fun saveApkPackageName(fileName: String) {
        try {
            val apkFile = File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: filesDir,
                File(fileName).name
            )

            if (!apkFile.exists()) return

            val packageInfo = packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                0
            ) ?: return

            val apkPackageName = packageInfo.packageName

            if (apkPackageName.isNotBlank()) {
                prefs.edit()
                    .putString("package_$fileName", apkPackageName)
                    .apply()
            }
        } catch (e: Exception) {
        }
    }

    private fun sendProgress(url: String, percent: Int, state: String) {
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
    // 一般 APK 安裝
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
            refreshInstalledState()
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
            if (::webView.isInitialized && webView.canGoBack()) {
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
        specialAccessLevel = ""

        if (::webView.isInitialized) {
            webView.destroy()
        }

        super.onDestroy()
    }
}
