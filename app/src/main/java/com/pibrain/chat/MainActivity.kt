package com.pibrain.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.util.Base64
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pibrain.chat.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var activeTab = Tab.CLAUDE
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
    private var pollingJob: Job? = null

    enum class Tab { CLAUDE, GEMINI, PIBRAIN }

    private val chromeUA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230901.001) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/121.0.6167.144 Mobile Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        requestPermissions()
        setupWebViews()
        setupTabs()
        setupPiBrainPanel()

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
                userAgentString = chromeUA
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = false
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                safeBrowsingEnabled = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            wv.webViewClient = PiBrainWebViewClient()
            wv.webChromeClient = PiBrainChromeClient(wv)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.removeJavascriptInterface("searchBoxJavaBridge_")
            wv.removeJavascriptInterface("accessibility")
            wv.removeJavascriptInterface("accessibilityTraversal")
        }
    }

    private fun setupTabs() {
        binding.tabClaude.setOnClickListener { showTab(Tab.CLAUDE) }
        binding.tabGemini.setOnClickListener { showTab(Tab.GEMINI) }
        // Note: activity_main.xml still uses IDs tabPibrain / indicatorPibrain
        findViewById<View>(R.id.tabPibrain).setOnClickListener { showTab(Tab.PIBRAIN) }
    }

    private fun showTab(tab: Tab) {
        activeTab = tab
        binding.webviewClaude.visibility = if (tab == Tab.CLAUDE) View.VISIBLE else View.GONE
        binding.webviewGemini.visibility = if (tab == Tab.GEMINI) View.VISIBLE else View.GONE
        // ID changed to pibrainPanel in XML
        findViewById<View>(R.id.pibrainPanel).visibility = if (tab == Tab.PIBRAIN) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(this, R.color.tab_active)
        val inactive = ContextCompat.getColor(this, R.color.tab_inactive)
        binding.tabClaude.setTextColor(if (tab == Tab.CLAUDE) active else inactive)
        binding.tabGemini.setTextColor(if (tab == Tab.GEMINI) active else inactive)
        
        val tabPibrain = findViewById<android.widget.TextView>(R.id.tabPibrain)
        tabPibrain.setTextColor(if (tab == Tab.PIBRAIN) active else inactive)

        binding.indicatorClaude.visibility = if (tab == Tab.CLAUDE) View.VISIBLE else View.INVISIBLE
        binding.indicatorGemini.visibility = if (tab == Tab.GEMINI) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.indicatorPibrain).visibility = if (tab == Tab.PIBRAIN) View.VISIBLE else View.INVISIBLE

        if (tab == Tab.PIBRAIN) {
            checkPatAndStart()
        } else {
            pollingJob?.cancel()
        }
    }

    private fun getSharedPrefs() = EncryptedSharedPreferences.create(
        this,
        "pibrain_prefs",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun checkPatAndStart() {
        val prefs = getSharedPrefs()
        if (prefs.getString("github_pat", null) == null) {
            val input = EditText(this)
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            AlertDialog.Builder(this)
                .setTitle("GitHub PAT Required")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    prefs.edit().putString("github_pat", input.text.toString().trim()).apply()
                    startPiBrain()
                }
                .setCancelable(false)
                .show()
        } else {
            startPiBrain()
        }
    }

    private fun startPiBrain() {
        startForegroundPolling()
        
        // Schedule background worker
        val workRequest = PeriodicWorkRequestBuilder<PiBrainWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PiBrainWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun setupPiBrainPanel() {
        val btnSend = findViewById<View>(R.id.btnSendChat)
        val chatInput = findViewById<EditText>(R.id.chatInput)
        
        btnSend.setOnClickListener {
            val msg = chatInput.text.toString().trim()
            if (msg.isNotEmpty()) {
                chatInput.setText("")
                appendChat("You:\n$msg\n")
                sendToPiBrain(msg)
            }
        }
    }

    private fun appendChat(text: String) {
        val chatHistory = findViewById<android.widget.TextView>(R.id.chatHistory)
        val scroll = findViewById<android.widget.ScrollView>(R.id.chatScroll)
        chatHistory.append(text + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendToPiBrain(message: String) {
        val prefs = getSharedPrefs()
        val pat = prefs.getString("github_pat", null) ?: return
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val dateFmtFrontmatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val now = Date()
                val utcTime = dateFormat.format(now)
                val fmTime = dateFmtFrontmatter.format(now)

                val frontmatter = "---\ndevice: phone\nsent_at: $fmTime\napp: PiBrainChat\n---\n"
                val fullContent = frontmatter + message
                
                // Idempotent hash
                val hashInput = "phone$fmTime$message"
                val md = MessageDigest.getInstance("SHA-1")
                val sha1 = md.digest(hashInput.toByteArray()).joinToString("") { "%02x".format(it) }.take(6)
                
                val filename = "${utcTime}__${sha1}.md"
                val encodedContent = Base64.encodeToString(fullContent.toByteArray(), Base64.NO_WRAP)
                
                val json = JSONObject().apply {
                    put("message", "User message from PiBrainChat")
                    put("content", encodedContent)
                    put("branch", "feature/anr-tier0-fix")
                }

                val request = Request.Builder()
                    .url("https://api.github.com/repos/physicistmanoj/pibrain/contents/chat/inbox/$filename")
                    .header("Authorization", "Bearer $pat")
                    .header("Accept", "application/vnd.github.v3+json")
                    .put(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) { toast("Failed to send: ${response.code}") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Error: ${e.message}") }
            }
        }
    }

    private fun startForegroundPolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            val prefs = getSharedPrefs()
            while (isActive) {
                val pat = prefs.getString("github_pat", null)
                if (pat != null) {
                    try {
                        val request = Request.Builder()
                            .url("https://api.github.com/repos/physicistmanoj/pibrain/contents/chat/outbox?ref=feature/anr-tier0-fix")
                            .header("Authorization", "Bearer $pat")
                            .header("Accept", "application/vnd.github.v3+json")
                            .build()

                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            if (body.startsWith("[")) {
                                val jsonArray = JSONArray(body)
                                for (i in 0 until jsonArray.length()) {
                                    val fileObj = jsonArray.getJSONObject(i)
                                    val name = fileObj.getString("name")
                                    val url = fileObj.getString("download_url")
                                    
                                    if (!prefs.getBoolean("seen_$name", false)) {
                                        // Fetch content
                                        val contentReq = Request.Builder().url(url).build()
                                        val contentRes = client.newCall(contentReq).execute()
                                        if (contentRes.isSuccessful) {
                                            val text = contentRes.body?.string() ?: ""
                                            withContext(Dispatchers.Main) {
                                                appendChat("PiBrain:\n$text\n")
                                            }
                                            prefs.edit().putBoolean("seen_$name", true).apply()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                delay(30000)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
    }

    inner class PiBrainChromeClient(private val parentWebView: WebView) : WebChromeClient() {
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
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
        override fun onPermissionRequest(request: PermissionRequest?) { request?.grant(request.resources) }
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            binding.progressBar.progress = newProgress
            binding.progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        }
    }
}
