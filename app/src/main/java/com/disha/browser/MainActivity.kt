package com.disha.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    class Tab(val web: WebView, val incognito: Boolean) {
        var desktop = false
    }

    private val tabs = mutableListOf<Tab>()
    private var current: Tab? = null
    private val closedUrls = ArrayDeque<String>()
    private lateinit var prefs: Prefs

    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var tabBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var container: FrameLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private var urlDefaultColors: ColorStateList? = null

    @Volatile private var adBlockOn = true
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && text != null) go(text)
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        prefs.applyNightMode()
        super.onCreate(savedInstanceState)
        adBlockOn = prefs.adBlock
        buildUi()
        Notify.channels(this)
        DailyWorker.schedule(this, prefs.notifications)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }

        prefs.savedTabs().forEach { newTab(it, false, select = false) }
        val incoming = intentUrl(intent)
        when {
            incoming != null -> newTab(incoming, false)
            tabs.isEmpty() -> newTab(prefs.homeUrl(), false)
            else -> selectTab(tabs.last())
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val c = current
                when {
                    fullscreenView != null -> fullscreenCallback?.onCustomViewHidden()
                    findBar.visibility == View.VISIBLE -> closeFind()
                    c != null && c.web.canGoBack() -> c.web.goBack()
                    c != null && tabs.size > 1 -> closeTab(c)
                    else -> finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentUrl(intent)?.let { newTab(it, false) }
    }

    override fun onPause() {
        super.onPause()
        prefs.saveTabs(tabs.filter { !it.incognito }.mapNotNull { it.web.url })
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    private fun intentUrl(i: Intent?): String? {
        if (i == null) return null
        i.getStringExtra("open_url")?.let { i.removeExtra("open_url"); return it }
        if (i.action == Intent.ACTION_VIEW) i.dataString?.let { return it }
        if (i.action == Intent.ACTION_WEB_SEARCH || i.action == Intent.ACTION_SEARCH) {
            i.getStringExtra(SearchManager.QUERY)?.let { return toUrl(it) }
        }
        return null
    }

    // ---------------- UI ----------------

    private fun iconButton(label: String, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            minWidth = dp(40)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onClick(it) }
        }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val home = iconButton("⌂") { current?.web?.loadUrl(prefs.homeUrl()) }
        urlBar = EditText(this).apply {
            hint = "खोजें या वेब पता लिखें"
            isSingleLine = true
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.url_bg)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    go(text.toString()); true
                } else false
            }
        }
        urlDefaultColors = urlBar.textColors
        val mic = iconButton("🎤") { startVoice() }.apply { textSize = 17f }
        tabBtn = iconButton("1") { showTabs() }.apply {
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
        }
        val menuBtn = iconButton("⋮") { v -> showMenu(v) }
        topBar.addView(home)
        topBar.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(mic)
        topBar.addView(tabBtn)
        topBar.addView(menuBtn)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.brand))
        }

        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(2), dp(4), dp(2))
        }
        findInput = EditText(this).apply {
            hint = "पेज में खोजें"
            isSingleLine = true
            textSize = 15f
            doAfterTextChanged { s -> current?.web?.findAllAsync(s?.toString() ?: "") }
        }
        findCount = TextView(this).apply { text = "0/0"; setPadding(dp(6), 0, dp(6), 0) }
        findBar.addView(findInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        findBar.addView(findCount)
        findBar.addView(iconButton("▲") { current?.web?.findNext(false) })
        findBar.addView(iconButton("▼") { current?.web?.findNext(true) })
        findBar.addView(iconButton("✕") { closeFind() })

        container = FrameLayout(this)
        swipe = SwipeRefreshLayout(this).apply {
            addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setColorSchemeColors(ContextCompat.getColor(this@MainActivity, R.color.brand))
            setOnRefreshListener { current?.web?.reload() }
            setOnChildScrollUpCallback { _, _ -> (current?.web?.scrollY ?: 0) > 0 }
        }

        root.addView(topBar)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(findBar)
        root.addView(swipe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun displayUrl(url: String?): String {
        if (url == null || url.startsWith(Prefs.HOME) || url == "about:blank") return ""
        if (url.startsWith("file:///android_asset/search.html")) {
            return Uri.parse(url).getQueryParameter("q") ?: ""
        }
        return url
    }

    // ---------------- Tabs ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(incognito: Boolean): WebView {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = prefs.javascript
            domStorageEnabled = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            textZoom = prefs.textZoom
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (incognito) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, true)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = Client()
        w.webChromeClient = Chrome()
        w.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
        w.setFindListener { active, count, _ ->
            findCount.text = if (count == 0) "0/0" else "${active + 1}/$count"
        }
        return w
    }

    private fun tabOf(v: WebView?): Tab? = tabs.firstOrNull { it.web == v }

    private fun newTab(url: String, incognito: Boolean, select: Boolean = true) {
        val t = Tab(createWebView(incognito), incognito)
        tabs.add(t)
        t.web.loadUrl(url)
        if (select) {
            selectTab(t)
            if (incognito) toast("इनकॉग्निटो टैब: इतिहास और कैश सेव नहीं होगा")
        } else updateTabCount()
    }

    private fun selectTab(t: Tab) {
        container.removeAllViews()
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        container.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        current = t
        urlBar.setText(displayUrl(t.web.url))
        if (t.incognito) {
            topBar.setBackgroundColor(Color.parseColor("#2B2B2B"))
            urlBar.setTextColor(Color.WHITE)
            urlBar.setHintTextColor(Color.LTGRAY)
            tabBtn.setTextColor(Color.WHITE)
        } else {
            topBar.setBackgroundColor(Color.TRANSPARENT)
            urlDefaultColors?.let { urlBar.setTextColor(it) }
            tabBtn.setTextColor(urlBar.currentTextColor)
        }
        updateTabCount()
    }

    private fun closeTab(t: Tab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        if (!t.incognito) t.web.url?.let { if (it.startsWith("http")) closedUrls.addLast(it) }
        tabs.removeAt(idx)
        container.removeView(t.web)
        t.web.destroy()
        when {
            tabs.isEmpty() -> newTab(prefs.homeUrl(), false)
            current == t -> selectTab(tabs[minOf(idx, tabs.size - 1)])
            else -> updateTabCount()
        }
    }

    private fun updateTabCount() {
        tabBtn.text = tabs.size.toString()
    }

    private fun showTabs() {
        lateinit var dialog: AlertDialog
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(12), dp(8))
        }
        tabs.toList().forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val name = t.web.title?.takeIf { it.isNotBlank() && !it.endsWith(".html") } ?: "नया टैब"
            val label = TextView(this).apply {
                text = (if (t.incognito) "🕶 " else "") + name + "\n" + displayUrl(t.web.url)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                textSize = 15f
                if (t == current) setTypeface(null, Typeface.BOLD)
                setOnClickListener { selectTab(t); dialog.dismiss() }
            }
            val x = iconButton("✕") { closeTab(t); dialog.dismiss(); showTabs() }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(x)
            box.addView(row)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("खुले टैब (${tabs.size})")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("+ नया टैब") { _, _ -> newTab(prefs.homeUrl(), false) }
            .setNeutralButton("+ इनकॉग्निटो") { _, _ -> newTab(prefs.homeUrl(), true) }
            .setNegativeButton("सभी बंद करें") { _, _ -> tabs.toList().forEach { closeTab(it) } }
            .create()
        dialog.show()
    }

    // ---------------- Navigation ----------------

    private fun go(input: String) {
        current?.web?.loadUrl(toUrl(input))
        hideKeyboard(urlBar)
        urlBar.clearFocus()
    }

    private fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return prefs.homeUrl()
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://") || s.startsWith("about:")) return s
        if (!s.contains(" ") && s.contains(".") && !s.endsWith(".") && !s.startsWith(".")) return "https://$s"
        return Engines.url(prefs.engine, s)
    }

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "बोलिए, क्या खोजना है…")
        try { voice.launch(i) } catch (e: ActivityNotFoundException) { toast("इस फ़ोन पर वॉइस सर्च उपलब्ध नहीं है") }
    }

    inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            val scheme = u.scheme ?: return false
            if (scheme == "disha") {
                val q = u.getQueryParameter("q") ?: ""
                val e = u.getQueryParameter("e") ?: prefs.engine
                view.loadUrl(if (u.host == "open") toUrl(q) else Engines.url(e, q))
                return true
            }
            if (scheme in listOf("http", "https", "file", "about", "data", "javascript", "blob")) return false
            val s = u.toString()
            try {
                val i = if (scheme == "intent") Intent.parseUri(s, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                startActivity(i)
            } catch (e: Exception) {
                if (scheme == "intent") {
                    runCatching { Intent.parseUri(s, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }
                        .getOrNull()?.let { view.loadUrl(it) }
                } else toast("यह लिंक खोलने वाला ऐप नहीं मिला")
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (adBlockOn && AdBlock.isAd(request.url.host)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view == current?.web && !urlBar.hasFocus()) urlBar.setText(displayUrl(url))
        }

        override fun onPageFinished(view: WebView, url: String?) {
            swipe.isRefreshing = false
            val t = tabOf(view) ?: return
            if (view == current?.web && !urlBar.hasFocus()) urlBar.setText(displayUrl(url))
            if (!t.incognito && url != null && url.startsWith("http")) {
                prefs.addEntry("history", view.title ?: url, url, 500)
            }
        }
    }

    inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view == current?.web) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) { callback.onCustomViewHidden(); return }
            fullscreenView = view
            fullscreenCallback = callback
            (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        override fun onHideCustomView() {
            fullscreenView?.let { (window.decorView as FrameLayout).removeView(it) }
            fullscreenView = null
            fullscreenCallback = null
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                fileChooser.launch(fileChooserParams.createIntent()); true
            } catch (e: Exception) {
                fileCallback = null; false
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            AlertDialog.Builder(this@MainActivity)
                .setMessage("$origin आपकी लोकेशन जानना चाहता है")
                .setPositiveButton("अनुमति दें") { _, _ ->
                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    callback.invoke(origin, true, false)
                }
                .setNegativeButton("नहीं") { _, _ -> callback.invoke(origin, false, false) }
                .show()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("${request.origin.host} कैमरा/माइक इस्तेमाल करना चाहता है")
                    .setPositiveButton("अनुमति दें") { _, _ ->
                        val need = mutableListOf<String>()
                        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) need.add(Manifest.permission.CAMERA)
                        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) need.add(Manifest.permission.RECORD_AUDIO)
                        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
                        request.grant(request.resources)
                    }
                    .setNegativeButton("नहीं") { _, _ -> request.deny() }
                    .show()
            }
        }
    }

    // ---------------- Menu ----------------

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val items = listOf(
            1 to "← पीछे", 2 to "→ आगे", 3 to "⟳ रीलोड करें",
            4 to "नया टैब", 5 to "नया इनकॉग्निटो टैब", 6 to "बंद किया टैब फिर खोलें",
            7 to "☆ बुकमार्क जोड़ें", 8 to "बुकमार्क", 9 to "इतिहास (History)", 10 to "डाउनलोड",
            11 to "पेज में खोजें", 12 to "शेयर करें", 13 to "हिंदी में अनुवाद करें", 14 to "रीडिंग मोड",
            15 to "डेस्कटॉप साइट", 16 to "होम स्क्रीन पर जोड़ें", 17 to "ब्राउज़िंग डेटा हटाएँ",
            18 to "आज की ताज़ा ख़बरें", 19 to "सेटिंग्स", 20 to "अपडेट जाँचें"
        )
        items.forEach { (id, title) -> pm.menu.add(0, id, id, title) }
        pm.menu.findItem(15).isCheckable = true
        pm.menu.findItem(15).isChecked = current?.desktop == true
        pm.setOnMenuItemClickListener { onMenu(it.itemId); true }
        pm.show()
    }

    private fun onMenu(id: Int) {
        val w = current?.web
        when (id) {
            1 -> if (w?.canGoBack() == true) w?.goBack()
            2 -> if (w?.canGoForward() == true) w?.goForward()
            3 -> w?.reload()
            4 -> newTab(prefs.homeUrl(), false)
            5 -> newTab(prefs.homeUrl(), true)
            6 -> closedUrls.removeLastOrNull()?.let { newTab(it, false) } ?: toast("कोई बंद टैब नहीं है")
            7 -> addBookmark()
            8 -> showList("बुकमार्क", "bookmarks")
            9 -> showList("इतिहास", "history")
            10 -> try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (e: Exception) { toast("डाउनलोड फ़ोल्डर नहीं खुल पाया") }
            11 -> openFind()
            12 -> share()
            13 -> translate()
            14 -> readingMode()
            15 -> toggleDesktop()
            16 -> addShortcut()
            17 -> clearData()
            18 -> newTab(News.PAGE, false)
            19 -> showSettings()
            20 -> checkUpdate()
        }
    }

    private fun pageUrl(): String? = current?.web?.url?.takeIf { it.startsWith("http") }

    private fun addBookmark() {
        val w = current?.web ?: return
        val url = pageUrl() ?: run { toast("यह पेज बुकमार्क नहीं हो सकता"); return }
        prefs.addEntry("bookmarks", w.title ?: url, url, 1000)
        toast("बुकमार्क जोड़ दिया ★")
    }

    private fun showList(title: String, key: String) {
        val list = prefs.list(key)
        if (list.isEmpty()) { toast("अभी कुछ नहीं है"); return }
        val labels = list.map { "${it.title}\n${it.url}" }.toTypedArray()
        val d = AlertDialog.Builder(this)
            .setTitle("$title — हटाने के लिए देर तक दबाएँ")
            .setItems(labels) { _, i -> current?.web?.loadUrl(list[i].url) }
            .setNeutralButton("सब हटाएँ") { _, _ -> prefs.saveList(key, emptyList()); toast("सब हटा दिया") }
            .setNegativeButton("बंद करें", null)
            .create()
        d.setOnShowListener {
            d.listView?.setOnItemLongClickListener { _, _, pos, _ ->
                list.removeAt(pos)
                prefs.saveList(key, list)
                d.dismiss()
                toast("हटा दिया")
                true
            }
        }
        d.show()
    }

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        current?.web?.clearMatches()
        findInput.setText("")
        findBar.visibility = View.GONE
        hideKeyboard(findInput)
    }

    private fun share() {
        val url = pageUrl() ?: return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, current?.web?.title ?: "")
            .putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(i, "शेयर करें"))
    }

    private fun translate() {
        val url = pageUrl() ?: run { toast("इस पेज का अनुवाद नहीं हो सकता"); return }
        current?.web?.loadUrl("https://translate.google.com/translate?sl=auto&tl=hi&u=" + URLEncoder.encode(url, "UTF-8"))
    }

    private fun readingMode() {
        val js = """
            (function(){
              var a=document.querySelector('article')||document.querySelector('main')||document.body;
              var els=a.querySelectorAll('h1,h2,h3,p,li,img,blockquote');
              var h='<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8">'+
                '<style>body{font-family:serif;max-width:720px;margin:auto;padding:18px;line-height:1.75;font-size:19px;background:#fbf6ec;color:#222}'+
                'img{max-width:100%;height:auto;border-radius:8px}h1{font-size:26px}</style></head><body><h1>'+
                document.title.replace(/</g,'&lt;')+'</h1>';
              els.forEach(function(e){
                if(e.tagName==='IMG'){ if(e.src && e.width>100) h+='<img src="'+e.src+'">'; }
                else { var t=(e.innerText||'').trim(); if(t.length>0) h+='<'+e.tagName+'>'+t.replace(/</g,'&lt;')+'</'+e.tagName+'>'; }
              });
              document.open(); document.write(h+'</body></html>'); document.close();
            })();
        """.trimIndent()
        current?.web?.evaluateJavascript(js, null)
    }

    private fun toggleDesktop() {
        val t = current ?: return
        t.desktop = !t.desktop
        t.web.settings.userAgentString = if (t.desktop) DESKTOP_UA else null
        t.web.settings.useWideViewPort = true
        t.web.settings.loadWithOverviewMode = true
        t.web.reload()
        toast(if (t.desktop) "डेस्कटॉप साइट चालू" else "मोबाइल साइट चालू")
    }

    private fun addShortcut() {
        val w = current?.web ?: return
        val url = pageUrl() ?: run { toast("यह पेज होम स्क्रीन पर नहीं जुड़ सकता"); return }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) { toast("आपका लॉन्चर शॉर्टकट सपोर्ट नहीं करता"); return }
        val icon = w.favicon?.let { IconCompat.createWithBitmap(it) } ?: IconCompat.createWithResource(this, R.mipmap.ic_launcher)
        val label = w.title?.take(24)?.ifBlank { "दिशा" } ?: "दिशा"
        val info = ShortcutInfoCompat.Builder(this, "s" + url.hashCode())
            .setShortLabel(label)
            .setIcon(icon)
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)))
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, info, null)
    }

    private fun clearData() {
        val opts = arrayOf("इतिहास", "कैश की गई फ़ाइलें", "कुकीज़ और साइट डेटा", "बुकमार्क")
        val checked = booleanArrayOf(true, true, false, false)
        AlertDialog.Builder(this)
            .setTitle("ब्राउज़िंग डेटा हटाएँ")
            .setMultiChoiceItems(opts, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("हटाएँ") { _, _ ->
                if (checked[0]) { prefs.saveList("history", emptyList()); tabs.forEach { it.web.clearHistory() } }
                if (checked[1]) current?.web?.clearCache(true)
                if (checked[2]) { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() }
                if (checked[3]) prefs.saveList("bookmarks", emptyList())
                toast("डेटा हटा दिया गया")
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    private fun onOff(b: Boolean) = if (b) "चालू" else "बंद"

    private fun showSettings() {
        val themes = arrayOf("सिस्टम जैसा", "लाइट", "डार्क")
        val items = arrayOf(
            "डिफ़ॉल्ट सर्च इंजन: ${Engines.list[prefs.engine]}",
            "होम पेज: ${if (prefs.home == "google") "Google" else "दिशा होम"}",
            "JavaScript: ${onOff(prefs.javascript)}",
            "विज्ञापन ब्लॉकर: ${onOff(prefs.adBlock)}",
            "रोज़ाना ख़बर व अपडेट नोटिफिकेशन: ${onOff(prefs.notifications)}",
            "थीम: ${themes[prefs.night]}",
            "टेक्स्ट साइज़: ${prefs.textZoom}%",
            "दिशा को डिफ़ॉल्ट ब्राउज़र बनाएँ",
            "ऐप के बारे में"
        )
        AlertDialog.Builder(this)
            .setTitle("सेटिंग्स")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        val keys = Engines.list.keys.toList()
                        AlertDialog.Builder(this).setTitle("सर्च इंजन चुनें")
                            .setSingleChoiceItems(Engines.list.values.toTypedArray(), keys.indexOf(prefs.engine)) { d, w ->
                                prefs.engine = keys[w]; d.dismiss(); showSettings()
                            }.show()
                    }
                    1 -> { prefs.home = if (prefs.home == "google") "disha" else "google"; showSettings() }
                    2 -> {
                        prefs.javascript = !prefs.javascript
                        tabs.forEach { it.web.settings.javaScriptEnabled = prefs.javascript }
                        showSettings()
                    }
                    3 -> { prefs.adBlock = !prefs.adBlock; adBlockOn = prefs.adBlock; showSettings() }
                    4 -> {
                        prefs.notifications = !prefs.notifications
                        DailyWorker.schedule(this, prefs.notifications)
                        showSettings()
                    }
                    5 -> AlertDialog.Builder(this).setTitle("थीम")
                        .setSingleChoiceItems(themes, prefs.night) { d, w ->
                            d.dismiss()
                            prefs.night = w
                            prefs.saveTabs(tabs.filter { !it.incognito }.mapNotNull { it.web.url })
                            prefs.applyNightMode()
                        }.show()
                    6 -> {
                        val sizes = arrayOf(80, 90, 100, 115, 130, 150, 175)
                        AlertDialog.Builder(this).setTitle("टेक्स्ट साइज़")
                            .setSingleChoiceItems(sizes.map { "$it%" }.toTypedArray(), sizes.indexOf(prefs.textZoom)) { d, w ->
                                prefs.textZoom = sizes[w]
                                tabs.forEach { it.web.settings.textZoom = sizes[w] }
                                d.dismiss()
                            }.show()
                    }
                    7 -> try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    } catch (e: Exception) { toast("सेटिंग्स नहीं खुल पाईं") }
                    8 -> AlertDialog.Builder(this).setTitle("दिशा ब्राउज़र")
                        .setMessage("वर्ज़न ${BuildConfig.VERSION_NAME}\n\nतेज़, सुरक्षित और हिंदी में बना ब्राउज़र।\nटैब, इनकॉग्निटो, बुकमार्क, इतिहास, डाउनलोड, विज्ञापन ब्लॉकर, रीडिंग मोड, अनुवाद, वॉइस सर्च, डेस्कटॉप साइट, डार्क मोड और रोज़ाना ख़बरें।")
                        .setPositiveButton("ठीक है", null).show()
                }
            }
            .setNegativeButton("बंद करें", null)
            .show()
    }

    private fun checkUpdate() {
        toast("अपडेट जाँच रहे हैं…")
        thread {
            val r = runCatching { Updates.latest() }.getOrNull()
            runOnUiThread {
                when {
                    r == null -> toast("अभी अपडेट की जानकारी नहीं मिली")
                    r.first > BuildConfig.VERSION_CODE -> AlertDialog.Builder(this)
                        .setTitle("नया वर्ज़न उपलब्ध है")
                        .setMessage("v1.0.${r.first} आ गया है। अभी डाउनलोड करें?")
                        .setPositiveButton("डाउनलोड") { _, _ -> current?.web?.loadUrl(r.second) }
                        .setNegativeButton("बाद में", null).show()
                    else -> toast("आप सबसे नया वर्ज़न इस्तेमाल कर रहे हैं ✓")
                }
            }
        }
    }

    // ---------------- Downloads ----------------

    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) { toast("यह फ़ाइल सीधे डाउनलोड नहीं हो सकती"); return }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            toast("अनुमति देकर फिर से डाउनलोड करें")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                if (mime != null) setMimeType(mime)
                addRequestHeader("User-Agent", ua ?: "")
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(name)
                setDescription("दिशा ब्राउज़र से डाउनलोड")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("डाउनलोड शुरू: $name")
        } catch (e: Exception) {
            toast("डाउनलोड नहीं हो पाया")
        }
    }

    companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
