package com.downls10

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.Patterns
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.BaseAdapter
import android.text.Editable
import android.text.TextWatcher
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.Locale
import org.json.JSONObject
import kotlin.math.abs

private data class Tab(
    val id: Long,
    val webView: WebView,
    var isHome: Boolean = true,
    var title: String = "صفحة جديدة",
    var desktopMode: Boolean = false,
    // Translation stays enabled for this tab while the user browses.
    var translationEnabled: Boolean = false,
    var translationInProgress: Boolean = false,
    var lastTranslatedUrl: String = "",
    val backStack: MutableList<String> = mutableListOf(),
    val forwardStack: MutableList<String> = mutableListOf(),
    // --- حفظ واستعادة موضع التمرير عند الرجوع/التقدم ---
    /** آخر موضع تمرير (بالبكسل) لكل رابط زاره هذا التبويب */
    val scrollPositions: MutableMap<String, Int> = mutableMapOf(),
    /** true أثناء تحميل صفحة جديدة (لا نسجّل التمرير حينها لأن الصفحة تبدأ من الصفر) */
    var pageLoading: Boolean = false,
    /** الموضع المطلوب استعادته الآن، أو -1 إذا لا توجد استعادة جارية */
    var pendingRestoreY: Int = -1,
    /** رقم يزيد عند بدء/إلغاء كل استعادة لإيقاف المحاولات القديمة */
    var restoreGeneration: Int = 0
)

class BrowserActivity : Activity() {

    companion object {
        const val EXTRA_OPEN_URL = "extra_open_url"
        private const val SEARCH_PROVIDER_KEY = "searchProvider"
        private const val SEARCH_SEARXNG = "searxng"
        private const val SEARCH_GOOGLE = "google"
        private const val SEARCH_VIDEO = "video"
        private const val SEARXNG_SEARCH_BASE = "https://searx.ononoki.org/search?q="
        // SearXNG bang modifiers explicitly select non-Bing web engines.
        // SearXNG's ! syntax is inclusive, so Bing is not selected here.
        private const val SEARXNG_ENGINE_PREFIX = "!ddg !br !qw !yh "
        // محركات الفيديو مدمجة داخل خيار "بحث فيديو" ولا تظهر في واجهة اختيار البحث.
        // يتم تمريرها إلى SearXNG كـ bang modifiers ليجمع نتائجها في صفحة واحدة.
        private const val VIDEO_SEARCH_ENGINE_PREFIX = "!gov !biv !brvid !yt !ptb !dm !od !qwv !ddv !kgv "
    }

    private lateinit var webViewContainer: FrameLayout
    private lateinit var editUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnTabs: Button
    private var suggestionsPopup: PopupWindow? = null
    private var suggestionGeneration = 0
    private var suggestionItems: List<String> = emptyList()

    private val tabs = mutableListOf<Tab>()
    private var currentTabIndex = 0

    private var hideMedia = false
    private var nightMode = false
    private var mobileUA: String = ""
    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

    private var pendingStorageAction: (() -> Unit)? = null
    private val STORAGE_PERM_CODE = 4102
    private val ANDROID_PERM_CODE = 4103
    private val FILE_CHOOSER_CODE = 4104

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingBookmarkExportContent: String? = null
    private var lastConfirmedSafetyUrl: String? = null
    private val bgExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val PREFS = "browser_settings"
    private fun settingsPrefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun selectedSearchProvider(): String =
        settingsPrefs().getString(SEARCH_PROVIDER_KEY, SEARCH_GOOGLE) ?: SEARCH_GOOGLE

    private fun saveSearchProvider(provider: String) {
        settingsPrefs().edit().putString(SEARCH_PROVIDER_KEY, provider).apply()
    }

    private fun savedFontZoom(): Int =
        settingsPrefs().getInt("fontZoom", 100).coerceIn(50, 200)

    private fun saveFontZoom(value: Int) {
        settingsPrefs().edit().putInt("fontZoom", value.coerceIn(50, 200)).apply()
    }

    private fun cameraAllowed() = settingsPrefs().getBoolean("camera", false)
    private fun micAllowed() = settingsPrefs().getBoolean("mic", false)
    private fun locationAllowed() = settingsPrefs().getBoolean("location", false)

    private fun permissionStateText(label: String, enabled: Boolean): String =
        "$label — ${if (enabled) "مفعّل" else "معطّل"}"

    private var pendingAndroidPermissionCallback: ((Boolean) -> Unit)? = null
    private var pendingAndroidPermissionAnyOf: Boolean = false

