package com.pibrain.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pibrain.chat.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var activeTab = Tab.CLAUDE

    enum class Tab { CLAUDE, GEMINI, RELAY }

    // Pixel 7 Chrome UA — must NOT contain "wv" (WebView marker)
    // claude.ai checks for "wv" in the UA and blocks it
    private val chromeUA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230901.001) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/121.0.6167.144 Mobile Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Global cookie manager setup
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        requestPermissions()
        setupWebViews()
        setupTabs()
        setupRelayPanel()
        setupNotepad()

        binding.webviewClaude.loadUrl("https://claude.ai")
        binding.webviewGemini.loadUrl("https://gemini.google.com")

        showTab(Tab.CLAUDE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViews() {
        listOf(binding.webviewClaude, binding.webviewGemini).forEach { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false

                // Critical: strip "wv" from user agent
                // Default WebView UA contains " wv" which sites use to detect and block WebViews
                userAgentString = chromeUA

                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = false
                displayZoomControls = false

                // Allow mixed content (needed for some auth flows)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // Disable safe browsing — it can interfere with auth redirects
                safeBrowsingEnabled = false

                // Cache
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            wv.webViewClient = PiBrainWebViewClient()
            wv.webChromeClient = PiBrainChromeClient(wv)

            // Accept third-party cookies (required for OAuth)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            // Remove WebView-specific headers that leak identity
            wv.removeJavascriptInterface("searchBoxJavaBridge_")
            wv.removeJavascriptInterface("accessibility")
            wv.removeJavascriptInterface("accessibilityTraversal")
        }
    }

    private fun setupTabs() {
        binding.tabClaude.setOnClickListener { showTab(Tab.CLAUDE) }
        binding.tabGemini.setOnClickListener { showTab(Tab.GEMINI) }
        binding.tabRelay.setOnClickListener { showTab(Tab.RELAY) }
    }

    private fun showTab(tab: Tab) {
        activeTab = tab
        binding.webviewClaude.visibility = if (tab == Tab.CLAUDE) View.VISIBLE else View.GONE
        binding.webviewGemini.visibility = if (tab == Tab.GEMINI) View.VISIBLE else View.GONE
        binding.relayPanel.visibility = if (tab == Tab.RELAY) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(this, R.color.tab_active)
        val inactive = ContextCompat.getColor(this, R.color.tab_inactive)
        binding.tabClaude.setTextColor(if (tab == Tab.CLAUDE) active else inactive)
        binding.tabGemini.setTextColor(if (tab == Tab.GEMINI) active else inactive)
        binding.tabRelay.setTextColor(if (tab == Tab.RELAY) active else inactive)

        binding.indicatorClaude.visibility = if (tab == Tab.CLAUDE) View.VISIBLE else View.INVISIBLE
        binding.indicatorGemini.visibility = if (tab == Tab.GEMINI) View.VISIBLE else View.INVISIBLE
        binding.indicatorRelay.visibility = if (tab == Tab.RELAY) View.VISIBLE else View.INVISIBLE
    }

    private fun setupRelayPanel() {
        binding.btnFromClaude.setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cb.primaryClip
            if (clip != null && clip.itemCount > 0)
                binding.relayInput.setText(clip.getItemAt(0).coerceToText(this).toString())
            else toast("Copy text from Claude first")
        }
        binding.btnFromGemini.setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cb.primaryClip
            if (clip != null && clip.itemCount > 0)
                binding.relayInput.setText(clip.getItemAt(0).coerceToText(this).toString())
            else toast("Copy text from Gemini first")
        }
        binding.btnSendToClaude.setOnClickListener {
            val text = binding.relayInput.text.toString().trim()
            if (text.isNotEmpty()) { copyToClipboard(text); showTab(Tab.CLAUDE); toast("Copied — paste into Claude") }
        }
        binding.btnSendToGemini.setOnClickListener {
            val text = binding.relayInput.text.toString().trim()
            if (text.isNotEmpty()) { copyToClipboard(text); showTab(Tab.GEMINI); toast("Copied — paste into Gemini") }
        }
        binding.btnClearRelay.setOnClickListener { binding.relayInput.setText("") }
    }

    private fun setupNotepad() {
        binding.notepadInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                copyToClipboard(binding.notepadInput.text.toString())
                toast("Copied")
                true
            } else false
        }
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("PiBrain", text))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (activeTab) {
            Tab.CLAUDE -> if (binding.webviewClaude.canGoBack()) binding.webviewClaude.goBack() else super.onBackPressed()
            Tab.GEMINI -> if (binding.webviewGemini.canGoBack()) binding.webviewGemini.goBack() else super.onBackPressed()
            Tab.RELAY -> showTab(Tab.CLAUDE)
        }
    }

    private fun requestPermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    inner class PiBrainWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            binding.progressBar.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            binding.progressBar.visibility = View.GONE
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            binding.progressBar.visibility = View.GONE
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            // Let the page handle its own HTTP errors
        }
    }

    inner class PiBrainChromeClient(private val parentWebView: WebView) : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
        ): Boolean {
            // Handle OAuth popup windows (Google login, etc.)
            val newWebView = WebView(this@MainActivity)
            newWebView.settings.javaScriptEnabled = true
            newWebView.settings.domStorageEnabled = true
            newWebView.settings.userAgentString = chromeUA
            newWebView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            newWebView.webViewClient = PiBrainWebViewClient()
            CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)
            val transport = resultMsg?.obj as? WebView.WebViewTransport
            transport?.webView = newWebView
            resultMsg?.sendToTarget()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.grant(request.resources)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            binding.progressBar.progress = newProgress
            binding.progressBar.visibility =
                if (newProgress < 100) View.VISIBLE else View.GONE
        }
    }
}
