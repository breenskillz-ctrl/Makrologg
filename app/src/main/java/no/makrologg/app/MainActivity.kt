package no.makrologg.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        /** Raise when the bridge below gains features the web page depends on. */
        const val NATIVE_API = 5
        const val REPO = "breenskillz-ctrl/Makrologg"
        const val WEB_URL = "https://raw.githubusercontent.com/$REPO/main/app/src/main/assets/index.html"
        const val RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val BASE_URL = "file:///android_asset/"
    }

    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()
    private val net = Executors.newFixedThreadPool(3)
    private var updateApkUrl: String? = null
    private val cachedHtml get() = File(filesDir, "web/index.html")

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val code = result.contents
        js("window.onNativeBarcode && window.onNativeBarcode(${if (code != null) JSONObject.quote(code) else "null"})")
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        js("window.onNotifPermission && window.onNotifPermission(${JSONObject.quote(notifState())})")
    }

    private fun notifState(): String = if (Reminders.canNotify(this)) "granted" else "off"

    /* notification taps: deliver to the page once it has loaded */
    private var pageReady = false
    private var pendingCall: String? = null

    private fun handleReminderIntent(i: Intent?) {
        val slot = i?.getStringExtra("slot") ?: return
        val day = i.getStringExtra("day") ?: ""
        i.removeExtra("slot")
        val call = "window.onReminderOpen && window.onReminderOpen(${JSONObject.quote(slot)}, ${JSONObject.quote(day)})"
        if (pageReady) js(call) else pendingCall = call
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private val versionCode: Long by lazy {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
    }
    private val versionName: String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            textZoom = 100
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "file" || url.scheme == "data" || url.scheme == "about") return false
                startActivity(Intent(Intent.ACTION_VIEW, url))
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true
                updateApkUrl?.let { notifyUpdate() }
                pendingCall?.let { pendingCall = null; js(it) }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")

        handleReminderIntent(intent)
        addOnNewIntentListener { handleReminderIntent(it) }
        Reminders.ensureChannel(this)

        loadPage(currentHtml())
        io.execute { fetchWebUpdate() }
        io.execute { checkForApkUpdate() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false") { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private var lastCheck = 0L

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (lastCheck != 0L && now - lastCheck > 60_000) {
            io.execute { fetchWebUpdate() }
            io.execute { checkForApkUpdate() }
        }
        if (lastCheck == 0L || now - lastCheck > 60_000) lastCheck = now
    }

    /* ---------- page content: downloaded copy if compatible, else the bundled one ---------- */

    private fun bundledHtml(): String = assets.open("index.html").bufferedReader().use { it.readText() }

    private fun minNative(html: String): Int =
        Regex("""<meta\s+name="min-native"\s+content="(\d+)"""").find(html)?.groupValues?.get(1)?.toInt() ?: 1

    private fun webVersion(html: String): Long =
        Regex("""<meta\s+name="web-version"\s+content="(\d+)"""").find(html)?.groupValues?.get(1)?.toLong() ?: 0

    private fun currentHtml(): String {
        val bundled = bundledHtml()
        val cached = try { if (cachedHtml.exists()) cachedHtml.readText() else null } catch (e: Exception) { null }
        if (cached != null && minNative(cached) <= NATIVE_API && webVersion(cached) >= webVersion(bundled)) return cached
        return bundled
    }

    private fun loadPage(html: String) {
        web.loadDataWithBaseURL(BASE_URL + "index.html", html, "text/html", "utf-8", null)
    }

    private fun fetchWebUpdate() {
        try {
            val html = httpGet(WEB_URL + "?t=" + System.currentTimeMillis()) ?: return
            if (!html.contains("<title>Makrologg</title>")) return
            if (minNative(html) > NATIVE_API) return // needs a newer APK; the update banner covers that
            val current = currentHtml()
            if (html == current || webVersion(html) < webVersion(current)) return
            cachedHtml.parentFile?.mkdirs()
            cachedHtml.writeText(html)
            runOnUiThread {
                web.evaluateJavascript("window.canReload ? window.canReload() : true") { ok ->
                    if (ok == "true") loadPage(html)
                }
            }
        } catch (_: Exception) { }
    }

    /* ---------- APK updates from GitHub Releases ---------- */

    /** Latest release tag via the github.com redirect (not rate limited like the API). */
    private fun latestTag(): String? {
        val conn = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Makrologg-Android")
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location") ?: ""
            lastCheckError = if (code in 300..399 && loc.contains("/tag/")) null else "svar $code"
            if (loc.contains("/tag/")) loc.substringAfterLast("/tag/").substringBefore("?") else null
        } catch (e: Exception) {
            lastCheckError = e.javaClass.simpleName
            null
        } finally { conn.disconnect() }
    }

    private var lastCheckError: String? = null

    private fun checkForApkUpdate(manual: Boolean = false) {
        val tag = latestTag()
        if (tag == null) {
            if (manual) js("window.onUpdateCheck && window.onUpdateCheck('error', ${JSONObject.quote(lastCheckError ?: "")})")
            return
        }
        val remoteCode = tag.substringAfterLast('.').toLongOrNull()
        if (remoteCode == null || remoteCode <= versionCode) {
            if (manual) js("window.onUpdateCheck && window.onUpdateCheck('none', ${JSONObject.quote(versionName)})")
            return
        }
        updateApkUrl = "https://github.com/$REPO/releases/download/$tag/Makrologg.apk"
        var notes = ""
        try { notes = JSONObject(httpGet("https://api.github.com/repos/$REPO/releases/tags/$tag") ?: "{}").optString("body") } catch (_: Exception) { }
        updateInfo = JSONObject().put("version", tag.removePrefix("v")).put("notes", notes.take(400))
        runOnUiThread { notifyUpdate() }
    }

    private var updateInfo: JSONObject? = null
    private fun notifyUpdate() {
        val info = updateInfo ?: return
        js("window.onUpdateAvailable && window.onUpdateAvailable($info)")
    }

    private fun downloadAndInstall() {
        val url = updateApkUrl ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            js("window.onUpdateStatus && window.onUpdateStatus('permission')")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        io.execute {
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "Makrologg.apk")
                var conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                // GitHub redirects to another host; follow manually if needed
                var hops = 0
                while (conn.responseCode in 300..399 && hops++ < 5) {
                    val next = conn.getHeaderField("Location"); conn.disconnect()
                    conn = URL(next).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000; conn.readTimeout = 30000
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(32 * 1024); var done = 0L; var lastPct = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            output.write(buf, 0, n); done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; js("window.onUpdateStatus && window.onUpdateStatus('progress', $pct)") }
                            }
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                js("window.onUpdateStatus && window.onUpdateStatus('ready')")
                runOnUiThread { startActivity(install) }
            } catch (e: Exception) {
                js("window.onUpdateStatus && window.onUpdateStatus('error')")
            }
        }
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json, text/html, */*")
            conn.setRequestProperty("User-Agent", "Makrologg-Android")
            conn.useCaches = false
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    /* ---------- bridge for the web page ---------- */

    inner class Bridge {
        @JavascriptInterface
        fun scan() {
            runOnUiThread {
                val opts = ScanOptions()
                    .setDesiredBarcodeFormats(
                        ScanOptions.EAN_13, ScanOptions.EAN_8,
                        ScanOptions.UPC_A, ScanOptions.UPC_E, ScanOptions.CODE_128
                    )
                    .setPrompt("Hold strekkoden inne i rammen")
                    .setBeepEnabled(true)
                    .setOrientationLocked(true)
                scanLauncher.launch(opts)
            }
        }

        @JavascriptInterface
        fun share(text: String, title: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                }
                startActivity(Intent.createChooser(send, title))
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        @JavascriptInterface
        fun installUpdate() = downloadAndInstall()

        /** Async HTTP GET for the page; answers via window.onNativeFetch(id, status, body). Status 0 = network error. */
        @JavascriptInterface
        fun fetchText(id: String, url: String, headersJson: String) {
            net.execute {
                var status = 0
                var body = ""
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000; conn.readTimeout = 15000
                    conn.setRequestProperty("User-Agent", "Makrologg-Android/1.0")
                    try {
                        val h = JSONObject(headersJson)
                        h.keys().forEach { k -> conn.setRequestProperty(k, h.getString(k)) }
                    } catch (_: Exception) { }
                    status = conn.responseCode
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    conn.disconnect()
                } catch (_: Exception) { status = 0 }
                js("window.onNativeFetch && window.onNativeFetch(${JSONObject.quote(id)}, $status, ${JSONObject.quote(body)})")
            }
        }

        @JavascriptInterface
        fun appVersion(): String = versionName

        @JavascriptInterface
        fun scheduleReminders(json: String) = Reminders.replaceAll(this@MainActivity, json)

        @JavascriptInterface
        fun notifPermission(): String = notifState()

        @JavascriptInterface
        fun requestNotifPermission() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33 && notifState() != "granted") {
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    js("window.onNotifPermission && window.onNotifPermission(${JSONObject.quote(notifState())})")
                }
            }
        }

        @JavascriptInterface
        fun openNotifSettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }

        @JavascriptInterface
        fun nativeApi(): Int = NATIVE_API

        @JavascriptInterface
        fun checkUpdates() { io.execute { fetchWebUpdate() }; io.execute { checkForApkUpdate(true) } }
    }
}
