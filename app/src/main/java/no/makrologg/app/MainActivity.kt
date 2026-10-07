package no.makrologg.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val code = result.contents
        val js = if (code != null) {
            "window.onNativeBarcode && window.onNativeBarcode(${JSONObject.quote(code)})"
        } else {
            "window.onNativeBarcode && window.onNativeBarcode(null)"
        }
        web.evaluateJavascript(js, null)
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
                if (url.scheme == "file") return false
                startActivity(Intent(Intent.ACTION_VIEW, url))
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("file:///android_asset/index.html")

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

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
    }
}
