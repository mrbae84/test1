package vn.trinhdoc.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/** Màn hình đọc toàn màn hình. Chạm 1 lần để hiện/ẩn thanh công cụ. */
class ReaderActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val HOST = "reader.local"
        private val THEMES = setOf("light", "sepia", "green", "dark")
        /** Các cài đặt trang đọc được phép lưu (từ bảng cài đặt trong trang). */
        private val SETTING_KEYS = setOf("theme", "font", "fs", "lh", "mg", "spread", "mode_pdf", "mode_docx", "mode_pptx")
    }

    private lateinit var web: WebView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var settingsBtn: TextView
    private lateinit var closeBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView

    @Volatile
    private var content: ReaderContent? = null
    @Volatile
    private var pageHtml: ByteArray? = null
    @Volatile
    private var screenW = 1200
    private var uiVisible = false
    /** true khi đang ở kiểu Trượt hoặc Lật sách (nút âm lượng dùng để lật trang). */
    @Volatile
    private var pagedMode = false
    @Volatile
    private var kind = ""

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        buildUi()
        setUiVisible(false)
        updateScreenWidth()

        val resolved = resolveInput()
        if (resolved == null) {
            showError("Không tìm thấy file để mở.")
            return
        }
        val (input, name) = resolved
        titleView.text = name

        Thread {
            try {
                val c = DocLoader.load(applicationContext, input, name)
                val html = Html.page(c, settingsJson()).toByteArray(Charsets.UTF_8)
                runOnUiThread {
                    if (isDestroyed) {
                        c.close()
                    } else {
                        content = c
                        kind = c.kind
                        pageHtml = html
                        pagedMode = currentMode(c.kind) != "scroll"
                        settingsBtn.visibility = View.VISIBLE
                        progress.visibility = View.GONE
                        web.loadUrl("https://$HOST/index.html")
                        showHintOnce()
                    }
                }
            } catch (e: ReaderException) {
                runOnUiThread { showError(e.message ?: "Không mở được file.") }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { showError("File quá lớn, máy không đủ bộ nhớ để mở.") }
            } catch (e: Exception) {
                runOnUiThread { showError("Không mở được file (${e.javaClass.simpleName}).") }
            }
        }.start()
    }

    private fun resolveInput(): Pair<Input, String>? {
        intent.getStringExtra(EXTRA_PATH)?.let { path ->
            val f = File(path)
            return Pair(Input.Local(f), Recents.displayName(f))
        }
        val uri: Uri? = if (intent.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
        } else {
            intent.data
        }
        if (uri == null) return null
        return Pair(Input.Remote(uri), displayName(uri))
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Tài liệu"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun barButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this)

        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        progress = ProgressBar(this)
        root.addView(progress, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        errorView = TextView(this).apply {
            visibility = View.GONE
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(errorView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE6202124.toInt())
        }
        topBar.addView(barButton("←") { finish() })
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        settingsBtn = barButton("Aa") { web.evaluateJavascript("toggleSheet()", null) }
        settingsBtn.textSize = 19f
        settingsBtn.visibility = View.GONE
        topBar.addView(settingsBtn)
        root.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // Nút ✕ nhỏ, mờ, luôn ở góc trên bên phải: bấm là thoát ngay.
        closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            alpha = 0.55f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            contentDescription = "Đóng"
            setOnClickListener { finish() }
        }
        val closeLp = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END)
        root.addView(closeBtn, closeLp)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val t = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            topBar.setPadding(t.left, t.top, t.right, 0)
            closeLp.topMargin = t.top + dp(10)
            closeLp.marginEnd = t.right + dp(12)
            closeBtn.layoutParams = closeLp
            insets
        }

        setContentView(root)
        setupWeb()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.setBackgroundColor(Color.TRANSPARENT)
        with(web.settings) {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        web.addJavascriptInterface(Bridge(), "Reader")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (url.host != HOST) return notFound()
                val path = url.path ?: "/"
                if (path == "/index.html") {
                    val html = pageHtml ?: return notFound()
                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html))
                }
                if (path == "/reader.css" || path == "/reader.js") {
                    return try {
                        WebResourceResponse(
                            if (path.endsWith(".css")) "text/css" else "application/javascript", "utf-8",
                            assets.open(path.removePrefix("/"))
                        )
                    } catch (e: Exception) {
                        notFound()
                    }
                }
                val res = try {
                    content?.resource(path, screenW)
                } catch (e: Throwable) {
                    null
                }
                return res ?: notFound()
            }

            // Không cho chuyển trang (tài liệu chỉ để đọc).
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    inner class Bridge {
        @JavascriptInterface
        fun tap() {
            runOnUiThread { setUiVisible(!uiVisible) }
        }

        /** Trang đọc lưu một cài đặt (màu nền, phông, kiểu xem...). */
        @JavascriptInterface
        fun save(key: String, value: String) {
            if (key !in SETTING_KEYS || value.length > 20) return
            prefs.edit().putString(key, value).apply()
            if (key == "mode_$kind") pagedMode = value != "scroll"
        }
    }

    private fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        closeBtn.visibility = if (visible) View.GONE else View.VISIBLE
        window.insetsController?.let { c ->
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (visible) c.show(WindowInsets.Type.systemBars()) else c.hide(WindowInsets.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !uiVisible) setUiVisible(false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenWidth()
    }

    private fun updateScreenWidth() {
        screenW = (resources.displayMetrics.widthPixels * 1.6).toInt().coerceIn(1400, 2400)
    }

    private fun currentMode(k: String): String =
        prefs.getString("mode_$k", null) ?: if (k == "pptx") "slide" else "scroll"

    /** Cài đặt hiện tại gửi vào trang đọc. */
    private fun settingsJson(): String {
        val o = JSONObject()
        for (k in SETTING_KEYS) prefs.getString(k, null)?.let { o.put(k, it) }
        if (o.optString("theme") !in THEMES) {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            o.put("theme", if (night) "dark" else "light")
        }
        return o.toString()
    }

    // Nút âm lượng để lật trang khi ở kiểu Trượt / Lật sách.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (pagedMode && content != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    web.evaluateJavascript("nextPage()", null); return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    web.evaluateJavascript("prevPage()", null); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showHintOnce() {
        if (!prefs.getBoolean("hinted2", false)) {
            prefs.edit().putBoolean("hinted2", true).apply()
            Toast.makeText(
                this,
                "Chạm giữa màn hình để hiện thanh công cụ. Bấm Aa để chọn kiểu đọc, màu nền, cỡ chữ.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showError(msg: String) {
        progress.visibility = View.GONE
        errorView.text = msg
        errorView.visibility = View.VISIBLE
        setUiVisible(true)
    }

    override fun onDestroy() {
        web.destroy()
        content?.close()
        content = null
        super.onDestroy()
    }
}
