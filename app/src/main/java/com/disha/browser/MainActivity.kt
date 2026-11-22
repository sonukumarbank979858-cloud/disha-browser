package com.disha.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.os.SystemClock
import android.util.TypedValue
import androidx.core.widget.doAfterTextChanged
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    val tabs = mutableListOf<Tab>()
    val groups = mutableListOf<TabGroup>()
    var current: Tab? = null
        private set
    private lateinit var prefs: Prefs
    private lateinit var store: TabStore
    private lateinit var switcher: TabSwitcher

    private lateinit var root: ChromeFrame
    private lateinit var header: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var urlBox: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var homeBtn: TextView
    private lateinit var micBtn: TextView
    private lateinit var tabBtn: TextView
    private lateinit var menuBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var container: FrameLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private var urlDefaultColors: ColorStateList? = null
    private var menuPopup: PopupWindow? = null
    private val main = Handler(Looper.getMainLooper())

    // hide-on-scroll state for the top toolbar
    private var touching = false
    private var lastTouchUp = 0L
    private var touchDownY = 0f
    private var lastInnerScroll = 0L
    private var scrollAcc = 0f                 // scroll in one direction since the last turn
    private val innerTop = java.util.WeakHashMap<WebView, Boolean>()   // page's own scroll box at its top
    private val innerAtTop: Boolean get() = current?.web?.let { innerTop[it] } ?: true
    private val releaseRunnable = Runnable { if (::root.isInitialized && !touching) root.holdFull = false }
    private val captureRunnable = Runnable { current?.let { if (!switcher.isShowing) capture(it) } }

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

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        prefs.applyNightMode()
        super.onCreate(savedInstanceState)
        store = TabStore(this)
        adBlockOn = prefs.adBlock
        setupSystemBars()
        buildUi()
        Notify.channels(this)
        DailyWorker.schedule(this, prefs.notifications)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }

        restoreTabs()
        // A recreated activity (theme change, process restart) or a launch from Recents gets the
        // old intent again; opening its URL again would add a duplicate tab every time.
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val incoming = if (savedInstanceState == null && !fromHistory) intentUrl(intent) else null
        consumeIntent()
        if (tabs.isNotEmpty()) {
            val cid = store.currentId()
            selectTab(tabs.firstOrNull { it.id == cid } ?: tabs.maxByOrNull { it.lastUsed } ?: tabs.last())
        }
        when {
            incoming != null -> openIncoming(incoming)
            tabs.isEmpty() -> newTab(prefs.homeUrl(), false)   // fresh start: exactly one tab
        }
        store.cleanup(tabs.map { it.id }.toSet())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val c = current
                when {
                    fullscreenView != null -> fullscreenCallback?.onCustomViewHidden()
                    switcher.onBack() -> Unit
                    findBar.visibility == View.VISIBLE -> closeFind()
                    c?.web?.canGoBack() == true -> c?.web?.goBack()
                    c != null && tabs.size > 1 -> closeTab(c)
                    else -> finish()
                }
            }
        })
    }

    private fun isNewTabPage(u: String): Boolean =
        u.isBlank() || u == "about:blank" || u.startsWith(Prefs.HOME) || u == prefs.homeUrl()

    private fun restoreTabs() {
        tabs.clear()
        groups.clear()
        groups.addAll(store.loadGroups())
        val loaded = if (store.hasState()) store.loadTabs() else {
            // migrate the v1 list of open tab URLs (only real pages, no repeats)
            prefs.savedTabs().distinct().filter { !isNewTabPage(it) }.map { u -> Tab(newId(), false).apply { url = u } }
        }
        val seen = HashSet<String>()
        loaded.forEach { t -> if (seen.add(t.id)) tabs.add(t) }   // never restore the same tab twice
        val sp = getSharedPreferences("disha_fix", MODE_PRIVATE)
        if (!sp.getBoolean("tabs_fix_v3", false)) {
            // One-time cleanup of tabs that older versions added by themselves:
            // repeated pages and extra empty "नया टैब" tabs.
            val urls = HashSet<String>()
            val cid = store.currentId()
            tabs.sortByDescending { if (it.id == cid) Long.MAX_VALUE else it.lastUsed }
            val keep = tabs.filter { t -> !isNewTabPage(t.url) && urls.add(t.url) }.toMutableList()
            if (keep.isEmpty()) tabs.firstOrNull()?.let { keep.add(it) }
            val order = tabs.toList()
            tabs.clear()
            tabs.addAll(order.filter { it in keep }.sortedBy { loaded.indexOf(it) })
            store.clearAllThumbs()   // old previews were screenshots of the tab switcher
            sp.edit().putBoolean("tabs_fix_v3", true).apply()
        }
        cleanupGroups()
    }

    /** A link opened from another app or a notification: reuse a tab instead of piling up new ones. */
    private fun openIncoming(url: String) {
        if (switcher.isShowing) switcher.hide()
        tabs.firstOrNull { !it.incognito && it.currentUrl() == url }?.let { selectTab(it); return }
        val c = current
        if (c != null && !c.incognito && isNewTabPage(c.currentUrl()) && c.web?.canGoBack() != true) {
            selectTab(c)
            ensureWeb(c).loadUrl(url)
            c.url = url
            saveState()
            return
        }
        newTab(url, false)
    }

    private fun consumeIntent() {
        setIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(this, MainActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val u = if (fromHistory) null else intentUrl(intent)
        consumeIntent()
        u?.let { openIncoming(it) }
    }

    override fun onPause() {
        super.onPause()
        current?.let { if (!switcher.isShowing) capture(it) }
        saveState()
        CookieManager.getInstance().flush()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (fullscreenView == null) setupSystemBars()
        root.show(false)
        if (switcher.isShowing) switcher.render()
    }

    override fun onDestroy() {
        menuPopup?.dismiss()
        tabs.forEach { it.web?.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    fun saveState() {
        if (!::store.isInitialized) return
        store.save(tabs, groups, current?.takeIf { !it.incognito }?.id)
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

    private fun iconButton(label: String, desc: String, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            minWidth = dp(42)
            contentDescription = desc
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onClick(it) }
        }

    private fun buildUi() {
        root = ChromeFrame(this)
        header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        homeBtn = iconButton("⌂", "होम") { current?.web?.loadUrl(prefs.homeUrl()) }.apply { textSize = 24f }
        urlBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.url_bg)
        }
        urlBar = EditText(this).apply {
            hint = "खोजें या वेब पता लिखें"
            isSingleLine = true
            textSize = 15f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setPadding(dp(14), dp(9), dp(4), dp(9))
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    go(text.toString()); true
                } else false
            }
        }
        urlDefaultColors = urlBar.textColors
        micBtn = iconButton("🎤", "वॉइस सर्च") { startVoice() }.apply { textSize = 16f; minWidth = dp(36) }
        urlBox.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        urlBox.addView(micBtn)

        tabBtn = TextView(this).apply {
            text = "1"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            contentDescription = "खुले टैब देखें"
            setOnClickListener { openSwitcher() }
        }
        val tabBtnBox = FrameLayout(this).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { openSwitcher() }
        }
        tabBtnBox.addView(tabBtn, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        menuBtn = iconButton("⋮", "मेनू") { v -> showMenu(v) }.apply { textSize = 22f }

        topBar.addView(homeBtn)
        topBar.addView(urlBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(2) })
        topBar.addView(tabBtnBox)
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
        findBar.addView(iconButton("▲", "पिछला") { current?.web?.findNext(false) })
        findBar.addView(iconButton("▼", "अगला") { current?.web?.findNext(true) })
        findBar.addView(iconButton("✕", "बंद करें") { closeFind() })

        container = FrameLayout(this)
        swipe = SwipeRefreshLayout(this).apply {
            addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setColorSchemeColors(ContextCompat.getColor(this@MainActivity, R.color.brand))
            setOnRefreshListener { current?.web?.reload() }
            // Pull-to-refresh only when the page really is at its top: the page itself AND the
            // page's own scroll box (sites that scroll inside a div keep scrollY at 0, which made
            // scrolling up on them start a refresh = page jumping / hanging).
            setOnChildScrollUpCallback { _, _ ->
                val w = current?.web
                w == null || w.scrollY > 0 || w.canScrollVertically(-1) || !innerAtTop ||
                    SystemClock.uptimeMillis() - lastInnerScroll < 400
            }
            setDistanceToTriggerSync(dp(140))
        }

        // The progress line sits over the bottom edge of the toolbar, so showing or hiding it
        // never changes the toolbar height (no jump of the page).
        val topBox = FrameLayout(this)
        topBox.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        topBox.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM))
        header.addView(topBox)
        header.addView(findBar)
        header.isClickable = true   // touches on the toolbar never fall through to the page
        root.setup(swipe, header)
        root.enabledHide = prefs.hideToolbar
        urlBar.setOnFocusChangeListener { _, has -> updateToolbarLock(); if (has) root.show(true) }

        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Safety net: if the window is ever laid out edge-to-edge (some ROMs / Android 15),
        // keep the browser out of the status bar, navigation bar and display cutout.
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        switcher = TabSwitcher(this)
        frame.addView(switcher.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun displayUrl(url: String?): String {
        if (url == null || url.startsWith(Prefs.HOME) || url == "about:blank") return ""
        if (url.startsWith("file:///android_asset/search.html")) {
            return Uri.parse(url).getQueryParameter("q") ?: ""
        }
        return url
    }

    private fun applyToolbarColors() {
        val t = current
        val incog = t?.incognito == true
        // the toolbar now floats over the page, so it needs a solid background
        header.setBackgroundColor(if (incog) Color.parseColor("#2B2B2B") else themeBackground())
        applyStatusBarColor()
        if (incog) {
            topBar.setBackgroundColor(Color.parseColor("#2B2B2B"))
            urlBar.setTextColor(Color.WHITE)
            urlBar.setHintTextColor(Color.LTGRAY)
        } else {
            topBar.setBackgroundColor(Color.TRANSPARENT)
            urlDefaultColors?.let { urlBar.setTextColor(it) }
            urlBar.setHintTextColor(Color.GRAY)
        }
        val c = if (incog) Color.WHITE else urlBar.currentTextColor
        listOf(homeBtn, micBtn, tabBtn, menuBtn).forEach { it.setTextColor(c) }
        tabBtn.background = rounded(Color.TRANSPARENT, dp(5).toFloat(), dp(2), c)
    }

    // ---------------- System bars + hide-on-scroll toolbar ----------------

    private fun themeBackground(): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.colorBackground, tv, true) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT)
            tv.data else if (isNight()) Color.parseColor("#121212") else Color.WHITE
    }

    /** Normal (not edge-to-edge) window: pages never draw under the status/navigation bar or a cutout. */
    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        c.show(WindowInsetsCompat.Type.systemBars())
        applyStatusBarColor()
    }

    private fun applyStatusBarColor() {
        val incog = current?.incognito == true
        @Suppress("DEPRECATION")
        window.statusBarColor = if (incog) Color.parseColor("#2B2B2B") else ContextCompat.getColor(this, R.color.brand_dark)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
    }

    private fun updateToolbarLock() {
        if (!::root.isInitialized) return
        root.lockShown = urlBar.hasFocus() || findBar.visibility == View.VISIBLE
    }

    /** A finger on the page (or the fling right after it) — programmatic scrolls do not move the toolbar. */
    private fun userScrolling(): Boolean = touching || SystemClock.uptimeMillis() - lastTouchUp < 2500

    /** Scroll changes caused by our own toolbar animation / resize are never user scrolls. */
    private fun ownLayoutChange(): Boolean =
        root.isAnimating || SystemClock.uptimeMillis() - root.layoutStamp < 350

    private fun toolbarActive(w: WebView): Boolean =
        w == current?.web && !switcher.isShowing && fullscreenView == null && root.enabledHide

    /**
     * Direction with hysteresis: the toolbar only turns after [TURN_DP] of scrolling in one
     * direction, and only once per turn (no per-pixel following, no per-frame layout).
     */
    private fun onScrollDelta(dyPx: Float, w: WebView) {
        if (ownLayoutChange() || !userScrolling()) { scrollAcc = 0f; return }
        if (dyPx == 0f) return
        if ((dyPx > 0) != (scrollAcc > 0)) scrollAcc = 0f
        scrollAcc += dyPx
        val turn = dp(TURN_DP).toFloat()
        if (scrollAcc >= turn) {
            scrollAcc = 0f
            if (!root.isHidden && roomBelow(w)) root.hide()
        } else if (scrollAcc <= -turn) {
            scrollAcc = 0f
            if (root.isHidden) root.show(true)
        }
    }

    /** Hiding grows the page by the toolbar height; near the very end that would clamp (jump) it. */
    private fun roomBelow(w: WebView): Boolean {
        if (root.isContentFull) return true
        @Suppress("DEPRECATION")
        val total = w.contentHeight * w.scale
        if (total <= 0f) return true
        return total - (w.scrollY + w.height) > root.headerHeight()
    }

    private fun onPageScrolled(w: WebView, dy: Int, y: Int) {
        if (!toolbarActive(w)) return
        if (y <= 0) { scrollAcc = 0f; root.show(true); return }      // reached the top of the page
        onScrollDelta(dy.toFloat(), w)
    }

    private fun onWebTouch(w: WebView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                touchDownY = e.rawY
                scrollAcc = 0f
                main.removeCallbacks(releaseRunnable)
                root.holdFull = true      // no resize of the page while a finger is on it
            }
            MotionEvent.ACTION_MOVE -> {
                // Finger pulled down on a page that cannot scroll up any more (short page, or a
                // site that scrolls inside its own box and is at its top): bring the toolbar back.
                if (e.rawY - touchDownY > dp(24) && root.isHidden && !w.canScrollVertically(-1) &&
                    innerAtTop && SystemClock.uptimeMillis() - lastInnerScroll > 300) {
                    root.show(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                lastTouchUp = SystemClock.uptimeMillis()
                main.removeCallbacks(releaseRunnable)
                main.postDelayed(releaseRunnable, 300)
            }
        }
    }

    /**
     * Reports from the page's own scroll boxes (e.g. some Google / web-app pages). The JS side is
     * throttled: it only calls on a direction turn (after [TURN_DP] css px) or a top/not-top change.
     */
    inner class ScrollBridge(private val w: WebView) {
        @JavascriptInterface
        fun dir(d: Int) {
            main.post {
                lastInnerScroll = SystemClock.uptimeMillis()
                if (!toolbarActive(w) || ownLayoutChange() || !userScrolling()) return@post
                if (d > 0) { if (!root.isHidden) root.hide() } else if (root.isHidden) root.show(true)
            }
        }

        @JavascriptInterface
        fun top(atTop: Boolean) {
            main.post {
                innerTop[w] = atTop
                if (w != current?.web) return@post
                if (!atTop) lastInnerScroll = SystemClock.uptimeMillis()
                if (atTop && toolbarActive(w)) root.show(true)
            }
        }
    }

    private fun injectScrollWatcher(w: WebView) {
        if (!w.settings.javaScriptEnabled) return
        w.evaluateJavascript(SCROLL_JS, null)
    }

    // ---------------- Tabs ----------------

    fun tabTitle(t: Tab): String {
        val u = t.currentUrl()
        if (u.startsWith(Prefs.HOME) || u == "about:blank" || u.isBlank()) return "नया टैब"
        val title = t.currentTitle()
        return if (title.isBlank() || title.endsWith(".html")) hostOf(t).ifBlank { "नया टैब" } else title
    }

    fun hostOf(t: Tab): String {
        val u = t.currentUrl()
        if (u.startsWith("file:///android_asset/search.html")) return "दिशा सर्च"
        if (u.startsWith("file:") || u.startsWith("about:")) return "दिशा"
        return Uri.parse(u).host?.removePrefix("www.") ?: u
    }

    fun thumbOf(t: Tab): Bitmap? {
        if (t.thumb == null && !t.thumbLoaded && !t.incognito) t.thumb = store.loadThumb(t.id)
        t.thumbLoaded = true
        return t.thumb
    }

    fun faviconOf(t: Tab): Bitmap? {
        if (t.favicon == null) t.web?.favicon?.let { t.favicon = it }
        if (t.favicon == null && !t.favLoaded && !t.incognito) t.favicon = store.loadFavicon(t.id)
        t.favLoaded = true
        return t.favicon
    }

    fun groupMembers(g: TabGroup): List<Tab> = tabs.filter { it.groupId == g.id }

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
        w.addJavascriptInterface(ScrollBridge(w), "DishaScroll")
        w.setOnScrollChangeListener { v, _, y, _, oldY -> onPageScrolled(v as WebView, y - oldY, y) }
        w.setOnTouchListener { v, e -> onWebTouch(v as WebView, e); false }
        w.setFindListener { active, count, _ ->
            findCount.text = if (count == 0) "0/0" else "${active + 1}/$count"
        }
        return w
    }

    private fun ensureWeb(t: Tab): WebView {
        t.web?.let { return it }
        val w = createWebView(t.incognito)
        if (t.desktop) w.settings.userAgentString = DESKTOP_UA
        t.web = w
        w.loadUrl(t.url.ifBlank { prefs.homeUrl() })
        return w
    }

    private fun tabOf(v: WebView?): Tab? = tabs.firstOrNull { it.web == v }

    fun newTab(url: String, incognito: Boolean, groupId: String? = null, select: Boolean = true) {
        val t = Tab(newId(), incognito)
        t.url = url
        val g = if (incognito) null else groupId?.let { id -> groups.firstOrNull { it.id == id } }
        t.groupId = g?.id
        val idx = if (g != null) tabs.indexOfLast { it.groupId == g.id } else -1
        if (idx >= 0) tabs.add(idx + 1, t) else tabs.add(t)
        g?.updated = System.currentTimeMillis()
        if (select) {
            ensureWeb(t)
            selectTab(t)
            if (incognito) toast("इनकॉग्निटो टैब: इतिहास और कैश सेव नहीं होगा")
        } else updateTabCount()
        saveState()
    }

    fun selectTab(t: Tab) {
        val old = current
        if (old != null && old != t && old.web?.parent == container && !switcher.isShowing) capture(old)
        val w = ensureWeb(t)
        container.removeAllViews()
        (w.parent as? ViewGroup)?.removeView(w)
        container.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        current = t
        t.lastUsed = System.currentTimeMillis()
        urlBar.setText(displayUrl(w.url ?: t.url))
        if (findBar.visibility == View.VISIBLE) closeFind()
        applyToolbarColors()
        updateTabCount()
        touching = false
        root.show(false)
    }

    fun closeTab(t: Tab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        if (!t.incognito) {
            val u = t.currentUrl()
            if (u.startsWith("http")) prefs.addEntry("closed", tabTitle(t), u, 25)
        }
        tabs.removeAt(idx)
        t.web?.let { container.removeView(it); it.destroy() }
        t.web = null
        store.deleteFiles(t.id)
        t.groupId?.let { gid -> groups.firstOrNull { it.id == gid }?.updated = System.currentTimeMillis() }
        cleanupGroups()
        if (current == t) {
            current = null
            if (!switcher.isShowing) {
                val sameMode = tabs.filter { it.incognito == t.incognito }
                when {
                    sameMode.isNotEmpty() -> selectTab(sameMode[minOf(idx, sameMode.size - 1).coerceAtLeast(0)])
                    tabs.isNotEmpty() -> selectTab(tabs.maxByOrNull { it.lastUsed } ?: tabs.last())
                    else -> newTab(prefs.homeUrl(), false)
                }
            }
        }
        updateTabCount()
        saveState()
    }

    fun closeAllTabs(incognito: Boolean, done: () -> Unit) {
        val list = tabs.filter { it.incognito == incognito }
        if (list.isEmpty()) { done(); return }
        AlertDialog.Builder(this)
            .setMessage(if (incognito) "सभी ${list.size} इनकॉग्निटो टैब बंद करें?" else "सभी ${list.size} टैब बंद करें?")
            .setPositiveButton("बंद करें") { _, _ -> list.forEach { closeTab(it) }; done() }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    private fun updateTabCount() {
        val incog = current?.incognito == true
        val n = tabs.count { it.incognito == incog }
        tabBtn.text = if (n > 99) ":D" else n.toString()
    }

    private fun cleanupGroups() {
        groups.removeAll { g -> tabs.none { it.groupId == g.id } }
        tabs.forEach { t -> if (t.groupId != null && groups.none { it.id == t.groupId }) t.groupId = null }
    }

    /**
     * Takes a small picture of the page for the tab card by drawing only the WebView into a bitmap
     * (never the window, so the tab switcher or menus can never end up in the preview).
     */
    private fun capture(t: Tab, done: (() -> Unit)? = null) {
        val w = t.web
        if (w != null && w.isAttachedToWindow && w.width > 0 && w.height > 0 && fullscreenView == null) {
            try {
                val scale = 0.45f
                val bmp = Bitmap.createBitmap(maxOf(1, (w.width * scale).toInt()), maxOf(1, (w.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.WHITE)
                c.scale(scale, scale)
                c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
                w.draw(c)
                t.thumb = bmp
                t.thumbLoaded = true
                if (!t.incognito) store.saveThumb(t.id, bmp)
            } catch (e: Throwable) { }
        }
        done?.invoke()
    }

    fun openSwitcher() {
        menuPopup?.dismiss()
        if (switcher.isShowing) return
        hideKeyboard(urlBar)
        urlBar.clearFocus()
        val mode = if (current?.incognito == true) TabSwitcher.INCOGNITO else TabSwitcher.NORMAL
        current?.let { capture(it) }   // picture of the page first, then show the switcher
        switcher.show(mode)
    }

    fun closeSwitcher() {
        switcher.hide()
        val c = current
        if (c != null && tabs.contains(c)) { selectTab(c); return }
        val pref = tabs.filter { it.incognito == (switcher.mode == TabSwitcher.INCOGNITO) }
        when {
            pref.isNotEmpty() -> selectTab(pref.maxByOrNull { it.lastUsed } ?: pref.last())
            tabs.isNotEmpty() -> selectTab(tabs.maxByOrNull { it.lastUsed } ?: tabs.last())
            else -> newTab(prefs.homeUrl(), false)
        }
    }

    fun selectFromSwitcher(t: Tab) {
        switcher.hide()
        selectTab(t)
    }

    fun newTabFromSwitcher(incognito: Boolean, groupId: String?) {
        switcher.hide()
        newTab(prefs.homeUrl(), incognito, groupId)
    }

    // ---------------- Tab groups ----------------

    private fun dot(color: Int, text: String): CharSequence {
        val sb = SpannableStringBuilder("●  ")
        sb.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(text)
        return sb
    }

    fun moveToGroup(t: Tab, g: TabGroup) {
        if (t.incognito) return
        t.groupId = g.id
        tabs.remove(t)
        val idx = tabs.indexOfLast { it.groupId == g.id }
        if (idx >= 0) tabs.add(idx + 1, t) else tabs.add(t)
        g.updated = System.currentTimeMillis()
        cleanupGroups()
        saveState()
    }

    fun removeFromGroup(t: Tab) {
        val gid = t.groupId ?: return
        t.groupId = null
        groups.firstOrNull { it.id == gid }?.updated = System.currentTimeMillis()
        cleanupGroups()
        saveState()
        toast("टैब ग्रुप से हटा दिया")
    }

    fun addToGroupDialog(t: Tab?, done: () -> Unit = {}) {
        if (t == null) return
        if (t.incognito) { toast("इनकॉग्निटो टैब ग्रुप में नहीं जोड़े जा सकते"); return }
        val others = groups.filter { it.id != t.groupId }
        val items = mutableListOf<CharSequence>("＋  नया टैब ग्रुप")
        others.forEach { g -> items.add(dot(g.color, "${g.name.ifBlank { "बिना नाम" }}  (${groupMembers(g).size} टैब)")) }
        AlertDialog.Builder(this)
            .setTitle("टैब को ग्रुप में जोड़ें")
            .setItems(items.toTypedArray()) { _, i ->
                if (i == 0) newGroupDialog(t, done)
                else {
                    val g = others[i - 1]
                    moveToGroup(t, g)
                    toast("“${g.name}” ग्रुप में जोड़ दिया")
                    done()
                }
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    private fun colorPicker(initial: Int): Pair<View, () -> Int> {
        var sel = GroupColors.values.indexOf(initial).coerceAtLeast(0)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(4)) }
        val dots = mutableListOf<View>()
        fun refresh() {
            dots.forEachIndexed { i, v ->
                v.background = circle(GroupColors.values[i], if (i == sel) dp(4) else 0, if (isNight()) Color.WHITE else Color.BLACK)
            }
        }
        GroupColors.values.forEachIndexed { i, _ ->
            val v = View(this).apply {
                contentDescription = GroupColors.names[i]
                setOnClickListener { sel = i; refresh() }
            }
            dots.add(v)
            row.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        }
        refresh()
        val hs = HorizontalScrollView(this).apply { addView(row) }
        return Pair(hs, { GroupColors.values[sel] })
    }

    private fun newGroupDialog(t: Tab, done: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        val name = EditText(this).apply {
            hint = "ग्रुप का नाम"
            isSingleLine = true
            setText(t.currentTitle().takeIf { it.isNotBlank() && !it.endsWith(".html") }?.take(20) ?: "ग्रुप ${groups.size + 1}")
            setSelectAllOnFocus(true)
        }
        val (picker, color) = colorPicker(GroupColors.values[(groups.size + 1) % GroupColors.values.size])
        box.addView(name)
        box.addView(picker)
        AlertDialog.Builder(this)
            .setTitle("नया टैब ग्रुप")
            .setView(box)
            .setPositiveButton("बनाएँ") { _, _ ->
                val g = TabGroup(newId(), name.text.toString().trim().ifBlank { "ग्रुप ${groups.size + 1}" }, color(), System.currentTimeMillis())
                groups.add(g)
                moveToGroup(t, g)
                toast("“${g.name}” ग्रुप बन गया")
                done()
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    fun renameGroupDialog(g: TabGroup, done: () -> Unit) {
        val input = EditText(this).apply {
            setText(g.name)
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("ग्रुप का नाम बदलें")
            .setView(box)
            .setPositiveButton("सेव करें") { _, _ ->
                g.name = input.text.toString().trim()
                g.updated = System.currentTimeMillis()
                saveState(); done()
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    fun colorDialog(g: TabGroup, done: () -> Unit) {
        val items = GroupColors.values.mapIndexed { i, c -> dot(c, GroupColors.names[i]) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ग्रुप का रंग बदलें")
            .setSingleChoiceItems(items, GroupColors.values.indexOf(g.color)) { d, i ->
                g.color = GroupColors.values[i]
                g.updated = System.currentTimeMillis()
                saveState(); d.dismiss(); done()
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    fun ungroup(g: TabGroup) {
        tabs.forEach { if (it.groupId == g.id) it.groupId = null }
        groups.remove(g)
        saveState()
        toast("ग्रुप हटा दिया, टैब खुले हैं")
    }

    fun closeGroup(g: TabGroup, done: () -> Unit) {
        val members = groupMembers(g)
        AlertDialog.Builder(this)
            .setMessage("“${g.name.ifBlank { "ग्रुप" }}” के ${members.size} टैब बंद करें?")
            .setPositiveButton("ग्रुप बंद करें") { _, _ ->
                members.forEach { closeTab(it) }
                groups.remove(g)
                saveState(); done()
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    // ---------------- Navigation ----------------

    private fun go(input: String) {
        val w = current?.web ?: run { newTab(toUrl(input), false); return }
        w.loadUrl(toUrl(input))
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
            val t = tabOf(view)
            if (t != null && url != null) t.url = url
            if (view == current?.web && !urlBar.hasFocus()) urlBar.setText(displayUrl(url))
            innerTop[view] = true
            if (view == current?.web) { scrollAcc = 0f; root.show(true) }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            injectScrollWatcher(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            swipe.isRefreshing = false
            val t = tabOf(view) ?: return
            if (url != null) t.url = url
            view.title?.let { if (it.isNotBlank()) t.title = it }
            if (view == current?.web && !urlBar.hasFocus()) urlBar.setText(displayUrl(url))
            injectScrollWatcher(view)
            if (!t.incognito && url != null && url.startsWith("http")) {
                prefs.addEntry("history", view.title ?: url, url, 500)
            }
            t.groupId?.let { gid -> groups.firstOrNull { it.id == gid }?.updated = System.currentTimeMillis() }
            if (!t.incognito) saveState()
            if (view == current?.web) {
                main.removeCallbacks(captureRunnable)       // SPA pages fire this often: one capture
                main.postDelayed(captureRunnable, 1200)
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

        override fun onReceivedTitle(view: WebView, title: String?) {
            val t = tabOf(view) ?: return
            if (!title.isNullOrBlank()) t.title = title
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            val t = tabOf(view) ?: return
            if (icon == null) return
            t.favicon = icon
            t.favLoaded = true
            if (!t.incognito) store.saveFavicon(t.id, icon)
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
            setupSystemBars()
            root.show(false)
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

    // ---------------- Main ⋮ menu (Chrome style) ----------------

    private fun showMenu(anchor: View) {
        menuPopup?.dismiss()
        root.show(false)
        val t = current
        val w = t?.web
        val pal = Palette(isNight() || t?.incognito == true)
        val pop = PopupWindow(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }

        // top icon row: पीछे, आगे, बुकमार्क, डाउनलोड, रीफ़्रेश
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun top(sym: String, desc: String, enabled: Boolean, color: Int = pal.text, action: () -> Unit) {
            val v = TextView(this).apply {
                text = sym
                textSize = 21f
                gravity = Gravity.CENTER
                setTextColor(color)
                contentDescription = desc
                alpha = if (enabled) 1f else 0.3f
                setPadding(0, dp(10), 0, dp(10))
                if (enabled) setOnClickListener { pop.dismiss(); action() }
                setOnLongClickListener { toast(desc); true }
            }
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val url = pageUrl()
        val starred = url != null && prefs.list("bookmarks").any { it.url == url }
        top("←", "पीछे", w?.canGoBack() == true) { w?.goBack() }
        top("→", "आगे", w?.canGoForward() == true) { w?.goForward() }
        top(if (starred) "★" else "☆", if (starred) "बुकमार्क हटाएँ" else "बुकमार्क करें", url != null,
            if (starred) ContextCompat.getColor(this, R.color.brand) else pal.text) { toggleBookmark() }
        top("⤓", "पेज डाउनलोड करें", url != null) { downloadPage() }
        top("⟳", "रीफ़्रेश", w != null) { w?.reload() }
        box.addView(row)

        fun divider() {
            box.addView(View(this).apply { setBackgroundColor(pal.divider) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(4), 0, dp(4)) })
        }
        fun item(icon: String, label: String, checked: Boolean? = null, action: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(11), dp(12), dp(11))
                isClickable = true
                setOnClickListener { pop.dismiss(); action() }
            }
            r.addView(TextView(this).apply {
                text = icon
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(pal.muted)
            }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
            r.addView(TextView(this).apply {
                text = label
                textSize = 15.5f
                setTextColor(pal.text)
                setPadding(dp(14), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (checked != null) {
                r.addView(CheckBox(this).apply {
                    isChecked = checked
                    isClickable = false
                    isFocusable = false
                    buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.brand))
                })
            }
            box.addView(r)
        }

        divider()
        item("＋", "नया टैब") { newTab(prefs.homeUrl(), false) }
        item("🕶", "नया इनकॉग्निटो टैब") { newTab(prefs.homeUrl(), true) }
        item("▦", "टैब को ग्रुप में जोड़ें") { addToGroupDialog(current) }
        divider()
        item("🕘", "इतिहास") { showList("इतिहास", "history") }
        item("🗑", "ब्राउज़िंग डेटा हटाएँ") { clearData() }
        divider()
        item("⤓", "डाउनलोड") { openDownloads() }
        item("★", "बुकमार्क") { showList("बुकमार्क", "bookmarks") }
        item("🗗", "हाल के टैब") { recentTabs() }
        divider()
        item("↗", "शेयर करें") { share() }
        item("🔍", "पेज में खोजें") { openFind() }
        item("文", "अनुवाद करें") { translate() }
        item("📖", "रीडिंग मोड") { readingMode() }
        item("🖥", "डेस्कटॉप साइट", t?.desktop == true) { toggleDesktop() }
        item("📲", "होम स्क्रीन पर जोड़ें") { addShortcut() }
        divider()
        item("⚙", "सेटिंग्स") { showSettings() }
        item("🏠", "नया टैब पेज बदलें") { newTabPageDialog() }
        item("❓", "सहायता और फ़ीडबैक") { helpDialog() }

        val scroll = ScrollView(this).apply { addView(box) }
        box.measure(View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val maxH = resources.displayMetrics.heightPixels - dp(90)
        pop.contentView = scroll
        pop.width = dp(280)
        pop.height = minOf(box.measuredHeight, maxH)
        pop.isFocusable = true
        pop.isOutsideTouchable = true
        pop.setBackgroundDrawable(rounded(pal.card, dp(14).toFloat()))
        pop.elevation = dp(10).toFloat()
        pop.setOnDismissListener { menuPopup = null }
        menuPopup = pop
        pop.showAsDropDown(anchor, -dp(4), -anchor.height, Gravity.END)
    }

    private fun pageUrl(): String? = current?.web?.url?.takeIf { it.startsWith("http") }

    private fun toggleBookmark() {
        val w = current?.web ?: return
        val url = pageUrl() ?: run { toast("यह पेज बुकमार्क नहीं हो सकता"); return }
        val list = prefs.list("bookmarks")
        if (list.any { it.url == url }) {
            list.removeAll { it.url == url }
            prefs.saveList("bookmarks", list)
            toast("बुकमार्क हटा दिया")
        } else {
            prefs.addEntry("bookmarks", w.title ?: url, url, 1000)
            toast("बुकमार्क जोड़ दिया ★")
        }
    }

    private fun openDownloads() {
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (e: Exception) { toast("डाउनलोड फ़ोल्डर नहीं खुल पाया") }
    }

    private fun showList(title: String, key: String) {
        val list = prefs.list(key)
        if (list.isEmpty()) { toast("अभी कुछ नहीं है"); return }
        val labels = list.map { "${it.title}\n${it.url}" }.toTypedArray()
        val d = AlertDialog.Builder(this)
            .setTitle("$title — हटाने के लिए देर तक दबाएँ")
            .setItems(labels) { _, i -> openUrl(list[i].url) }
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

    private fun openUrl(url: String) {
        val w = current?.web
        if (w == null) newTab(url, false) else w.loadUrl(url)
    }

    private fun recentTabs() {
        val closed = prefs.list("closed")
        if (closed.isEmpty()) { toast("हाल में कोई टैब बंद नहीं किया गया"); return }
        val labels = closed.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("हाल ही में बंद किए गए टैब")
            .setItems(labels) { _, i ->
                val e = closed.removeAt(i)
                prefs.saveList("closed", closed)
                newTab(e.url, false)
            }
            .setPositiveButton("सब फिर खोलें") { _, _ ->
                closed.drop(1).reversed().forEach { newTab(it.url, false, select = false) }
                newTab(closed.first().url, false)
                prefs.saveList("closed", emptyList())
            }
            .setNeutralButton("सूची साफ़ करें") { _, _ -> prefs.saveList("closed", emptyList()) }
            .setNegativeButton("बंद करें", null)
            .show()
    }

    private fun openFind() {
        if (current?.web == null) return
        findBar.visibility = View.VISIBLE
        updateToolbarLock()
        findInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        current?.web?.clearMatches()
        findInput.setText("")
        findBar.visibility = View.GONE
        hideKeyboard(findInput)
        updateToolbarLock()
    }

    fun shareUrl(url: String, title: String) {
        if (!url.startsWith("http")) { toast("यह पेज शेयर नहीं हो सकता"); return }
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(i, "शेयर करें"))
    }

    private fun share() {
        val url = pageUrl() ?: run { toast("यह पेज शेयर नहीं हो सकता"); return }
        shareUrl(url, current?.web?.title ?: "")
    }

    private fun translate() {
        val url = pageUrl() ?: run { toast("इस पेज का अनुवाद नहीं हो सकता"); return }
        val names = arrayOf("हिंदी", "अंग्रेज़ी (English)", "पंजाबी", "बंगाली", "उर्दू", "मराठी")
        val codes = arrayOf("hi", "en", "pa", "bn", "ur", "mr")
        AlertDialog.Builder(this)
            .setTitle("किस भाषा में अनुवाद करें?")
            .setItems(names) { _, i ->
                current?.web?.loadUrl("https://translate.google.com/translate?sl=auto&tl=${codes[i]}&u=" + URLEncoder.encode(url, "UTF-8"))
            }
            .setNegativeButton("रद्द करें", null)
            .show()
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
        val w = t.web ?: return
        t.desktop = !t.desktop
        w.settings.userAgentString = if (t.desktop) DESKTOP_UA else null
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = true
        w.reload()
        saveState()
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
        val ranges = arrayOf("पिछला 1 घंटा", "पिछले 24 घंटे", "पिछले 7 दिन", "पिछले 4 हफ़्ते", "हर समय")
        val spans = longArrayOf(3600_000L, 86400_000L, 7 * 86400_000L, 28 * 86400_000L, Long.MAX_VALUE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(TextView(this).apply { text = "समय सीमा"; textSize = 14f; setTypeface(typeface, Typeface.BOLD) })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ranges)
            setSelection(1)
        }
        box.addView(spinner)
        val cHistory = CheckBox(this).apply { text = "ब्राउज़िंग इतिहास"; isChecked = true }
        val cCookies = CheckBox(this).apply { text = "कुकीज़ और साइट डेटा\n(ज़्यादातर साइटों से साइन आउट हो जाएँगे)"; isChecked = true }
        val cCache = CheckBox(this).apply { text = "कैश की गई इमेज और फ़ाइलें"; isChecked = true }
        val cBook = CheckBox(this).apply { text = "बुकमार्क"; isChecked = false }
        listOf(cHistory, cCookies, cCache, cBook).forEach { box.addView(it) }
        box.addView(TextView(this).apply {
            text = "ध्यान दें: कुकीज़ और कैश समय सीमा चाहे जो हो, पूरे हटते हैं (Android WebView में समय के हिसाब से हटाने की सुविधा नहीं है)।"
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(6), 0, dp(4))
        })
        AlertDialog.Builder(this)
            .setTitle("ब्राउज़िंग डेटा हटाएँ")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("डेटा हटाएँ") { _, _ ->
                val span = spans[spinner.selectedItemPosition.coerceIn(0, spans.size - 1)]
                val cutoff = if (span == Long.MAX_VALUE) 0L else System.currentTimeMillis() - span
                if (cHistory.isChecked) {
                    prefs.removeSince("history", cutoff)
                    prefs.removeSince("closed", cutoff)
                    if (cutoff == 0L) tabs.forEach { it.web?.clearHistory() }
                }
                if (cCookies.isChecked) {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                }
                if (cCache.isChecked) {
                    (current?.web ?: tabs.firstNotNullOfOrNull { it.web })?.clearCache(true)
                    cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
                }
                if (cBook.isChecked) prefs.removeSince("bookmarks", cutoff)
                toast("${ranges[spinner.selectedItemPosition]} का डेटा हटा दिया गया")
            }
            .setNegativeButton("रद्द करें", null)
            .show()
    }

    private fun newTabPageDialog() {
        val names = arrayOf("दिशा होम (डिफ़ॉल्ट)", "Google", "खाली पेज", "अपना पेज (वेब पता)")
        val keys = arrayOf("disha", "google", "blank", "custom")
        AlertDialog.Builder(this)
            .setTitle("नया टैब पेज")
            .setSingleChoiceItems(names, keys.indexOf(prefs.home).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                if (keys[i] == "custom") {
                    val input = EditText(this).apply {
                        hint = "https://…"
                        isSingleLine = true
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        setText(prefs.homeCustom)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("नए टैब में कौन सा पेज खुले?")
                        .setView(FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(input) })
                        .setPositiveButton("सेव करें") { _, _ ->
                            val u = toUrl(input.text.toString())
                            prefs.homeCustom = u
                            prefs.home = "custom"
                            toast("नया टैब पेज बदल दिया")
                        }
                        .setNegativeButton("रद्द करें", null)
                        .show()
                } else {
                    prefs.home = keys[i]
                    toast("नया टैब पेज: ${names[i]}")
                }
            }
            .setNegativeButton("बंद करें", null)
            .show()
    }

    private fun helpDialog() {
        val items = arrayOf("ब्राउज़र कैसे इस्तेमाल करें", "फ़ीडबैक भेजें / समस्या बताएँ", "अपडेट जाँचें", "आज की ताज़ा ख़बरें", "ऐप के बारे में")
        AlertDialog.Builder(this)
            .setTitle("सहायता और फ़ीडबैक")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> AlertDialog.Builder(this).setTitle("मदद")
                        .setMessage(
                            "• ऊपर पता-बार में कुछ भी लिखें या 🎤 दबाकर बोलें।\n" +
                            "• टैब बटन (चौकोर अंक) से सारे टैब ग्रिड में दिखते हैं — ऊपर “टैब खोजें”, बीच में सामान्य / 🕶 इनकॉग्निटो / ▦ ग्रुप।\n" +
                            "• टैब कार्ड को देर तक दबाकर उसे ग्रुप में जोड़ें। ग्रुप का नाम, रंग ⋮ से बदलें।\n" +
                            "• ⋮ मेनू में ऊपर पीछे, आगे, ☆ बुकमार्क, ⤓ पेज डाउनलोड और ⟳ रीफ़्रेश।\n" +
                            "• पेज में खोजें, अनुवाद, डेस्कटॉप साइट, होम स्क्रीन शॉर्टकट भी ⋮ मेनू में हैं।\n" +
                            "• नीचे खींचकर (pull down) पेज रीफ़्रेश करें।")
                        .setPositiveButton("ठीक है", null).show()
                    1 -> newTab("https://github.com/${Updates.REPO}/issues/new", false)
                    2 -> checkUpdate()
                    3 -> newTab(News.PAGE, false)
                    4 -> about()
                }
            }
            .setNegativeButton("बंद करें", null)
            .show()
    }

    private fun about() {
        AlertDialog.Builder(this).setTitle("दिशा ब्राउज़र")
            .setMessage("वर्ज़न ${BuildConfig.VERSION_NAME}\n\nतेज़, सुरक्षित और हिंदी में बना ब्राउज़र।\nChrome जैसा टैब ग्रिड, टैब ग्रुप, इनकॉग्निटो, बुकमार्क, इतिहास, डाउनलोड, विज्ञापन ब्लॉकर, रीडिंग मोड, अनुवाद, वॉइस सर्च, डेस्कटॉप साइट, डार्क मोड और रोज़ाना ख़बरें।")
            .setPositiveButton("ठीक है", null).show()
    }

    private fun onOff(b: Boolean) = if (b) "चालू" else "बंद"

    private fun homeName(): String = when (prefs.home) {
        "google" -> "Google"
        "blank" -> "खाली पेज"
        "custom" -> "अपना पेज"
        else -> "दिशा होम"
    }

    fun showSettings() {
        val themes = arrayOf("सिस्टम जैसा", "लाइट", "डार्क")
        val items = arrayOf(
            "डिफ़ॉल्ट सर्च इंजन: ${Engines.list[prefs.engine]}",
            "नया टैब / होम पेज: ${homeName()}",
            "JavaScript: ${onOff(prefs.javascript)}",
            "विज्ञापन ब्लॉकर: ${onOff(prefs.adBlock)}",
            "रोज़ाना ख़बर व अपडेट नोटिफिकेशन: ${onOff(prefs.notifications)}",
            "थीम: ${themes[prefs.night]}",
            "टेक्स्ट साइज़: ${prefs.textZoom}%",
            "स्क्रॉल करने पर टूलबार छिपाएँ: ${onOff(prefs.hideToolbar)}",
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
                    1 -> newTabPageDialog()
                    2 -> {
                        prefs.javascript = !prefs.javascript
                        tabs.forEach { it.web?.settings?.javaScriptEnabled = prefs.javascript }
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
                            saveState()
                            prefs.applyNightMode()
                        }.show()
                    6 -> {
                        val sizes = arrayOf(80, 90, 100, 115, 130, 150, 175)
                        AlertDialog.Builder(this).setTitle("टेक्स्ट साइज़")
                            .setSingleChoiceItems(sizes.map { "$it%" }.toTypedArray(), sizes.indexOf(prefs.textZoom)) { d, w ->
                                prefs.textZoom = sizes[w]
                                tabs.forEach { it.web?.settings?.textZoom = sizes[w] }
                                d.dismiss()
                            }.show()
                    }
                    7 -> {
                        prefs.hideToolbar = !prefs.hideToolbar
                        root.enabledHide = prefs.hideToolbar
                        showSettings()
                    }
                    8 -> try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    } catch (e: Exception) { toast("सेटिंग्स नहीं खुल पाईं") }
                    9 -> about()
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
                        .setMessage("v${Updates.versionName(r.first)} आ गया है। अभी डाउनलोड करें?")
                        .setPositiveButton("डाउनलोड") { _, _ -> openUrl(r.second) }
                        .setNegativeButton("बाद में", null).show()
                    else -> toast("आप सबसे नया वर्ज़न इस्तेमाल कर रहे हैं ✓")
                }
            }
        }
    }

    // ---------------- Downloads ----------------

    private fun needsStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            toast("अनुमति देकर फिर से डाउनलोड करें")
            return true
        }
        return false
    }

    /** Saves the current page (MHTML) into the phone's Downloads folder. */
    private fun downloadPage() {
        val w = current?.web ?: return
        if (pageUrl() == null) { toast("यह पेज डाउनलोड नहीं हो सकता"); return }
        if (needsStoragePermission()) return
        val base = (w.title ?: "page").replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(60).ifBlank { "page" }
        val name = "$base.mht"
        val tmp = File(cacheDir, "page_${System.currentTimeMillis()}.mht")
        toast("पेज डाउनलोड हो रहा है…")
        w.saveWebArchive(tmp.absolutePath, false) { path ->
            if (path == null || !tmp.exists()) { toast("पेज डाउनलोड नहीं हो पाया"); return@saveWebArchive }
            thread {
                val ok = runCatching { copyToDownloads(tmp, name, "multipart/related") }.getOrDefault(false)
                tmp.delete()
                runOnUiThread { toast(if (ok) "पेज डाउनलोड हो गया: $name" else "पेज डाउनलोड नहीं हो पाया") }
            }
        }
    }

    private fun copyToDownloads(src: File, name: String, mime: String): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
            contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return false
            v.clear()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, v, null, null)
            return true
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        src.copyTo(File(dir, name), overwrite = true)
        return true
    }

    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) { toast("यह फ़ाइल सीधे डाउनलोड नहीं हो सकती"); return }
        if (needsStoragePermission()) return
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
        const val TURN_DP = 24

        /**
         * Watches the page's own scroll boxes (not the page itself). Passive listeners, work done
         * at most once per animation frame, and the bridge is called only when the direction turns
         * (after 24 css px one way) or the box reaches / leaves its top. Ignores scroll caused by a
         * window resize (our toolbar) for 350 ms.
         */
        val SCROLL_JS = """
(function(){if(window.__dishaSc||!window.DishaScroll)return;window.__dishaSc=1;
var B=window.DishaScroll,last=new WeakMap(),acc=0,sentDir=0,sentTop=true,pend=null,raf=0,quiet=0;
function now(){return Date.now()}
function big(t){return t&&t.nodeType===1&&t!==document.documentElement&&t!==document.body&&t.clientHeight>=window.innerHeight*0.5}
window.addEventListener('resize',function(){quiet=now()+350;acc=0;},{passive:true});
document.addEventListener('scroll',function(e){var t=e.target;if(!big(t))return;
var y=t.scrollTop,p=last.has(t)?last.get(t):y;last.set(t,y);pend=t;
if(now()<quiet){acc=0;return}var d=y-p;if(!d)return;
if((d>0)!==(acc>0))acc=0;acc+=d;
if(!raf)raf=requestAnimationFrame(flush);},{capture:true,passive:true});
function flush(){raf=0;var t=pend;if(!t)return;var top=t.scrollTop<=0;
if(top!==sentTop){sentTop=top;try{B.top(top)}catch(x){}}
if(acc>=24&&sentDir!==1){sentDir=1;acc=0;try{B.dir(1)}catch(x){}}
else if(acc<=-24&&sentDir!==-1){sentDir=-1;acc=0;try{B.dir(-1)}catch(x){}}}
document.addEventListener('touchstart',function(e){var n=e.target,top=true;
while(n&&n.nodeType===1&&n!==document.documentElement&&n!==document.body){
if(n.scrollTop>0&&n.scrollHeight>n.clientHeight){top=false;break}n=n.parentNode}
sentDir=0;if(top!==sentTop){sentTop=top;try{B.top(top)}catch(x){}}},{capture:true,passive:true});
})();
"""
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
