package vn.trinhdoc.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
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
import java.io.ByteArrayInputStream
import java.io.File

/** Màn hình đọc toàn màn hình. Chạm 1 lần để hiện/ẩn thanh công cụ. */
class ReaderActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val HOST = "reader.local"
        private val THEMES = arrayOf("light", "sepia", "dark")
        private val THEME_NAMES = mapOf("light" to "Nền sáng", "sepia" to "Nền giấy", "dark" to "Nền tối")
    }

    private lateinit var web: WebView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var spreadBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView

    @Volatile
    private var content: ReaderContent? = null
    @Volatile
    private var pageHtml: ByteArray? = null
    @Volatile
    private var screenW = 1200
    private var uiVisible = false

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
                val html = Html.page(c, currentTheme()).toByteArray(Charsets.UTF_8)
                runOnUiThread {
                    if (isDestroyed) {
                        c.close()
                    } else {
                        content = c
                        pageHtml = html
                        spreadBtn.visibility = if (c.kind == "pdf") View.VISIBLE else View.GONE
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
        spreadBtn = barButton("1 | 2 trang") { web.evaluateJavascript("toggleSpread()", null) }
        spreadBtn.visibility = View.GONE
        topBar.addView(spreadBtn)
        topBar.addView(barButton("Nền") { cycleTheme() })
        root.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val t = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            topBar.setPadding(t.left, t.top, t.right, 0)
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
    }

    private fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
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
        screenW = resources.displayMetrics.widthPixels.coerceIn(1000, 1800)
    }

    private fun currentTheme(): String {
        prefs.getString("theme", null)?.let { if (it in THEMES) return it }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) "dark" else "light"
    }

    private fun cycleTheme() {
        val next = THEMES[(THEMES.indexOf(currentTheme()) + 1) % THEMES.size]
        prefs.edit().putString("theme", next).apply()
        web.evaluateJavascript("setTheme('$next')", null)
        Toast.makeText(this, THEME_NAMES[next], Toast.LENGTH_SHORT).show()
    }

    private fun showHintOnce() {
        if (!prefs.getBoolean("hinted", false)) {
            prefs.edit().putBoolean("hinted", true).apply()
            Toast.makeText(this, "Chạm một lần vào màn hình để hiện thanh công cụ", Toast.LENGTH_LONG).show()
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