    /** يطلب أذونات أندرويد المطلوبة ويتحقق من النتيجة قبل متابعة طلب الموقع. */
    private fun ensureAndroidPermissions(
        permissions: Array<String>,
        anyOf: Boolean = false,
        callback: (Boolean) -> Unit
    ) {
        val required = permissions.distinct()
        if (required.isEmpty()) {
            callback(true)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            callback(true)
            return
        }
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            callback(true)
            return
        }
        if (pendingAndroidPermissionCallback != null) {
            callback(false)
            return
        }
        pendingAndroidPermissionCallback = callback
        pendingAndroidPermissionAnyOf = anyOf
        requestPermissions(missing.toTypedArray(), ANDROID_PERM_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ANDROID_PERM_CODE) return
        val callback = pendingAndroidPermissionCallback ?: return
        val anyOf = pendingAndroidPermissionAnyOf
        pendingAndroidPermissionCallback = null
        pendingAndroidPermissionAnyOf = false
        val grantedCount = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
        callback(if (anyOf) grantedCount > 0 else grantResults.isNotEmpty() && grantedCount == grantResults.size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webViewContainer = findViewById(R.id.webViewContainer)
        editUrl = findViewById(R.id.editUrl)
        // لا تفتح لوحة المفاتيح عند تشغيل المتصفح. لا نطلبها إلا بعد لمسة صريحة على حقل العنوان.
        editUrl.clearFocus()
        findViewById<View>(android.R.id.content).apply {
            isFocusableInTouchMode = true
            requestFocus()
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editUrl.setOnClickListener {
            editUrl.requestFocus()
            editUrl.post {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(editUrl, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        progressBar = findViewById(R.id.progressBar)
        val btnBookmarkStar = findViewById<Button>(R.id.btnBookmarkStar)
        val btnForward = findViewById<Button>(R.id.btnForward)
        val btnDownloads = findViewById<Button>(R.id.btnDownloads)
        btnTabs = findViewById(R.id.btnTabs)
        val btnBrowserMenu = findViewById<Button>(R.id.btnBrowserMenu)

        CookieManager.getInstance().setAcceptCookie(true)
        DownloadsRepository.ensureLoaded(this)
        AdBlocker.cleanupRemovedLists(this)
        hideMedia = settingsPrefs().getBoolean("hideMedia", false)
        nightMode = settingsPrefs().getBoolean("nightMode", false)

        editUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                loadFromAddressBar()
                hideKeyboard()
                true
            } else false
        }

        btnBookmarkStar.setOnClickListener { addCurrentPageToBookmarks() }
        btnForward.setOnClickListener {
            val tab = currentTab()
            if (!tab.isHome && tab.webView.canGoForward()) {
                tab.webView.goForward()
            } else {
                Toast.makeText(this, "لا توجد صفحة للتقدّم إليها", Toast.LENGTH_SHORT).show()
            }
        }
        btnTabs.setOnClickListener { showTabsDialog() }
        btnDownloads.setOnClickListener { openDownLS10() }
        btnBrowserMenu.setOnClickListener { showBrowserMenu() }
        setupGoogleSuggestions()

        if (savedInstanceState != null && savedInstanceState.getInt("tabCount", -1) >= 0) {
            restoreTabsFromInstanceState(savedInstanceState)
        } else {
            restoreTabsOrCreateHome()
        }
        handleOpenUrlIntent(intent)
    }

    /**
     * يحوّل حالة WebView إلى نص قابل للحفظ بشكل دائم في SharedPreferences.
     * saveState() يحفظ سجل الرجوع/التقدم داخل WebView، لذلك لا يكفي حفظ URL الحالي فقط.
     */
    private fun serializeWebViewState(webView: WebView): String? {
        return try {
            val state = Bundle()
            val history = webView.saveState(state) ?: return null
            if (history.size <= 0) return null

            val parcel = Parcel.obtain()
            try {
                state.writeToParcel(parcel, 0)
                Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** يعيد حالة WebView المحفوظة، بما فيها سجل الرجوع/التقدم. */
    private fun restoreSerializedWebViewState(webView: WebView, encoded: String?): Boolean {
        if (encoded.isNullOrBlank()) return false
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val state = Bundle.CREATOR.createFromParcel(parcel)
                state.classLoader = WebView::class.java.classLoader
                webView.restoreState(state) != null
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tabCount", tabs.size)
        outState.putInt("currentTabIndex", currentTabIndex)
        tabs.forEachIndexed { i, tab ->
            outState.putBoolean("tab_${i}_isHome", tab.isHome)
            outState.putBoolean("tab_${i}_desktop", tab.desktopMode)
            outState.putStringArrayList("tab_${i}_back", ArrayList(tab.backStack))
            outState.putStringArrayList("tab_${i}_forward", ArrayList(tab.forwardStack))
            if (!tab.isHome) {
                val webState = Bundle()
                tab.webView.saveState(webState)
                outState.putBundle("tab_${i}_webstate", webState)
            }
        }
        // مهم: onSaveInstanceState وحده لا يكفي لإعادة تشغيل التطبيق لاحقًا.
        persistTabs()
    }

    /** يستعيد التبويبات من حالة أندرويد المحفوظة (تستخدم ذاكرة WebView المؤقتة بدل تحميل الصفحة من الإنترنت من جديد) */
    private fun restoreTabsFromInstanceState(state: Bundle) {
        val count = state.getInt("tabCount", 0)
        for (i in 0 until count) {
            val isHome = state.getBoolean("tab_${i}_isHome", true)
            val desktop = state.getBoolean("tab_${i}_desktop", false)
            val back = state.getStringArrayList("tab_${i}_back") ?: arrayListOf()
            val forward = state.getStringArrayList("tab_${i}_forward") ?: arrayListOf()
            val webState = state.getBundle("tab_${i}_webstate")

            val webView = WebView(this)
            setupWebView(webView)
            val cleanedUA = buildChromeLikeUserAgent(webView.settings.userAgentString)
            webView.settings.userAgentString = if (desktop) desktopUA else cleanedUA
            if (mobileUA.isEmpty()) mobileUA = cleanedUA

            val tab = Tab(id = System.currentTimeMillis() + i, webView = webView, isHome = isHome, desktopMode = desktop)
            tab.backStack.addAll(back)
            tab.forwardStack.addAll(forward)
            tabs.add(tab)

            if (!isHome && webState != null) {
                webView.restoreState(webState)
            }
        }
        val idx = state.getInt("currentTabIndex", 0).coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        if (tabs.isEmpty()) {
            createHomeTab()
        } else {
            switchToTab(idx)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenUrlIntent(intent)
    }

    private fun handleOpenUrlIntent(intent: Intent?) {
        if (intent == null) return

        // الروابط القادمة من التطبيقات الأخرى (ومنها ChatGPT) تصل عادةً كـ ACTION_VIEW
        // مع URI في data. نفتحها دائمًا في تبويب جديد بدل استبدال التبويب الحالي.
        val extraUrl = intent.getStringExtra(EXTRA_OPEN_URL)
        val dataUrl = intent.data?.toString()
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

        val url = listOf(extraUrl, dataUrl, sharedText)
            .asSequence()
            .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
            .mapNotNull { value ->
                when {
                    value.startsWith("http://") || value.startsWith("https://") -> value
                    else -> null
                }
            }
            .firstOrNull()

        if (!url.isNullOrBlank()) {
            // كل رابط خارجي يفتح تبويبًا جديدًا، سواء وصل من ChatGPT أو WhatsApp أو Telegram
            // أو أي تطبيق آخر يدعم روابط الويب.
            createBrowsingTab(url)
        }
    }

    // ---------------- استعادة/حفظ التبويبات ----------------

    private fun restoreTabsOrCreateHome() {
        val (saved, currentIndex) = TabPersistence.load(this)
        if (saved.isEmpty()) {
            createHomeTab()
            return
        }
        saved.forEach { savedTab ->
            if (savedTab.isHome) {
                createTabInternal(isHome = true, url = null)
            } else {
                createTabInternal(
                    isHome = false,
                    url = savedTab.url,
                    restoredBack = savedTab.backStack,
                    restoredForward = savedTab.forwardStack,
                    restoredWebState = savedTab.webState
                )
            }
        }
        val idx = currentIndex.coerceIn(0, tabs.size - 1)
        switchToTab(idx)
    }

    private fun persistTabs() {
        val saved = tabs.map { tab ->
            SavedTab(
                tab.isHome,
                if (tab.isHome) "" else (tab.webView.url ?: ""),
                tab.title,
                tab.backStack.toList(),
                tab.forwardStack.toList(),
                if (tab.isHome) null else serializeWebViewState(tab.webView)
            )
        }
        TabPersistence.save(this, saved, currentTabIndex)
    }

    override fun onPause() {
        super.onPause()
        persistTabs()
    }

    // ---------------- إدارة التبويبات ----------------

    private fun createHomeTab() {
        createTabInternal(isHome = true, url = null)
        switchToTab(tabs.size - 1)
        persistTabs()
    }

    private fun createBrowsingTab(url: String) {
        createTabInternal(isHome = false, url = url)
        switchToTab(tabs.size - 1)
        persistTabs()
    }

    private fun createTabInternal(
        isHome: Boolean,
        url: String?,
        restoredBack: List<String> = emptyList(),
        restoredForward: List<String> = emptyList(),
        restoredWebState: String? = null
    ) {
        val webView = WebView(this)
        setupWebView(webView)
        val cleanedUA = buildChromeLikeUserAgent(webView.settings.userAgentString)
        webView.settings.userAgentString = cleanedUA
        if (mobileUA.isEmpty()) mobileUA = cleanedUA

        val tab = Tab(id = System.currentTimeMillis(), webView = webView, isHome = isHome)
        tab.backStack.addAll(restoredBack)
        tab.forwardStack.addAll(restoredForward)
        if (!isHome && !url.isNullOrBlank()) {
            tab.title = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
        }
        tabs.add(tab)

        if (!isHome) {
            // لا نعيد تحميل URL إذا كان لدينا WebView state محفوظ؛
            // restoreState يعيد سجل الرجوع/التقدم نفسه.
            val restored = restoreSerializedWebViewState(webView, restoredWebState)
            if (!restored && !url.isNullOrBlank()) {
                webView.loadUrl(url)
            }
        }
    }

    /** الرجوع والتقدم يعتمدان على سجل WebView الحقيقي مع استعادة موضع التمرير. */
    private fun goBackInTab(tab: Tab) {
        navigateHistory(tab, -1)
    }

    private fun goForwardInTab(tab: Tab) {
        navigateHistory(tab, 1)
    }

    // ---------------- حفظ واستعادة موضع التمرير (Scroll Position Restoration) ----------------

    private val blobBridge by lazy { BlobDownloadBridge(this) }

    private val SCROLL_RESTORE_INTERVAL_MS = 150L
    private val SCROLL_RESTORE_MAX_TRIES = 60          // ≈ 9 ثوانٍ كحد أقصى
    private val MAX_SAVED_SCROLL_ENTRIES = 100

    private fun rememberScroll(tab: Tab, url: String, y: Int) {
        if (url.isBlank() || url.startsWith("about:")) return
        tab.scrollPositions.remove(url)          // ليصبح الأحدث في آخر القائمة
        tab.scrollPositions[url] = y
        while (tab.scrollPositions.size > MAX_SAVED_SCROLL_ENTRIES) {
            val oldest = tab.scrollPositions.keys.firstOrNull() ?: break
            tab.scrollPositions.remove(oldest)
        }
    }

    /**
     * ينتقل في سجل التبويب خطوة للخلف (steps = -1) أو للأمام (steps = 1)،
     * ويعيد المستخدم إلى نفس مكان التمرير الذي كان فيه في تلك الصفحة.
     * يرجع false إذا لا توجد صفحة سابقة/تالية.
     */
    private fun navigateHistory(tab: Tab, steps: Int): Boolean {
        val wv = tab.webView
        val list = wv.copyBackForwardList()
        val targetIndex = list.currentIndex + steps
        if (targetIndex < 0 || targetIndex >= list.size) return false
        val targetUrl = list.getItemAtIndex(targetIndex)?.url

        // احفظ موضع الصفحة التي نغادرها (احتياطًا، المستمع يحفظه أصلًا أثناء التمرير)
        val leavingUrl = wv.url
        if (leavingUrl != null && !tab.pageLoading && tab.pendingRestoreY < 0) {
            rememberScroll(tab, leavingUrl, wv.scrollY)
        }

        cancelScrollRestore(tab)
        val savedY = targetUrl?.let { tab.scrollPositions[it] } ?: 0

        // عند الرجوع نفضّل النسخة المخزّنة مؤقتًا لتظهر نفس نتائج البحث التي رآها المستخدم
        wv.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        wv.goBackOrForward(steps)

        if (savedY > 0) startScrollRestore(tab, targetIndex, savedY)
        return true
    }

    private fun cancelScrollRestore(tab: Tab) {
        tab.restoreGeneration++
        tab.pendingRestoreY = -1
    }

    private fun finishScrollRestore(tab: Tab) {
        tab.pendingRestoreY = -1
        try { tab.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT } catch (e: Exception) { }
    }

    /**
     * يحاول كل 150ms تمرير الصفحة إلى الموضع المحفوظ حتى ينجح.
     * إذا كانت الصفحة أقصر من الموضع المطلوب (المحتوى لم يكتمل أو يُحمَّل بالتمرير اللانهائي مثل جوجل)،
     * يبقى التمرير عند آخر الصفحة المتاحة فيُحمَّل المزيد من المحتوى، ثم يكمل المحاولة.
     * تتوقف المحاولات إذا لمس المستخدم الشاشة.
     */
    private fun startScrollRestore(tab: Tab, targetIndex: Int, targetY: Int) {
        val generation = ++tab.restoreGeneration
        tab.pendingRestoreY = targetY
        var tries = 0
        val step = object : Runnable {
            override fun run() {
                if (tab.restoreGeneration != generation) return       // أُلغيت أو استُبدلت باستعادة أحدث
                if (!tabs.contains(tab)) return                        // التبويب أُغلق
                val wv = tab.webView
                tries++
                // لا نحرّك الصفحة القديمة: ننتظر حتى يصبح موضع السجل هو الصفحة الهدف
                val onTarget = try { wv.copyBackForwardList().currentIndex == targetIndex } catch (e: Exception) { false }
                if (onTarget) {
                    wv.scrollTo(wv.scrollX, targetY)
                    if (abs(wv.scrollY - targetY) <= 2 && !tab.pageLoading) {
                        finishScrollRestore(tab)
                        return
                    }
                }
                if (tries >= SCROLL_RESTORE_MAX_TRIES) {
                    finishScrollRestore(tab)
                    return
                }
                mainHandler.postDelayed(this, SCROLL_RESTORE_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(step, SCROLL_RESTORE_INTERVAL_MS)
    }

    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        webViewContainer.removeAllViews()
        currentTabIndex = index
        val tab = tabs[index]

        if (tab.isHome) {
            editUrl.setText("")
            progressBar.visibility = View.GONE
            webViewContainer.addView(buildSpeedDialView())
        } else {
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            webViewContainer.addView(
                tab.webView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            editUrl.setText(tab.webView.url ?: "")
        }
        updateTabCountUI()
    }

    private fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs[index]
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            createHomeTab()
        } else {
            val newIndex = if (currentTabIndex >= tabs.size) tabs.size - 1 else currentTabIndex
            switchToTab(newIndex)
        }
        persistTabs()
    }

    private fun closeAllTabs() {
        webViewContainer.removeAllViews()
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        createHomeTab()
    }

    private fun updateTabCountUI() {
        btnTabs.text = tabs.size.toString()
    }

    private fun currentTab(): Tab = tabs[currentTabIndex]
    private fun currentWebView(): WebView = tabs[currentTabIndex].webView

    /** يزيل علامة "wv" (WebView) من هوية المتصفح الافتراضية لتقليل رفض بعض المواقع/إظهار تحقق زائد */
    private fun buildChromeLikeUserAgent(defaultUA: String): String {
        return defaultUA
            .replace("; wv)", ")")
            .replace(" wv;", "")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(editUrl.windowToken, 0)
        editUrl.clearFocus()
    }

    // ---------------- صفحة البداية (Speed Dial) ----------------

    private fun buildSpeedDialView(): View {
        val root = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }

        val columns = 4
        val horizontalPadding = 16
        val screenWidth = resources.displayMetrics.widthPixels
        val cellWidth = (screenWidth - horizontalPadding * 2) / columns
        val iconSize = (cellWidth * 0.68f).toInt()

        val grid = GridLayout(this).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(horizontalPadding, 48, horizontalPadding, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val items = SpeedDialStorage.getItems(this)
        items.forEach { item ->
            grid.addView(buildSpeedDialTile(title = item.title, item = item, cellWidth = cellWidth, iconSize = iconSize) {
                createBrowsingTab(item.url)
            })
        }

        // زر + إضافة
        val addTile = buildAddTile(cellWidth = cellWidth, iconSize = iconSize) { showAddSpeedDialDialog() }
        grid.addView(addTile)

        root.addView(grid)
        return root
    }

    private fun buildAddTile(cellWidth: Int, iconSize: Int, onClick: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellWidth
                setMargins(4, 12, 4, 12)
            }
            setOnClickListener { onClick() }
        }
        val plusBox = TextView(this).apply {
            text = "+"
            setTextColor(Color.WHITE)
            textSize = (iconSize / 22f).coerceAtLeast(20f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setBackgroundColor(Color.parseColor("#222222"))
        }
        val label = TextView(this).apply {
            text = "إضافة"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        container.addView(plusBox)
        container.addView(label)
        return container
    }

    private fun buildSpeedDialTile(title: String, item: SpeedDialItem, cellWidth: Int, iconSize: Int, onClick: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellWidth
                setMargins(4, 12, 4, 12)
            }
            setOnClickListener { onClick() }
            setOnLongClickListener {
                showSpeedDialItemMenu(item)
                true
            }
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            tag = item.url
        }
        loadFaviconInto(icon, item.url)

        val label = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#808080"))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        container.addView(icon)
        container.addView(label)
        return container
    }

    private fun faviconCacheFile(host: String): File {
        val safe = Base64.encodeToString(
            host.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        val dir = File(filesDir, "speeddial_favicons")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, safe + ".png")
    }

    private fun loadFaviconInto(imageView: ImageView, pageUrl: String) {
        val host = try { Uri.parse(pageUrl).host?.lowercase(Locale.US) } catch (e: Exception) { null } ?: return
        val cacheFile = faviconCacheFile(host)

        // استخدم الصورة المحفوظة أولاً حتى لا تتحول مربعات المواقع إلى أسود عند كل تشغيل.
        bgExecutor.execute {
            var bitmap: Bitmap? = null
            if (cacheFile.exists()) {
                bitmap = runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
            }

            if (bitmap == null) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("https://www.google.com/s2/favicons?sz=128&domain=$host")
                    connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    bitmap = BitmapFactory.decodeStream(connection.inputStream)

                    bitmap?.let { downloaded ->
                        runCatching {
                            cacheFile.outputStream().use { out ->
                                downloaded.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                        }
                    }
                } catch (e: Exception) {
                    bitmap = null
                } finally {
                    connection?.disconnect()
                }
            }

            val result = bitmap
            mainHandler.post {
                if (result != null && imageView.tag == pageUrl) {
                    imageView.setImageBitmap(result)
                }
            }
        }
    }
    private fun showAddSpeedDialDialog() {
        val input = EditText(this).apply {
            hint = "الصق الرابط هنا"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("إضافة موقع")
            .setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                val host = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
                SpeedDialStorage.addItem(this, host, url)
                switchToTab(currentTabIndex)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showSpeedDialItemMenu(item: SpeedDialItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("إعادة تسمية", "حذف")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(item)
                    1 -> {
                        SpeedDialStorage.removeItem(this, item.id)
                        switchToTab(currentTabIndex)
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(item: SpeedDialItem) {
        val input = EditText(this).apply {
            setText(item.title)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this)
            .setTitle("إعادة تسمية")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                SpeedDialStorage.renameItem(this, item.id, input.text.toString().trim())
                switchToTab(currentTabIndex)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------------- إعداد WebView ----------------

    private fun setupWebView(webView: WebView) {
        if (nightMode) webView.setBackgroundColor(Color.BLACK)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val settings = webView.settings
        settings.textZoom = savedFontZoom()
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // النوافذ الجديدة تصل إلى onCreateWindow (مع نافذة التأكيد) فقط عند ضغط المستخدم؛
        // ما تفتحه الصفحة من نفسها بدون ضغط يبقى محجوبًا.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.allowFileAccess = true
        settings.blockNetworkImage = hideMedia
        settings.loadsImagesAutomatically = !hideMedia

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // تسجيل موضع التمرير أثناء التصفح لكل رابط (يُستخدم عند الرجوع)
        webView.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val wv = v as? WebView ?: return@setOnScrollChangeListener
            val tab = tabs.find { it.webView == wv } ?: return@setOnScrollChangeListener
            if (tab.pageLoading || tab.pendingRestoreY >= 0) return@setOnScrollChangeListener
            val url = wv.url ?: return@setOnScrollChangeListener
            rememberScroll(tab, url, scrollY)
        }

        // إذا لمس المستخدم الشاشة أثناء الاستعادة نوقفها حتى لا نتحكم بتمريره
        webView.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                tabs.find { it.webView == v }?.let { t ->
                    if (t.pendingRestoreY >= 0) {
                        cancelScrollRestore(t)
                        try { t.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT } catch (e: Exception) { }
                    }
                }
            }
            false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                AdBlocker.maybeBlock(this@BrowserActivity, request)?.let { return it }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (request.isForMainFrame && looksLikeDirectFileUrl(url)) {
                    DownloadsRepository.startNewDownload(
                        this@BrowserActivity,
                        url,
                        showToast = true,
                        userAgent = view.settings.userAgentString,
                        referer = view.url
                    )
                    return true
                }
                if (request.isForMainFrame) {
                    val known = DownloadsRepository.downloadList.find { it.url == url }
                    if (known != null) {
                        // رابط تنزيل معروف: كان يُتجاهل بصمت، الآن تظهر رسالة «الملف موجود: الاسم» أو يُستأنف التنزيل
                        DownloadsRepository.startNewDownload(
                            this@BrowserActivity, url, showToast = true, suggestedFileName = known.fileName,
                            userAgent = view.settings.userAgentString, referer = view.url
                        )
                        return true
                    }
                }
                if (request.isForMainFrame) {
                    navigateWithSafetyCheck(view, url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tabs.find { it.webView == view }?.pageLoading = true
                // نحفظ الحالة فور بدء التنقل أيضًا حتى لا نفقد آخر خطوة إذا أُغلق التطبيق
                // قبل onPageFinished.
                persistTabs()
                if (nightMode) applyNightModeJs(view)
                if (isActiveTab(view)) editUrl.setText(url)
            }

            // أول ظهور لمحتوى الصفحة الجديدة: أسرع وقت لتطبيق الوضع الليلي
            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                if (nightMode) applyNightModeJs(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                tabs.find { it.webView == view }?.let { t ->
                    t.pageLoading = false
                    // صفحة جديدة عادية (بدون استعادة جارية): سجّل موضعها الحالي
                    if (t.pendingRestoreY < 0) {
                        view.url?.let { u -> rememberScroll(t, u, view.scrollY) }
                        view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    }
                }
                if (url != null) {
                    recordVisitedNavigation(url)
                    BrowserStorage.addHistory(this@BrowserActivity, view.title ?: url, url)
                    applyVisitedSearchResultColors(view)
                }
                val resolvedTitle = view.title?.takeIf { it.isNotBlank() }
                    ?: url?.takeIf { it.isNotBlank() }
                    ?: "صفحة جديدة"
                tabs.find { it.webView == view }?.title = resolvedTitle
                if (nightMode) {
                    applyNightModeJs(view)
                    mainHandler.postDelayed({ if (nightMode) applyNightModeJs(view) }, 500)
                    // نحفظ حكم الصفحة (داكنة/فاتحة) لتُعالج أسرع في الزيارات القادمة
                    mainHandler.postDelayed({ if (nightMode) recordNightResult(view) }, 2200)
                }
                val tabForView = tabs.find { it.webView == view }
                if (tabForView?.desktopMode == true) applyDesktopViewportJs(view)
                if (isActiveTab(view)) {
                    if (hideMedia) applyHideMediaJs(view)
                }

                if (isSearchResultsPage(url)) {
                    // الحالة مبنية على سجل دائم، لذلك تبقى الألوان بعد الرجوع وإعادة فتح التطبيق.
                    applyVisitedSearchResultColors(view)
                    filterSearxngMicrosoftResults(view)
                    mainHandler.postDelayed({
                        if (!isFinishing && !isDestroyed && isSearchResultsPage(view.url)) {
                            applyVisitedSearchResultColors(view)
                            filterSearxngMicrosoftResults(view)
                        }
                    }, 700L)
                }

                // Keep translating every new page opened inside this translated tab.
                if (tabForView?.translationEnabled == true) {
                    mainHandler.postDelayed({
                        translateTabPageIfNeeded(view, tabForView)
                    }, 500L)
                }

                persistTabs()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (nightMode && newProgress in 15..95 && System.currentTimeMillis() - lastNightInject > 250) {
                    applyNightModeJs(view)
                }
                if (isActiveTab(view)) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

                // نرد على الصفحة فورًا بـ WebView مؤقت يلتقط رابط النافذة فقط.
                // الاحتفاظ برسالة النافذة حتى يضغط المستخدم «سماح» كان يوقف التطبيق
                // (المتصفح يُلغي النافذة المعلّقة إذا طلبت الصفحة نافذة أخرى أو طال الانتظار).
                val temp = WebView(this@BrowserActivity)
                var finished = false

                fun dispose() {
                    if (finished) return
                    finished = true
                    // لا نهدم الـ WebView داخل ردّه نفسه: ننفّذ ذلك بعد انتهاء الاستدعاء الحالي
                    mainHandler.post {
                        try { temp.stopLoading() } catch (e: Exception) { }
                        try { temp.destroy() } catch (e: Exception) { }
                    }
                }

                fun capture(rawUrl: String?) {
                    if (finished) return
                    val u = rawUrl?.trim().orEmpty()
                    val lower = u.lowercase()
                    if (u.isEmpty() || lower.startsWith("about:") || lower.startsWith("javascript:") ||
                        lower.startsWith("data:") || lower.startsWith("blob:")) return
                    dispose()
                    showPopupConfirmDialog(u)
                }

                temp.webViewClient = object : WebViewClient() {
                    // لا نحمّل شيئًا من الشبكة داخل الـ WebView المؤقت
                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        capture(request.url.toString())
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        capture(url)
                    }
                }

                transport.webView = temp
                resultMsg.sendToTarget()

                // إن لم يصل أي رابط (نافذة فارغة) نتخلص من الـ WebView المؤقت
                mainHandler.postDelayed({ dispose() }, 8000)
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                filePathCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, "اختر ملف"), FILE_CHOOSER_CODE)
                } catch (e: Exception) {
                    filePathCallback = null
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // نمنح فقط ما هو مسموح في إعدادات المتصفح، ونرفض الباقي
                val granted = request.resources.filter { res ->
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> cameraAllowed()
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> micAllowed()
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true   // مطلوب لتشغيل فيديوهات DRM
                        else -> false
                    }
                }
                val deniedByUser = request.resources.any { res ->
                    (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE && !cameraAllowed()) ||
                        (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE && !micAllowed())
                }
                if (granted.isEmpty() || deniedByUser) {
                    request.deny()
                    return
                }

                val androidPerms = mutableListOf<String>()
                if (granted.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) androidPerms.add(Manifest.permission.CAMERA)
                if (granted.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) androidPerms.add(Manifest.permission.RECORD_AUDIO)

                runOnUiThread {
                    ensureAndroidPermissions(androidPerms.toTypedArray()) { ok ->
                        if (ok) request.grant(granted.toTypedArray()) else request.deny()
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                if (!locationAllowed()) {
                    callback.invoke(origin, false, false)
                    return
                }
                ensureAndroidPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    anyOf = true
                ) { ok -> callback.invoke(origin, ok, false) }
            }
        }

        // جسر قراءة روابط blob: (محمي برمز عشوائي لكل عملية تنزيل)
        webView.addJavascriptInterface(blobBridge, "DownLS10Blob")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val nameFromHeader = DownloadsRepository.fileNameFromContentDispositionOrNull(contentDisposition)
            if (url.startsWith("blob:", ignoreCase = true) || url.startsWith("data:", ignoreCase = true)) {
                // روابط blob/data تعتمد على بيانات الصفحة نفسها، لذلك نمررها مباشرة للمسار المدعوم.
                if (url.startsWith("blob:", ignoreCase = true)) {
                    blobBridge.start(webView, url, nameFromHeader, mimeType)
                } else {
                    DownloadsRepository.startNewDownload(this, url, true, nameFromHeader, userAgent, webView.url, mimeType)
                }
                return@setDownloadListener
            }

            val guessedName = nameFromHeader ?: runCatching {
                Uri.decode(Uri.parse(url).lastPathSegment.orEmpty()).substringBefore("?")
                    .takeIf { it.isNotBlank() && it.contains(".") }
            }.getOrNull() ?: "download_${System.currentTimeMillis()}"

            val extension = guessedName.substringAfterLast('.', "").lowercase(Locale.US)
            val typeLabel = mimeType?.takeIf { it.isNotBlank() }
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "غير معروف"
            val sizeLabel = if (contentLength > 0L) {
                android.text.format.Formatter.formatFileSize(this, contentLength)
            } else {
                "غير معروف (يُحدَّد عند الاتصال بالخادم)"
            }

            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 20, 24, 12)
            }
            val icon = ImageView(this).apply {
                setImageResource(android.R.drawable.stat_sys_download)
                layoutParams = LinearLayout.LayoutParams(56, 56).apply { marginEnd = 18 }
                contentDescription = "أيقونة الملف"
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(TextView(this).apply {
                text = "اسم الملف: $guessedName"
                textSize = 16f
            })
            details.addView(TextView(this).apply {
                text = "النوع: $typeLabel"
                textSize = 14f
                setPadding(0, 8, 0, 0)
            })
            details.addView(TextView(this).apply {
                text = "الحجم: $sizeLabel"
                textSize = 14f
                setPadding(0, 8, 0, 0)
            })
            panel.addView(icon)
            panel.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            AlertDialog.Builder(this)
                .setTitle("تنزيل ملف")
                .setView(panel)
                .setPositiveButton("تنزيل") { _, _ ->
                    DownloadsRepository.startNewDownload(
                        this, url, true, guessedName, userAgent, webView.url, mimeType
                    )
                }
                .setNeutralButton("إضافة") { _, _ ->
                    DownloadsRepository.addToQueue(this, url, guessedName, userAgent, webView.url)
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_CODE) {
            val results: Array<Uri>? = if (resultCode == Activity.RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }

        if (requestCode == BookmarksFileManager.EXPORT_REQUEST_CODE) {
            val content = pendingBookmarkExportContent
            pendingBookmarkExportContent = null
            if (resultCode == Activity.RESULT_OK && data?.data != null && content != null) {
                val result = BookmarksFileManager.writeExport(this, data.data!!, content)
                Toast.makeText(this, if (result.first) "تم تصدير العلامات المرجعية" else "فشل التصدير: ${result.second}", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (requestCode == BookmarksFileManager.IMPORT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val result = BookmarksFileManager.readImport(this, data.data!!)
                val html = result.first
                if (html == null) {
                    Toast.makeText(this, "فشل الاستيراد: ${result.second}", Toast.LENGTH_LONG).show()
                } else {
                    val parsed = BrowserStorage.parseBookmarksHtml(html)
                    if (parsed.isEmpty()) {
                        Toast.makeText(this, "لم يتم العثور على علامات مرجعية في الملف", Toast.LENGTH_LONG).show()
                    } else {
                        val added = BrowserStorage.importBookmarks(this, parsed)
                        Toast.makeText(this, "تم استيراد $added علامة مرجعية جديدة", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun isActiveTab(view: WebView): Boolean =
        tabs.isNotEmpty() && currentTabIndex < tabs.size && tabs[currentTabIndex].webView == view

    // ---------------- التنقل + فحص الأمان ----------------

    /** يسجّل الرابط كمزار. روابط جوجل الوسيطة (/url?q=...) تُفك ليُسجَّل الموقع الحقيقي. */
    private fun isVisitedSite(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try { Uri.parse(url).host } catch (e: Exception) { null }
        return VisitedSites.isVisited(this, host)
    }

    private fun visitedSiteColor(url: String?, unvisitedColor: Int = Color.CYAN): Int {
        return if (isVisitedSite(url)) Color.parseColor("#808080") else unvisitedColor
    }

    private fun isSearchResultsPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase(Locale.US) ?: return false
        val path = uri.path?.lowercase(Locale.US) ?: ""

        // محركات البحث المعروفة، بما فيها نطاقات Google المحلية مثل google.iq وgoogle.ae.
        val knownSearchHost =
            host == "google.com" || host.startsWith("google.") || host.endsWith(".google.com") ||
            host == "bing.com" || host.endsWith(".bing.com") ||
            host == "search.yahoo.com" ||
            host == "duckduckgo.com" || host.endsWith(".duckduckgo.com") ||
            host == "search.brave.com" ||
            host.endsWith(".yandex.com") || host.endsWith(".yandex.ru") ||
            host == "search.aol.com" ||
            host == "search.naver.com" ||
            host == "www.ecosia.org" ||
            host == "search.qwant.com"

        if (knownSearchHost) {
            return path.contains("search") ||
                uri.getQueryParameter("q") != null ||
                uri.getQueryParameter("query") != null ||
                uri.getQueryParameter("p") != null ||
                uri.getQueryParameter("text") != null
        }

        // دعم محركات البحث الأخرى التي تستخدم صفحة /search أو معاملات البحث الشائعة.
        return path == "/search" || path.startsWith("/search/") ||
            uri.getQueryParameter("q") != null ||
            uri.getQueryParameter("query") != null ||
            uri.getQueryParameter("search_query") != null
    }

    private fun filterSearxngMicrosoftResults(webView: WebView) {
        val host = try {
            Uri.parse(webView.url ?: "").host?.lowercase(Locale.US)
        } catch (_: Exception) {
            null
        }
        if (host != "searx.ononoki.org") return

        val js = """
            (function() {
                try {
                    var badHosts = ["bing.com", "microsoft.com"];

                    function isBadHost(host) {
                        host = String(host || "").toLowerCase().replace(/^www\./, "");
                        return badHosts.some(function(b) {
                            return host === b || host.endsWith("." + b);
                        });
                    }

                    document.querySelectorAll('a[href]').forEach(function(a) {
                        try {
                            var u = new URL(a.href, location.href);
                            if (!isBadHost(u.hostname)) return;

                            var card = a.closest("article.result, .result, li.result");
                            if (card) card.remove();
                        } catch (e) {}
                    });
                } catch (e) {}
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    private fun applyVisitedSearchResultColors(webView: WebView) {
        val url = webView.url ?: return
        if (!isSearchResultsPage(url)) return

        val visitedUrlsJson = VisitedSites.allVisitedUrlsJson(this)
        val visitedHostsJson = VisitedSites.asJsonForInjection(this)
        val quotedUrlsJson = JSONObject.quote(visitedUrlsJson)
        val quotedHostsJson = JSONObject.quote(visitedHostsJson)

        val js = """
            (function(visitedUrlsRaw, visitedHostsRaw) {
                try {
                    var visitedUrls = JSON.parse(visitedUrlsRaw || '{}');
                    var visitedHosts = JSON.parse(visitedHostsRaw || '{}');
                    var PURPLE = '#800080';

                    function normalizeHost(host) {
                        return String(host || '')
                            .toLowerCase()
                            .replace(/^www\./, '')
                            .replace(/\.$/, '');
                    }

                    function normalize(u) {
                        try {
                            var x = new URL(u, location.href);
                            if (x.protocol !== 'http:' && x.protocol !== 'https:') return '';
                            x.hash = '';
                            var s = x.toString();
                            return s.endsWith('/') ? s.slice(0, -1) : s;
                        } catch (e) {
                            return '';
                        }
                    }

                    function unwrapGoogle(u) {
                        try {
                            var x = new URL(u, location.href);
                            var h = normalizeHost(x.hostname);
                            if ((h === 'google.com' || h.endsWith('.google.com') || h.indexOf('google.') === 0) &&
                                x.pathname === '/url') {
                                return x.searchParams.get('q') ||
                                       x.searchParams.get('url') ||
                                       x.searchParams.get('u') ||
                                       u;
                            }
                        } catch (e) {}
                        return u;
                    }

                    function isVisited(u) {
                        var unwrapped = unwrapGoogle(u);
                        var n = normalize(unwrapped);
                        if (!n) return false;

                        try {
                            var host = normalizeHost(new URL(unwrapped, location.href).hostname);
                            if (host) {
                                // تطابق النطاق نفسه، وكذلك النطاقات الفرعية للموقع.
                                if (visitedHosts[host]) return true;
                                var keys = Object.keys(visitedHosts);
                                for (var i = 0; i < keys.length; i++) {
                                    var visitedHost = normalizeHost(keys[i]);
                                    if (visitedHost &&
                                        (host === visitedHost ||
                                         host.endsWith('.' + visitedHost) ||
                                         visitedHost.endsWith('.' + host))) {
                                        return true;
                                    }
                                }
                            }
                        } catch (e) {}

                        if (visitedUrls[n]) return true;
                        var alt = n.endsWith('/') ? n.slice(0, -1) : n + '/';
                        return !!visitedUrls[alt];
                    }

                    function paintVisited(element) {
                        element.style.setProperty('color', PURPLE, 'important');
                        element.querySelectorAll('*').forEach(function(child) {
                            child.style.setProperty('color', PURPLE, 'important');
                        });
                    }

                    function styleResults() {
                        var links = document.querySelectorAll('a[href]');
                        for (var i = 0; i < links.length; i++) {
                            var a = links[i];
                            var href = a.href || a.getAttribute('href') || '';
                            if (!/^https?:/i.test(href)) continue;

                            var linkUrl;
                            try { linkUrl = new URL(href, location.href); } catch (e) { continue; }

                            var currentHost = normalizeHost(location.hostname);
                            var resultHost = normalizeHost(linkUrl.hostname);
                            if (resultHost === currentHost) continue;

                            if (!String(a.innerText || a.textContent || '').trim()) continue;

                            if (isVisited(href)) {
                                paintVisited(a);
                            }
                            // غير المزور: لا نضع أي لون من التطبيق، حتى يبقى
                            // اللون الأصلي الذي اختاره محرك البحث.
                        }
                    }

                    function scheduleStyle() {
                        if (window.__downls10VisitedColorTimer) {
                            clearTimeout(window.__downls10VisitedColorTimer);
                        }
                        window.__downls10VisitedColorTimer = setTimeout(function() {
                            window.__downls10VisitedColorTimer = null;
                            styleResults();
                        }, 80);
                    }

                    styleResults();

                    // محركات البحث الحديثة تغيّر class/style للنتائج بعد تحميلها.
                    // نراقب إضافة النتائج وتغييرات style/class حتى لا يعود الرابط المزور
                    // إلى اللون الأصلي بعد أن نلوّنه.
                    if (!window.__downls10VisitedColorObserver) {
                        window.__downls10VisitedColorObserver = new MutationObserver(function() {
                            scheduleStyle();
                        });

                        window.__downls10VisitedColorObserver.observe(document.documentElement, {
                            childList: true,
                            attributes: true,
                            attributeFilter: ['class', 'style'],
                            subtree: true
                        });

                        window.addEventListener('pageshow', styleResults);

                        // عدة محاولات قصيرة لأن بعض محركات البحث ترسم النتائج على مراحل.
                        [150, 400, 800, 1500, 3000].forEach(function(delay) {
                            setTimeout(styleResults, delay);
                        });
                    }
                } catch (e) {}
            })($quotedUrlsJson, $quotedHostsJson);
        """
        webView.evaluateJavascript(js, null)
    }

    private fun recordVisitedNavigation(url: String?) {
        if (url.isNullOrBlank()) return
        val uri = try { Uri.parse(url) } catch (e: Exception) { return }
        if (uri.scheme != "http" && uri.scheme != "https") return
        var realUrl = url
        val host = uri.host ?: return
        if (host.contains("google.") && uri.path == "/url") {
            val target = uri.getQueryParameter("q") ?: uri.getQueryParameter("url")
            if (!target.isNullOrBlank()) realUrl = target
        }
        val realHost = try { Uri.parse(realUrl).host } catch (e: Exception) { null }
        VisitedSites.recordVisit(this, realHost)
        VisitedSites.recordUrl(this, realUrl)
    }

    private fun loadAndRecord(webView: WebView, url: String) {
        recordVisitedNavigation(url)
        webView.loadUrl(url)
    }

    /** يلتقط روابط الملفات المباشرة حتى لو كان WebView يستطيع عرض نوع الملف داخله. */
    private fun looksLikeDirectFileUrl(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return false }
        val path = uri.path?.lowercase(Locale.US) ?: return false
        val ext = path.substringAfterLast('.', "")
        if (ext.isBlank() || ext.length > 10 || !ext.all { it.isLetterOrDigit() }) return false
        // صفحات الويب الديناميكية/العادية لا تُعامل كملفات.
        val webPageExtensions = setOf("html", "htm", "php", "asp", "aspx", "jsp", "cgi", "do", "action", "ashx", "axd", "pl")
        if (ext in webPageExtensions) return false
        // الامتدادات المعروفة للملفات التي يجب أن تنزل إلى Download.
        val fileExtensions = setOf(
            "apk", "aab", "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso",
            "pdf", "epub", "mobi", "azw", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "csv", "json", "xml", "rtf", "odt", "ods", "odp",
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "tif", "tiff",
            "mp3", "wav", "ogg", "m4a", "flac", "aac",
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v",
            "srt", "ass", "vtt", "torrent", "bin", "dmg", "deb", "rpm", "msi", "exe",
            "jar", "war", "class", "db", "sqlite", "sql", "bak", "dat", "log"
        )
        return ext in fileExtensions
    }

    private fun isUserRequestedNormalHost(host: String): Boolean {
        val h = host.lowercase(Locale.US).removePrefix("www.").removeSuffix(".")
        // مواقع تنزيل APK المعدّلة ومشاركة النصوص/الروابط التي طلب المستخدم فتحها مباشرةً،
        // مع نطاقاتها الفرعية. نتجنب كلمة "apk" وحدها كي لا نستثني مواقع سليمة مثل APKMirror.
        val allowedHosts = setOf(
            "modcda.com",
            "apkstime.com",
            "fastmodapk.com",
            "meigeeks.com",
            "pastebin.com",
            "greatmodapk.com",
            "downloadatoz.com",
            "pastelink.net"
        )
        if (allowedHosts.any { h == it || h.endsWith(".$it") }) return true

        // دعم مواقع مشابهة لتوزيع APK المعدّل، دون اعتبار كل موقع يحتوي "apk" موقعاً مسموحاً.
        val labels = h.split(".")
        return labels.any { label ->
            val compact = label.replace("-", "").replace("_", "")
            compact.contains("modapk") ||
                compact.contains("apkmod") ||
                compact.contains("fastmodapk") ||
                compact.contains("modcda")
        }
    }

    private fun navigateWithSafetyCheck(webView: WebView, rawUrl: String) {
        // روابط magnet وftp لا يفتحها المتصفح: تذهب لمدير التنزيل
        val lowerUrl = rawUrl.trim().lowercase()
        if (lowerUrl.startsWith("magnet:") || lowerUrl.startsWith("ftp://")) {
            DownloadsRepository.startNewDownload(this, rawUrl.trim(), showToast = true, referer = webView.url)
            return
        }
        // روابط ملفات Mega: صفحة Mega لا تستطيع التنزيل داخل WebView، فيتولاها مدير التنزيل مباشرة
        if (MegaSupport.isMegaFileLink(rawUrl.trim())) {
            DownloadsRepository.startNewDownload(this, rawUrl.trim(), showToast = true, referer = webView.url)
            return
        }
        // أي تنقل جديد (ضغط رابط/شريط العنوان) يستخدم الكاش الافتراضي وليس وضع الرجوع
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        tabs.find { it.webView == webView }?.let { cancelScrollRestore(it) }
        if (rawUrl == lastConfirmedSafetyUrl) {
            lastConfirmedSafetyUrl = null
            loadAndRecord(webView, rawUrl)
            return
        }

        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { null }

        // رابط مخصص (غير http/https): افتح التطبيق المثبّت إذا قدر يتعامل معه، وإلا تجاهله بهدوء
        if (uri?.scheme != null && uri.scheme != "http" && uri.scheme != "https") {
            openExternalAppLink(webView, rawUrl, uri)
            return
        }

        val host = uri?.host

        // لا تعرض أي نافذة تحذير أو رسالة Toast عند فتح المواقع.
        // نترك تحميل الموقع وسلوك بقية المتصفح كما هو.
        loadAndRecord(webView, rawUrl)
                    }
                    .setNegativeButton("رفض", null)
                    .show()
                return
            }
        }

        if (uri?.scheme == "http" && (uri.host == null || !isUserRequestedNormalHost(uri.host!!))) {
            Toast.makeText(this, "⚠️ هذا الموقع غير آمن (اتصال HTTP غير مشفّر)", Toast.LENGTH_SHORT).show()
        }

        loadAndRecord(webView, rawUrl)
    }

    // يفتح روابط التطبيقات الخارجية (Google Play, تيليجرام, واتساب, ...).
    // روابط "intent://" (مثل زر "الفتح في تطبيق Play") لها تنسيق خاص يجب فكّه
    // بواسطة Intent.parseUri وليس ببناء Intent يدويًا من الـ Uri مباشرة.
    private fun openExternalAppLink(webView: WebView, rawUrl: String, uri: Uri) {
        try {
            val intent: Intent
            var fallbackUrl: String? = null

            if (uri.scheme == "intent") {
                intent = Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
                fallbackUrl = intent.getStringExtra("browser_fallback_url")
                // نتأكد إنه ما يرجع لنفس المتصفح إذا التطبيق موجود أصلاً
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
            } else {
                intent = Intent(Intent.ACTION_VIEW, uri)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else if (!fallbackUrl.isNullOrBlank()) {
                // التطبيق غير مثبّت: افتح الرابط الاحتياطي (عادة صفحة متجر التطبيق نفسه)
                loadAndRecord(webView, fallbackUrl)
            }
            // ما فيه تطبيق ولا رابط احتياطي: تجاهل بهدوء
        } catch (e: Exception) {
            // تجاهل بهدوء عند فشل تحليل أو فتح الرابط
        }
    }

    private fun showBlockedInterstitial(webView: WebView, message: String) {
        val html = """
            <html dir="rtl"><body style="background:#000;color:#fff;font-family:sans-serif;
            display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
            <h2>🚫 $message</h2></body></html>
        """
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun setupGoogleSuggestions() {
        editUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                val generation = ++suggestionGeneration
                if (query.length < 2 || (query.contains(".") && !query.contains(" "))) {
                    suggestionsPopup?.dismiss()
                    return
                }
                bgExecutor.execute {
                    val result = try {
                        val url = URL("https://suggestqueries.google.com/complete/search?client=firefox&hl=ar&q=" + Uri.encode(query))
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                        try {
                            val json = connection.inputStream.bufferedReader().use { it.readText() }
                            val arr = org.json.JSONArray(json)
                            val values = arr.optJSONArray(1) ?: org.json.JSONArray()
                            (0 until values.length()).mapNotNull { values.optString(it).takeIf { v -> v.isNotBlank() } }.distinct().take(8)
                        } finally { connection.disconnect() }
                    } catch (_: Exception) { emptyList<String>() }
                    mainHandler.post {
                        if (generation == suggestionGeneration && editUrl.hasFocus() && editUrl.text.toString().trim() == query) {
                            showSuggestionRows(result)
                        }
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        editUrl.setOnFocusChangeListener { _, focused -> if (!focused) suggestionsPopup?.dismiss() }
    }

    private fun showSuggestionRows(items: List<String>) {
        suggestionsPopup?.dismiss()
        if (items.isEmpty()) return
        suggestionItems = items
        val list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.DKGRAY)
            dividerHeight = 1
            adapter = object : BaseAdapter() {
                override fun getCount() = suggestionItems.size
                override fun getItem(position: Int) = suggestionItems[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = LinearLayout(this@BrowserActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(8, 2, 8, 2)
                        setBackgroundColor(0xFF202020.toInt())
                    }
                    val label = TextView(this@BrowserActivity).apply {
                        text = getItem(position)
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        maxLines = 2
                        setPadding(8, 10, 8, 10)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    label.setOnClickListener {
                        editUrl.setText(getItem(position)); editUrl.setSelection(editUrl.text.length)
                        suggestionsPopup?.dismiss(); loadFromAddressBar(); hideKeyboard()
                    }
                    val insert = Button(this@BrowserActivity).apply {
                        text = "➜"; setTextColor(Color.LTGRAY); textSize = 18f
                        setOnClickListener {
                            editUrl.setText(getItem(position)); editUrl.setSelection(editUrl.text.length)
                            suggestionsPopup?.dismiss()
                        }
                    }
                    row.addView(label); row.addView(insert)
                    return row
                }
            }
        }
        suggestionsPopup = PopupWindow(list, editUrl.width.coerceAtLeast(250), (items.size * 52).coerceAtMost(320), false).apply {
            isOutsideTouchable = true
            isFocusable = false
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF202020.toInt()))
            showAsDropDown(editUrl)
        }
    }

    private fun buildSearchUrl(query: String): String {
        val encoded = Uri.encode(query)
        return when (selectedSearchProvider()) {
            SEARCH_SEARXNG -> SEARXNG_SEARCH_BASE + Uri.encode(SEARXNG_ENGINE_PREFIX + query)
            SEARCH_VIDEO -> SEARXNG_SEARCH_BASE + Uri.encode(VIDEO_SEARCH_ENGINE_PREFIX + query)
            else -> "https://www.google.com/search?q=" + encoded
        }
    }

    private fun loadFromAddressBar() {
        var input = editUrl.text.toString().trim()
        if (input.isEmpty()) return

        input = when {
            Patterns.WEB_URL.matcher(input).matches() && !input.startsWith("http") -> "https://$input"
            input.contains(" ") || !input.contains(".") -> {
                val searchUrl = buildSearchUrl(input)
                searchUrl
            }
            !input.startsWith("http://") && !input.startsWith("https://") -> "https://$input"
            else -> input
        }

        if (currentTab().isHome) {
            currentTab().isHome = false
            webViewContainer.removeAllViews()
            webViewContainer.addView(
                currentWebView(),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        navigateWithSafetyCheck(currentWebView(), input)
    }

    // ---------------- قائمة ☰ ----------------

    private fun showBrowserMenu() {
        val options = arrayOf(
            if (hideMedia) "إظهار الوسائط" else "إخفاء الوسائط بالكامل",
            "العلامات المرجعية",
            if (currentTab().desktopMode) "عرض الجوال (لهذا التبويب)" else "عرض سطح المكتب (لهذا التبويب)",
            "مانع الإعلانات وحماية التصفح",
            "تكبير/تصغير الخط",
            "السجل",
            "ترجمة إلى العربية",
            "الأذونات",
            "البحث",
            if (nightMode) "إيقاف الوضع الليلي" else "تفعيل الوضع الليلي"
        )

        AlertDialog.Builder(this)
            .setTitle("خيارات المتصفح")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleHideMedia()
                    1 -> showBookmarksDialog()
                    2 -> toggleDesktopMode()
                    3 -> showAdBlockSettingsDialog()
                    4 -> showFontZoomDialog()
                    5 -> showHistoryDialog()
                    6 -> runTranslate()
                    7 -> showPermissionsDialog()
                    8 -> showSearchProviderDialog()
                    9 -> toggleNightMode()
                }
            }
            .show()
    }

    private fun showSearchProviderDialog() {
        val providers = arrayOf(
            SEARCH_SEARXNG to "بحث SearXNG",
            SEARCH_GOOGLE to "Google",
            SEARCH_VIDEO to "بحث فيديو"
        )
        val selected = selectedSearchProvider()
        val items = providers.map { (id, label) ->
            if (id == selected) "$label    ✅" else label
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("البحث")
            .setItems(items) { dialog, which ->
                val provider = providers[which].first
                saveSearchProvider(provider)
                dialog.dismiss()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showPermissionsDialog() {
        fun rows() = listOf(
            permissionStateText("الكاميرا (الأمامية والخلفية)", cameraAllowed()),
            permissionStateText("الميكروفون", micAllowed()),
            permissionStateText("الموقع الجغرافي", locationAllowed()),
            "تحديث الخدمات — فحص إصدار WebView"
        )

        val items = ArrayList(rows())
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.select_dialog_item, items)
        val dialog = AlertDialog.Builder(this)
            .setTitle("الأذونات")
            .setAdapter(adapter, null)
            .setNegativeButton("إغلاق", null)
            .create()
        dialog.show()

        // نحدّد المستمع بعد show() حتى تبقى القائمة مفتوحة وتتحدّث الحالة أمام المستخدم
        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> togglePermission("camera", !cameraAllowed(), arrayOf(Manifest.permission.CAMERA), false)
                1 -> togglePermission("mic", !micAllowed(), arrayOf(Manifest.permission.RECORD_AUDIO), false)
                2 -> togglePermission(
                    "location", !locationAllowed(),
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), true
                )
                3 -> {
                    showUpdateServicesDialog()
                    return@setOnItemClickListener
                }
            }
            items.clear()
            items.addAll(rows())
            adapter.notifyDataSetChanged()
        }
    }

    /** يعرض فقط خدمة نظام فعلية يمكن معرفة إصدارها وتوجيه المستخدم لتحديثها رسمياً. */
    private fun showUpdateServicesDialog() {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        } else null
        val message = if (packageInfo != null) {
            "مكوّن WebView: ${packageInfo.packageName}\nالإصدار: ${packageInfo.versionName ?: "غير معروف"}\n\nيتم تحديث WebView من متجر Google Play أو من تحديثات النظام، بحسب الجهاز."
        } else {
            "تعذّر قراءة إصدار WebView من النظام على هذا الجهاز. افتح تحديثات النظام أو متجر التطبيقات للتحقق من وجود تحديث."
        }
        AlertDialog.Builder(this)
            .setTitle("تحديث الخدمات")
            .setMessage(message)
            .setPositiveButton("فتح صفحة WebView الرسمية") { _, _ ->
                val packageName = packageInfo?.packageName ?: "com.google.android.webview"
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                try {
                    startActivity(marketIntent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                    } catch (_: Exception) {
                        Toast.makeText(this, "تعذّر فتح متجر التطبيقات", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun togglePermission(key: String, newValue: Boolean, androidPerms: Array<String>, anyOf: Boolean) {
        settingsPrefs().edit().putBoolean(key, newValue).apply()
        // عند السماح نطلب صلاحية أندرويد مسبقًا حتى تعمل مباشرة عندما يطلبها الموقع
        if (newValue) ensureAndroidPermissions(androidPerms, anyOf) { }
    }

    /** نافذة تأكيد فتح نافذة منبثقة؛ عند «سماح» يُفتح الرابط في تبويب جديد. */
    private var popupDialogShowing = false

    private fun showPopupConfirmDialog(url: String) {
        if (popupDialogShowing || isFinishing || isDestroyed) return   // نافذة واحدة في كل مرة
        popupDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("نافذة منبثقة")
            .setMessage("هل تريد فتح نافذة منبثقة؟")
            .setPositiveButton("سماح") { _, _ -> openPopupInNewTab(url) }
            .setNegativeButton("رفض", null)
            .setOnDismissListener { popupDialogShowing = false }
            .show()
    }

    private fun openPopupInNewTab(rawUrl: String) {
        val cleanUrl = rawUrl.trim()
        val lower = cleanUrl.lowercase()
        val isWebUrl = lower.startsWith("http://") || lower.startsWith("https://")

        // إذا كانت النافذة المنبثقة تحتوي رابط ملف مباشر، فزر «سماح» يبدأ
        // التنزيل مباشرة بدل تحميل الرابط داخل WebView ثم فقدان حدث التنزيل.
        if (isWebUrl && looksLikeDirectFileUrl(cleanUrl)) {
            DownloadsRepository.startNewDownload(
                this,
                cleanUrl,
                showToast = true,
                userAgent = currentWebView().settings.userAgentString,
                referer = currentWebView().url
            )
            return
        }

        if (!isWebUrl || MegaSupport.isMegaFileLink(cleanUrl)) {
            // magnet/ftp/Mega/تطبيق خارجي: يتولاها مسار التنقل الحالي بدون تبويب فارغ
            navigateWithSafetyCheck(currentWebView(), cleanUrl)
            return
        }

        createTabInternal(isHome = false, url = null)
        switchToTab(tabs.size - 1)
        navigateWithSafetyCheck(tabs[tabs.size - 1].webView, cleanUrl)
        persistTabs()
    }
    private fun toggleSetting(key: String, value: Boolean, onEnabled: (Boolean) -> Unit) {
        settingsPrefs().edit().putBoolean(key, value).apply()
        onEnabled(value)
        Toast.makeText(this, if (value) "تم التفعيل" else "تم الإيقاف", Toast.LENGTH_SHORT).show()
    }

    private fun requestAndroidPermIfNeeded(permission: String) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), ANDROID_PERM_CODE)
        }
    }

    // 0) تنزيل -> يفتح DownLS10 مباشرة
    private fun openDownLS10() {
        val intent = Intent(this, DownLS10MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    // 1) إخفاء الوسائط بالكامل
    private fun toggleHideMedia() {
        hideMedia = !hideMedia
        settingsPrefs().edit().putBoolean("hideMedia", hideMedia).apply()
        tabs.forEach { tab ->
            tab.webView.settings.blockNetworkImage = hideMedia
            tab.webView.settings.loadsImagesAutomatically = !hideMedia
        }
        if (hideMedia) applyHideMediaJs(currentWebView()) else currentWebView().reload()
        Toast.makeText(this, if (hideMedia) "تم إخفاء الوسائط (سيبقى مفعّلاً دائماً)" else "تم إظهار الوسائط", Toast.LENGTH_SHORT).show()
    }

    private fun applyHideMediaJs(webView: WebView) {
        val js = """
            (function(){
                var css = 'img, video, iframe, embed, object, picture, svg { display:none !important; }';
                var style = document.getElementById('__downls10_hide_media__');
                if (!style) {
                    style = document.createElement('style');
                    style.id = '__downls10_hide_media__';
                    document.head.appendChild(style);
                }
                style.innerHTML = css;
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // ⭐ إضافة الصفحة الحالية مباشرة
    private fun addCurrentPageToBookmarks() {
        if (currentTab().isHome) {
            Toast.makeText(this, "لا توجد صفحة لإضافتها", Toast.LENGTH_SHORT).show()
            return
        }
        val url = currentWebView().url
        val title = currentWebView().title
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "لا توجد صفحة لإضافتها", Toast.LENGTH_SHORT).show()
            return
        }
        if (BrowserStorage.isBookmarked(this, url)) {
            Toast.makeText(this, "الصفحة مضافة مسبقاً للعلامات", Toast.LENGTH_SHORT).show()
            return
        }
        BrowserStorage.addBookmark(this, title ?: url, url)
        Toast.makeText(this, "تمت الإضافة للعلامات المرجعية", Toast.LENGTH_SHORT).show()
    }

    // 2) العلامات المرجعية (عرض + حذف + استيراد/تصدير)
    private fun showBookmarksDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .create()

        fun refresh() {
            container.removeAllViews()
            val bookmarks = BrowserStorage.getBookmarks(this)
            if (bookmarks.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "لا توجد علامات مرجعية بعد"
                    setTextColor(Color.LTGRAY)
                    setPadding(0, 16, 0, 16)
                })
            } else {
                bookmarks.forEach { bookmark ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 20, 0, 20)
                    }
                    val titleText = TextView(this).apply {
                        text = bookmark.title
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener {
                            createBrowsingTab(bookmark.url)
                            dialog.dismiss()
                        }
                    }
                    val deleteBtn = Button(this).apply {
                        text = "✕"
                        setTextColor(Color.WHITE)
                        setPadding(24, 0, 24, 0)
                        setOnClickListener {
                            BrowserStorage.removeBookmark(this@BrowserActivity, bookmark.url)
                            refresh()
                        }
                    }
                    row.addView(titleText)
                    row.addView(deleteBtn)
                    container.addView(row)

                    container.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(Color.parseColor("#333333"))
                    })
                }
            }

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 24, 0, 0)
            }
            val exportBtn = Button(this).apply {
                text = "تصدير"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { exportBookmarks { refresh() } }
            }
            val importBtn = Button(this).apply {
                text = "استيراد"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { importBookmarks { refresh() } }
            }
            actionsRow.addView(exportBtn)
            actionsRow.addView(importBtn)
            container.addView(actionsRow)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 8, 8, 8)
        }
        titleRow.addView(TextView(this).apply {
            text = "العلامات المرجعية"
            textSize = 20f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(Button(this).apply {
            text = "X"
            setTextColor(Color.WHITE)
            setOnClickListener {
                AlertDialog.Builder(this@BrowserActivity)
                    .setMessage("هل تريد حذف جميع العلامات المرجعية؟")
                    .setPositiveButton("موافق") { _, _ ->
                        BrowserStorage.getBookmarks(this@BrowserActivity).forEach { BrowserStorage.removeBookmark(this@BrowserActivity, it.url) }
                        refresh()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        })
        dialog.setCustomTitle(titleRow)
        refresh()
        dialog.show()
    }

    private fun exportBookmarks(onFinished: () -> Unit) {
        pendingBookmarkExportContent = BrowserStorage.buildBookmarksHtml(this)
        try {
            BookmarksFileManager.startExport(this, pendingBookmarkExportContent!!)
        } catch (e: Exception) {
            pendingBookmarkExportContent = null
            Toast.makeText(this, "فشل فتح حفظ العلامات: ${e.message ?: "غير معروف"}", Toast.LENGTH_LONG).show()
        }
        onFinished()
    }

    private fun importBookmarks(onFinished: () -> Unit) {
        try {
            BookmarksFileManager.startImport(this)
        } catch (e: Exception) {
            Toast.makeText(this, "فشل فتح اختيار ملف العلامات: ${e.message ?: "غير معروف"}", Toast.LENGTH_LONG).show()
        }
        onFinished()
    }

    // 3) عرض سطح المكتب (خاص بالتبويب الحالي فقط)
    private fun toggleDesktopMode() {
        val tab = currentTab()
        tab.desktopMode = !tab.desktopMode
        currentWebView().settings.userAgentString = if (tab.desktopMode) desktopUA else mobileUA
        currentWebView().reload()
        Toast.makeText(this, if (tab.desktopMode) "عرض سطح المكتب مفعّل لهذا التبويب" else "عرض الجوال مفعّل لهذا التبويب", Toast.LENGTH_SHORT).show()
    }

    private fun applyDesktopViewportJs(webView: WebView) {
        val js = """
            (function(){
                var meta = document.querySelector('meta[name=viewport]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.setAttribute('name', 'viewport');
                    document.head.appendChild(meta);
                }
                meta.setAttribute('content', 'width=1024, initial-scale=1, user-scalable=yes');
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // الوضع الليلي: يقلب ألوان أي موقع فاتح إلى أسود، ويترك الموقع الداكن كما هو.
    // الحالة محفوظة دائماً (تبقى بعد إغلاق التطبيق)، ويُطبَّق مباشرة على كل التبويبات عند التشغيل.
    private fun toggleNightMode() {
        nightMode = !nightMode
        settingsPrefs().edit().putBoolean("nightMode", nightMode).apply()
        tabs.forEach { tab ->
            tab.webView.setBackgroundColor(if (nightMode) Color.BLACK else Color.WHITE)
            if (!tab.isHome) {
                if (nightMode) applyNightModeJs(tab.webView) else removeNightModeJs(tab.webView)
            }
        }
        Toast.makeText(this, if (nightMode) "الوضع الليلي مفعّل (ثابت دائماً)" else "تم إيقاف الوضع الليلي", Toast.LENGTH_SHORT).show()
    }

    private var lastNightInject = 0L

    /** آخر حكم محفوظ لهذا الموقع: true = داكن أصلاً، false = فاتح، null = غير معروف. */
    private fun nightHostState(host: String?): Boolean? {
        if (host.isNullOrBlank()) return null
        val raw = settingsPrefs().getString("nightHosts", null) ?: return null
        return try {
            val obj = org.json.JSONObject(raw)
            if (obj.has(host)) obj.getBoolean(host) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun rememberNightHost(host: String?, dark: Boolean) {
        if (host.isNullOrBlank()) return
        try {
            val raw = settingsPrefs().getString("nightHosts", null)
            var obj = if (raw != null) org.json.JSONObject(raw) else org.json.JSONObject()
            if (obj.length() > 400) obj = org.json.JSONObject()
            if (obj.has(host) && obj.getBoolean(host) == dark) return
            obj.put(host, dark)
            settingsPrefs().edit().putString("nightHosts", obj.toString()).apply()
        } catch (e: Exception) { }
    }

    /** يقرأ ما حكمت به الصفحة (داكنة أم فاتحة) ويحفظه لهذا الموقع ليُطبَّق أسرع في الزيارة القادمة. */
    private fun recordNightResult(webView: WebView) {
        val host = try { Uri.parse(webView.url ?: "").host } catch (e: Exception) { null } ?: return
        webView.evaluateJavascript(
            "(window.__downls10NightDark === true ? '1' : (window.__downls10NightDark === false ? '0' : ''))"
        ) { result ->
            val value = result?.trim('"')
            if (value == "1") rememberNightHost(host, true)
            else if (value == "0") rememberNightHost(host, false)
        }
    }

    /**
     * يطبّق الوضع الليلي على الصفحة. آمن للاستدعاء المتكرر (يُستدعى عند بدء الصفحة، وظهورها، وتقدّم التحميل، واكتمالها).
     * الصفحة الفاتحة تُقلب إلى أسود، والصفحة الداكنة أصلاً تبقى كما هي (يُفحص لون الخلفية الفعلي في عدة نقاط من الشاشة).
     */
    private fun applyNightModeJs(webView: WebView) {
        lastNightInject = System.currentTimeMillis()
        val host = try { Uri.parse(webView.url ?: "").host } catch (e: Exception) { null }
        val hostDark = when (nightHostState(host)) {
            true -> "true"
            false -> "false"
            null -> "null"
        }
        val js = """
            (function(hostDark) {
                try {
                    window.__downls10NightOff = false;
                    var ID = '__downls10_night_mode__';
                    var CSS_ON = 'html{filter:invert(1) hue-rotate(180deg) !important;background:#fff !important;}' +
                        'img,video,iframe,picture,svg,canvas,[style*="background-image"]{filter:invert(1) hue-rotate(180deg) !important;}';

                    function styleEl() {
                        var s = document.getElementById(ID);
                        if (!s) {
                            s = document.createElement('style');
                            s.id = ID;
                            (document.head || document.documentElement).appendChild(s);
                        }
                        return s;
                    }
                    function setInverted(on) {
                        var s = styleEl();
                        var css = on ? CSS_ON : '';
                        if (s.textContent !== css) s.textContent = css;
                        window.__downls10NightInverted = on;
                    }
                    function parseColor(str) {
                        var m = String(str).match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?/);
                        if (!m) return null;
                        return { r: +m[1], g: +m[2], b: +m[3], a: (m[4] === undefined ? 1 : parseFloat(m[4])) };
                    }
                    // لون الخلفية الفعلي عند نقطة من الشاشة (أول عنصر له خلفية صريحة)
                    function backgroundAt(x, y) {
                        var els = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [];
                        for (var i = 0; i < els.length; i++) {
                            var cs = getComputedStyle(els[i]);
                            var c = parseColor(cs.backgroundColor);
                            if (c && c.a > 0.6) return c;
                            if (cs.backgroundImage && cs.backgroundImage !== 'none') return null;
                        }
                        return 'canvas';
                    }
                    function detectDark() {
                        if (!document.body) return null;
                        var w = window.innerWidth, h = window.innerHeight;
                        if (!w || !h) return null;
                        var scheme = String(getComputedStyle(document.documentElement).colorScheme || '').trim();
                        var canvasLum = (scheme === 'dark') ? 0 : 255;
                        var pts = [0.1, 0.3, 0.5, 0.7, 0.9];
                        var dark = 0, light = 0;
                        for (var i = 0; i < pts.length; i++) {
                            for (var j = 0; j < pts.length; j++) {
                                var bg = backgroundAt(Math.floor(w * pts[i]), Math.floor(h * pts[j]));
                                if (bg === null) continue;
                                var lum = (bg === 'canvas') ? canvasLum : (0.299 * bg.r + 0.587 * bg.g + 0.114 * bg.b);
                                if (lum < 110) dark++; else light++;
                            }
                        }
                        if (dark + light < 6) return null;
                        return (dark / (dark + light)) >= 0.55;
                    }
                    function run() {
                        if (window.__downls10NightOff) return;
                        var d = detectDark();
                        if (d === null) {
                            // لم نستطع الحكم بعد (الصفحة لم تُرسم): نستخدم المحفوظ لهذا الموقع، وإلا نفترضها فاتحة
                            if (window.__downls10NightInverted === undefined) setInverted(hostDark !== true);
                            return;
                        }
                        window.__downls10NightDark = d;
                        setInverted(!d);
                    }
                    run();

                    if (!window.__downls10NightHooked) {
                        window.__downls10NightHooked = true;
                        var timer = null;
                        var later = function() { clearTimeout(timer); timer = setTimeout(run, 250); };
                        document.addEventListener('DOMContentLoaded', run);
                        window.addEventListener('load', function() { run(); setTimeout(run, 800); setTimeout(run, 2000); });
                        setTimeout(run, 300);
                        setTimeout(run, 1000);
                        // إذا بدّل الموقع نفسه بين فاتح وداكن نعيد الحكم
                        var opts = { attributes: true, attributeFilter: ['class', 'style', 'data-theme', 'data-bs-theme'] };
                        if (document.documentElement) new MutationObserver(later).observe(document.documentElement, opts);
                        var bodyWatch = setInterval(function() {
                            if (document.body) {
                                new MutationObserver(later).observe(document.body, opts);
                                clearInterval(bodyWatch);
                            }
                        }, 200);
                        setTimeout(function() { clearInterval(bodyWatch); }, 5000);
                    }
                } catch (e) { }
            })($hostDark);
        """
        webView.evaluateJavascript(js, null)
    }

    private fun removeNightModeJs(webView: WebView) {
        val js = """
            (function(){
                window.__downls10NightOff = true;
                window.__downls10NightInverted = undefined;
                var style = document.getElementById('__downls10_night_mode__');
                if (style) style.textContent = '';
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // 4) مانع الإعلانات وحماية التصفح (كل القوائم)
    private fun showAdBlockSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        fun refresh() {
            container.removeAllViews()
            val globalRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 16)
            }
            globalRow.addView(TextView(this).apply {
                text = "مانع الإعلانات"
                setTextColor(Color.WHITE)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            globalRow.addView(Button(this).apply {
                text = if (AdBlocker.isGloballyEnabled(this@BrowserActivity)) "مفعّل" else "معطّل"
                setOnClickListener {
                    AdBlocker.setGloballyEnabled(this@BrowserActivity, !AdBlocker.isGloballyEnabled(this@BrowserActivity))
                    tabs.forEach { tab -> if (!tab.isHome) tab.webView.reload() }
                    refresh()
                }
            })
            container.addView(globalRow)
            AdBlocker.LISTS.forEach { def ->
                val block = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 16, 0, 16)
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val nameText = TextView(this).apply {
                    text = def.displayName
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val toggleBtn = Button(this).apply {
                    val enabled = AdBlocker.isEnabled(this@BrowserActivity, def.id)
                    text = if (enabled) "مفعّل" else "متوقف"
                    setOnClickListener {
                        val newVal = !AdBlocker.isEnabled(this@BrowserActivity, def.id)
                        AdBlocker.setEnabled(this@BrowserActivity, def.id, newVal)
                        tabs.forEach { tab -> if (!tab.isHome) tab.webView.reload() }
                        refresh()
                    }
                }
                row.addView(nameText)
                row.addView(toggleBtn)

                val statusRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val statusText = TextView(this).apply {
                    text = "${AdBlocker.ruleCount(this@BrowserActivity, def.id)} قاعدة - ${AdBlocker.lastUpdateText(this@BrowserActivity, def.id)}"
                    setTextColor(Color.LTGRAY)
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val updateBtn = Button(this).apply {
                    text = "تحديث"
                    setOnClickListener {
                        AdBlocker.updateList(this@BrowserActivity, def.id) { success, count, error ->
                            if (!success) {
                                Toast.makeText(this@BrowserActivity, "${def.displayName}: فشل - $error", Toast.LENGTH_LONG).show()
                            }
                            refresh()
                        }
                    }
                }
                statusRow.addView(statusText)
                statusRow.addView(updateBtn)

                block.addView(row)
                block.addView(statusRow)
                container.addView(block)

                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#333333"))
                })
            }
        }

        refresh()

        AlertDialog.Builder(this)
            .setTitle("مانع الإعلانات وحماية التصفح")
            .setView(scroll)
            .setPositiveButton("إغلاق", null)
            .show()
    }

    // 5) تكبير/تصغير الخط
    private fun showFontZoomDialog() {
        val current = savedFontZoom()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 8, 24, 8)
        }

        val sizeText = TextView(this).apply {
            text = "حجم الخط: ${current}%"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val decrease = Button(this).apply {
            text = "تصغير -"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val increase = Button(this).apply {
            text = "تكبير +"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        controls.addView(decrease)
        controls.addView(increase)

        val close = Button(this).apply {
            text = "إغلاق"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
        }

        root.addView(sizeText)
        root.addView(controls)
        root.addView(close)

        val dialog = AlertDialog.Builder(this)
            .setTitle("تكبير/تصغير الخط")
            .setView(root)
            .create()

        fun applyZoom(value: Int) {
            val zoom = value.coerceIn(50, 200)
            currentWebView().settings.textZoom = zoom
            saveFontZoom(zoom)
            sizeText.text = "حجم الخط: ${zoom}%"
        }

        decrease.setOnClickListener {
            val currentValue = currentWebView().settings.textZoom.coerceIn(50, 200)
            applyZoom(currentValue - 10)
        }

        increase.setOnClickListener {
            val currentValue = currentWebView().settings.textZoom.coerceIn(50, 200)
            applyZoom(currentValue + 10)
        }

        close.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // 6) السجل
    private fun showHistoryDialog() {
        val history = BrowserStorage.getHistory(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "السجل فارغ", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        history.forEach { entry ->
            val displayText = entry.title.ifBlank { entry.url }
            val row = TextView(this).apply {
                text = displayText
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 20, 0, 20)
                setOnClickListener { createBrowsingTab(entry.url) }
            }
            container.addView(row)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#333333"))
            })
        }

        AlertDialog.Builder(this)
            .setTitle("سجل التصفح")
            .setView(scroll)
            .setPositiveButton("مسح السجل") { _, _ -> BrowserStorage.clearHistory(this) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    // 7) ترجمة إلى العربية
    private fun runTranslate() {
        val tab = currentTab()
        if (tab.isHome) {
            Toast.makeText(this, "لا توجد صفحة لترجمتها", Toast.LENGTH_SHORT).show()
            return
        }

        // Translation remains enabled for this tab; every new page is translated automatically.
        tab.translationEnabled = true
        tab.lastTranslatedUrl = ""
        tab.translationInProgress = false
        Toast.makeText(this, "جاري ترجمة الموقع إلى العربية...", Toast.LENGTH_SHORT).show()
        translateTabPageIfNeeded(tab.webView, tab)
    }

    private fun translateTabPageIfNeeded(webView: WebView, tab: Tab) {
        if (!tab.translationEnabled || tab.translationInProgress) return
        val url = webView.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        if (url == tab.lastTranslatedUrl) return

        tab.translationInProgress = true
        PageTranslator.translatePage(webView, "ar") { success, message ->
            tab.translationInProgress = false
            if (success) tab.lastTranslatedUrl = url
            if (isActiveTab(webView) && !isFinishing) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- قائمة التبويبات ----------------

    private fun showTabsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("الصفحات المفتوحة")
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .create()

        fun refresh() {
            container.removeAllViews()
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 20)
            }
            val addBtn = Button(this).apply {
                text = "إضافة صفحة جديدة"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    createHomeTab()
                    dialog.dismiss()
                }
            }
            val closeAllBtn = Button(this).apply {
                text = "إغلاق الكل"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    AlertDialog.Builder(this@BrowserActivity)
                        .setMessage("هل تريد إغلاق جميع الصفحات المفتوحة؟")
                        .setPositiveButton("إغلاق الكل") { _, _ -> closeAllTabs(); dialog.dismiss() }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
            actionsRow.addView(addBtn)
            actionsRow.addView(closeAllBtn)
            container.addView(actionsRow)

            tabs.forEachIndexed { index, tab ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 20, 0, 20)
                }
                val label = if (tab.isHome) "الصفحة الرئيسية" else tab.title
                val tabUrl = if (tab.isHome) null else tab.webView.url
                val titleText = TextView(this).apply {
                    text = if (index == currentTabIndex) "● $label" else label
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        switchToTab(index)
                        dialog.dismiss()
                    }
                }
                val closeBtn = Button(this).apply {
                    text = "✕"
                    setTextColor(Color.WHITE)
                    setPadding(24, 0, 24, 0)
                    setOnClickListener {
                        closeTab(index)
                        refresh()
                    }
                }
                row.addView(titleText)
                row.addView(closeBtn)
                container.addView(row)

                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#333333"))
                })
            }

        }

        refresh()
        dialog.show()
    }

    override fun onBackPressed() {
        val tab = currentTab()
        if (!tab.isHome && navigateHistory(tab, -1)) {
            return
        }
        if (!tab.isHome) {
            tab.isHome = true
            switchToTab(currentTabIndex)
            persistTabs()
            return
        }
        finishAffinity()
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        super.onDestroy()
    }
}
