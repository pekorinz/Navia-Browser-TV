package com.pekorinz.via2.ui

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.util.Base64
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pekorinz.via2.R
import com.pekorinz.via2.adblock.AdBlockerManager
import com.pekorinz.via2.adblock.FilterSubscriptions
import com.pekorinz.via2.data.PreferenceManager
import com.pekorinz.via2.dns.DnsManager
import com.pekorinz.via2.dns.DnsProvider
import com.pekorinz.via2.dpad.CursorOverlayView
import com.pekorinz.via2.dpad.DPadController
import com.pekorinz.via2.dpad.DPadMode
import com.pekorinz.via2.webview.TVWebChromeClient
import com.pekorinz.via2.webview.TVWebView
import com.pekorinz.via2.webview.TVWebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var adBlockerManager: AdBlockerManager
    private lateinit var dpadController: DPadController

    private lateinit var toolbarContainer: ViewGroup
    private lateinit var urlPill: ViewGroup
    private lateinit var webView: TVWebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var cursorOverlay: CursorOverlayView
    private lateinit var etUrl: EditText
    private lateinit var btnAdBlock: ImageButton
    private lateinit var pbLoading: ProgressBar
    private lateinit var settingsScreen: View
    private lateinit var ivSecurity: ImageView
    private lateinit var tvBlockedBadge: TextView
    private lateinit var quickMenu: View
    private lateinit var quickMenuBottom: View
    private lateinit var tabsDrawer: View
    private lateinit var tabsDrawerItems: LinearLayout
    private lateinit var btnTabsDrawerClose: ImageButton
    private lateinit var btnNewTab: View

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val homePageUrl = "file:///android_asset/home_page.html"
    private val dnsCustomUrl = "file:///android_asset/dns_custom.html"
    private val tabsList = mutableListOf<String>()
    private var currentTabIndex = 0
    private val faviconExecutor = Executors.newFixedThreadPool(3)
    private val requestedFavicons = ConcurrentHashMap.newKeySet<String>()

    private var quickMenuHooked = false
    private var quickMenuBottomHooked = false
    private var isTabsDrawerOpen = false
    private var tabsDrawerReturnFocus: View? = null
    private val tabsDrawerFocusItems = mutableListOf<View>()

    private var isQuickMenuOpen = false
    private var currentSetupScreen: View? = null
    private var lastFocusedView: View? = null

    private var isDesktopMode = false
    private var isNightMode = false
    private var isEditingUrl = false
    private var isHomePage = false
    private var isYoutubeTvSite = false
    private var urlBeforeEditing = ""
    private var currentPageTitle: String = ""

    private val backHandler = Handler(Looper.getMainLooper())
    private var isBackLongPressTriggered = false
    private var backConsumedDown = false
    private val backLongPressRunnable = Runnable {
        isBackLongPressTriggered = true
        if (isQuickMenuOpen) hideQuickMenu() else showQuickMenu()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferenceManager = PreferenceManager(this)
        isNightMode = preferenceManager.isNightModeEnabled()
        adBlockerManager = AdBlockerManager(this, preferenceManager)
        DnsManager.applyFromPreferences(this, preferenceManager)

        initViews()
        setupWebView()
        applyNightMode()
        setupDPadController()
        setupToolbarListeners()
        setupSettingsActions()

        tabsList.add(homePageUrl)
        toolbarContainer.visibility = View.GONE

        webView.loadUrl(homePageUrl)
        webView.applyDnsProxyIfNeeded()
    }

    private fun initViews() {
        toolbarContainer = findViewById(R.id.toolbarContainer)
        urlPill = findViewById(R.id.urlPill)
        webView = findViewById(R.id.webView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        cursorOverlay = findViewById(R.id.cursorOverlay)
        settingsScreen = findViewById(R.id.settingsScreen)
        etUrl = findViewById(R.id.etUrl)
        btnAdBlock = findViewById(R.id.btnAdBlock)
        pbLoading = findViewById(R.id.pbLoading)
        ivSecurity = findViewById(R.id.ivSecurity)
        tvBlockedBadge = findViewById(R.id.tvBlockedBadge)
        quickMenu = findViewById(R.id.quickMenu)
        quickMenuBottom = findViewById(R.id.quickMenuBottom)
        tabsDrawer = findViewById(R.id.tabsDrawer)
        tabsDrawerItems = findViewById(R.id.tabsDrawerItems)
        btnTabsDrawerClose = findViewById(R.id.btnTabsDrawerClose)
        btnNewTab = findViewById(R.id.btnNewTab)
        updateAppThemeColors()
    }

    private fun setupWebView() {
        webView.webViewClient = TVWebViewClient(
            adBlockerManager = adBlockerManager,
            dohResolver = DnsManager.currentResolver,
            onPageUrlChanged = { url ->
                val isHomeUrl = url.substringBefore('#') == homePageUrl
                setHomePageNavigation(isHomeUrl)
                setYoutubeTvNavigation(isYoutubeTvUrl(url))
                if (isHomeUrl) etUrl.setText("")
                else if (url.contains("dns_custom.html")) etUrl.setText("navia://dns-custom")
                else if (!etUrl.hasFocus()) etUrl.setText(url)
                if (currentTabIndex in 0 until tabsList.size) tabsList[currentTabIndex] = url
                if (url.substringBefore('#') != homePageUrl && !url.contains("dns_custom.html")) saveSessionIfNeeded()
                updateSecurityIcon(url)
            },
            onLoadingStateChanged = { isLoading -> pbLoading.visibility = if (isLoading) View.VISIBLE else View.GONE },
            onHttpWarning = { url, proceed -> showHttpWarningDialog(url, proceed) },
            onAppAction = { action ->
                when {
                    action == TVWebViewClient.ACTION_RESTORE_TABS -> restorePreviousTabs()
                    action == TVWebViewClient.ACTION_SHOW_TOOLBAR -> runOnUiThread { showToolbar() }
                    action == TVWebViewClient.ACTION_CLOSE_CURRENT_TAB -> runOnUiThread { closeCurrentTabOrGoBack() }
                    action.startsWith("${TVWebViewClient.ACTION_SAVE_DOH}:") -> {
                        val customUrl = action.removePrefix("${TVWebViewClient.ACTION_SAVE_DOH}:")
                        runOnUiThread {
                            preferenceManager.setDnsCustomUrl(customUrl)
                            DnsManager.applyFromPreferences(this, preferenceManager)
                            val r = DnsManager.currentResolver
                            if (r != null) r.configure(DnsProvider.fromId(preferenceManager.getDnsProvider()), customUrl)
                            webView.applyDnsProxyIfNeeded()
                            updateDnsScreen(); updateAdvancedScreen()
                            toast(R.string.setting_dns_custom_saved)
                            closeCurrentTabOrGoBack()
                        }
                    }
                }
            },
            onBlockedCountChanged = { count ->
                runOnUiThread { preferenceManager.addBlockedCount(1L); updateBlockedBadge(count) }
            }
        )

        webView.webChromeClient = TVWebChromeClient(
            onProgressChanged = { progress -> pbLoading.progress = progress },
            onTitleReceived = { title ->
                currentPageTitle = title
                val url = webView.url
                if (!url.isNullOrBlank() && !preferenceManager.isIncognito()) preferenceManager.addHistory(url, title)
            },
            onIconReceived = { view, icon ->
                val host = view.url?.let { Uri.parse(it).host } ?: return@TVWebChromeClient
                val dataUri = encodeFavicon(icon) ?: return@TVWebChromeClient
                preferenceManager.saveFavicon(host, dataUri)
                if (webView.url?.substringBefore('#') == homePageUrl) webView.post { updateHomePageContent() }
            },
            onShowCustomViewCallback = { view, callback -> showCustomView(view, callback) },
            onHideCustomViewCallback = { hideCustomView() }
        )

        webView.setDownloadHandler { url, mimeType -> showDownloadConfirmationDialog(url, mimeType) }

        (webView.webViewClient as? TVWebViewClient)?.onPageFinishedCallback = { _, _ ->
            if (isHomePage) { updateHomePageContent(); webView.requestFocus() }
            else if (isDesktopMode) applyDesktopViewport()
            if (isNightMode) applyNightMode()
            if (isYoutubeTvSite) webView.evaluateJavascript(YOUTUBE_TV_SHADOW_JS, null)
        }
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view
        customViewCallback = callback
        toolbarContainer.visibility = View.GONE
        webView.visibility = View.GONE
        fullscreenContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreenContainer.visibility = View.VISIBLE
        fullscreenContainer.bringToFront()
        cursorOverlay.bringToFront()
        cursorOverlay.visibility = View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenContainer.visibility = View.GONE
        fullscreenContainer.removeView(view)
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        webView.visibility = View.VISIBLE
        cursorOverlay.visibility = View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        webView.requestFocus()
    }

    private fun setupDPadController() {
        dpadController = DPadController(
            webView = webView,
            toolbarContainer = toolbarContainer,
            cursorOverlay = cursorOverlay,
            preferenceManager = preferenceManager,
            onModeChanged = { mode ->
                updateModeUi(mode)
                if (isYoutubeTvSite) {
                    cursorOverlay.isCursorVisible = false
                    webView.isFocusable = true
                    webView.isFocusableInTouchMode = true
                    webView.requestFocus()
                }
            },
            onRequestShowToolbar = { showToolbar() },
            onRequestHideToolbar = { hideToolbar() },
            getActiveFullscreenView = { customView }
        )
        updateModeUi(dpadController.currentMode)
        if (isYoutubeTvSite) dpadController.setPassthroughToPage(true)
    }

    private fun updateSecurityIcon(url: String) {
        val secure = url.startsWith("https://")
        ivSecurity.setImageResource(if (secure) R.drawable.ic_via_lock else R.drawable.ic_via_close)
        ivSecurity.contentDescription = getString(if (secure) R.string.btn_secure else R.string.btn_insecure)
    }

    private fun updateBlockedBadge(count: Int) {
        if (count <= 0) tvBlockedBadge.visibility = View.GONE
        else { tvBlockedBadge.visibility = View.VISIBLE; tvBlockedBadge.text = count.toString() }
    }

    private fun updateModeUi(mode: DPadMode) {
        if (isHomePage) {
            cursorOverlay.isCursorVisible = false
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
        }
        Toast.makeText(this, getString(R.string.mode_changed_format, getString(mode.labelResId)), Toast.LENGTH_SHORT).show()
    }

    fun showToolbar() {
        if (isYoutubeTvSite) return
        if (toolbarContainer.visibility != View.VISIBLE) {
            toolbarContainer.visibility = View.VISIBLE
            toolbarContainer.getChildAt(0)?.requestFocus()
        }
    }

    fun hideToolbar() {
        if (toolbarContainer.visibility != View.GONE) {
            toolbarContainer.visibility = View.GONE
            if (isEditingUrl) finishUrlEditing(restoreOriginal = false, returnToUrlField = false)
            hideKeyboard()
            webView.requestFocus()
        }
    }

    fun toggleToolbar() {
        if (isYoutubeTvSite) return
        if (toolbarContainer.visibility == View.VISIBLE) hideToolbar() else showToolbar()
    }

    private fun setupToolbarListeners() {
        tabsDrawer.setOnClickListener { hideTabsDrawer() }
        findViewById<View>(R.id.tabsDrawerPanel).setOnClickListener { }
        btnTabsDrawerClose.setOnClickListener { hideTabsDrawer() }
        btnNewTab.setOnClickListener {
            tabsList.add(homePageUrl)
            currentTabIndex = tabsList.lastIndex
            webView.loadUrl(homePageUrl); webView.applyDnsProxyIfNeeded()
            hideTabsDrawer()
        }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBackPreservingState()
            else if (!isHomePage) openOrSwitchToTab(homePageUrl)
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { openOrSwitchToTab(homePageUrl) }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { webView.reload() }
        btnAdBlock.setOnClickListener {
            val enabled = !preferenceManager.isAdBlockEnabled()
            preferenceManager.setAdBlockEnabled(enabled)
            Toast.makeText(this, if (enabled) R.string.adblock_active else R.string.adblock_disabled, Toast.LENGTH_SHORT).show()
            webView.reload()
        }
        findViewById<ImageButton>(R.id.btnTabs).setOnClickListener { showTabsDrawer() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showQuickMenu() }
        urlPill.setOnClickListener { startUrlEditing() }
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) { loadEnteredUrl(); true } else false
        }
    }

    private fun loadEnteredUrl() {
        var input = etUrl.text.toString().trim()
        if (input.isNotEmpty()) {
            if (!input.startsWith("http://") && !input.startsWith("https://") && !input.startsWith("file://")) {
                input = if (input.contains(".") && !input.contains(" ")) "https://$input"
                else "https://www.google.com/search?q=${UriEncode(input)}"
            }
            webView.loadUrl(input)
            webView.applyDnsProxyIfNeeded()
            if (isEditingUrl) finishUrlEditing(restoreOriginal = false, returnToUrlField = false)
            else { hideKeyboard(); webView.requestFocus() }
        }
    }

    private fun startUrlEditing() {
        if (isEditingUrl) return
        isEditingUrl = true
        urlBeforeEditing = etUrl.text.toString()
        etUrl.isFocusable = true; etUrl.isFocusableInTouchMode = true; etUrl.isClickable = true; etUrl.isCursorVisible = true
        etUrl.requestFocus(); etUrl.selectAll()
        etUrl.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(etUrl, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun finishUrlEditing(restoreOriginal: Boolean, returnToUrlField: Boolean) {
        if (restoreOriginal) etUrl.setText(urlBeforeEditing)
        isEditingUrl = false
        etUrl.isCursorVisible = false; etUrl.isClickable = false; etUrl.isFocusable = false; etUrl.isFocusableInTouchMode = false
        hideKeyboard()
        if (returnToUrlField) urlPill.requestFocus() else webView.requestFocus()
    }

    private fun UriEncode(input: String): String = java.net.URLEncoder.encode(input, "UTF-8")

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            return handleBackKey(event)
        }
        if (customView != null) {
            if (dpadController.handleKeyEvent(event)) return true
            return super.dispatchKeyEvent(event)
        }
        if (isTabsDrawerOpen) return dispatchKeyEventInTabsDrawer(event)
        if (isQuickMenuOpen) {
            if (isYoutubeTvSite) return dispatchKeyEventInBottomSheet(event)
            return dispatchKeyEventInQuickMenu(event)
        }
        if (isSetupVisible()) return dispatchKeyEventInSetup(event)
        if (toolbarContainer.visibility == View.VISIBLE &&
            event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            hideToolbar(); return true
        }
        if (toolbarContainer.visibility == View.VISIBLE && toolbarContainer.hasFocus()) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED -> {
                    if (isQuickMenuOpen) hideQuickMenu() else showQuickMenu()
                    return true
                }
                KeyEvent.KEYCODE_INFO -> {
                    toggleToolbar(); return true
                }
            }
        }
        if (isHomePage) return super.dispatchKeyEvent(event)
        if (dpadController.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun dispatchKeyEventInBottomSheet(event: KeyEvent): Boolean {
        val views = listOfNotNull(
            findViewById<View>(R.id.qmbBrowser),
            findViewById<View>(R.id.qmbAdBlock),
            findViewById<View>(R.id.qmbExit),
            findViewById<View>(R.id.qmbSettings)
        )
        if (views.isEmpty()) return true
        val focused = currentFocus
        var idx = views.indexOf(focused)
        if (idx < 0) { idx = 0; views[0].requestFocus() }
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (isDown) views[idx].performClick()
            return true
        }
        if (!isDown) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> super.dispatchKeyEvent(event)
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val next = if (idx > 0) idx - 1 else views.lastIndex
                views[next].requestFocus(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val next = if (idx < views.lastIndex) idx + 1 else 0
                views[next].requestFocus(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED -> { hideBottomSheetForTv(); return true }
            else -> return super.dispatchKeyEvent(event)
        }
    }

    private fun handleBackKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return true
            backHandler.removeCallbacks(backLongPressRunnable)
            isBackLongPressTriggered = false
            backConsumedDown = false
            if (customView != null) { hideCustomView(); backConsumedDown = true; return true }
            if (isTabsDrawerOpen) { hideTabsDrawer(); backConsumedDown = true; return true }
            if (isQuickMenuOpen) { hideQuickMenu(); backConsumedDown = true; return true }
            if (isSetupVisible()) { goBackInSetup(); backConsumedDown = true; return true }
            if (isEditingUrl) { finishUrlEditing(restoreOriginal = true, returnToUrlField = true); backConsumedDown = true; return true }
            backHandler.postDelayed(backLongPressRunnable, 1000L)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            backHandler.removeCallbacks(backLongPressRunnable)
            if (isBackLongPressTriggered || backConsumedDown) return true
            if (isYoutubeTvSite) {
                webView.sendBackToPage()
            } else {
                if (toolbarContainer.visibility == View.VISIBLE) hideToolbar() else showToolbar()
            }
            return true
        }
        return true
    }

    private fun setHomePageNavigation(enabled: Boolean) {
        if (isHomePage == enabled) return
        isHomePage = enabled
        if (!::dpadController.isInitialized) return
        if (enabled) {
            cursorOverlay.isCursorVisible = false
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            webView.requestFocus()
        } else {
            webView.isFocusable = dpadController.currentMode == DPadMode.FOCO
            webView.isFocusableInTouchMode = dpadController.currentMode == DPadMode.FOCO
            cursorOverlay.isCursorVisible = dpadController.currentMode == DPadMode.CURSOR
        }
    }

    private fun isYoutubeTvUrl(url: String): Boolean {
        val normalized = url.lowercase()
        val isYoutubeHost = normalized.startsWith("https://www.youtube.com") ||
            normalized.startsWith("https://youtube.com") ||
            normalized.startsWith("https://m.youtube.com") ||
            normalized.startsWith("http://www.youtube.com") ||
            normalized.startsWith("http://youtube.com") ||
            normalized.startsWith("http://m.youtube.com")
        if (!isYoutubeHost) return false
        return normalized.contains("/tv")
    }

    private fun setYoutubeTvNavigation(enabled: Boolean) {
        if (isYoutubeTvSite == enabled) return
        isYoutubeTvSite = enabled
        if (!::dpadController.isInitialized) return
        dpadController.setPassthroughToPage(enabled)
        if (enabled) {
            cursorOverlay.isCursorVisible = false
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            webView.requestFocus()
        } else {
            webView.isFocusable = dpadController.currentMode == DPadMode.FOCO
            webView.isFocusableInTouchMode = dpadController.currentMode == DPadMode.FOCO
            cursorOverlay.isCursorVisible = dpadController.currentMode == DPadMode.CURSOR
        }
    }

    private fun dispatchKeyEventInSetup(event: KeyEvent): Boolean {
        val screen = currentSetupScreen ?: settingsScreen
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (currentFocus == null || !isDescendantOf(currentFocus, screen)) { focusFirstInScreen(screen); return true }
            return super.dispatchKeyEvent(event)
        }
        if (!isDown) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> super.dispatchKeyEvent(event)
            }
        }
        if (currentFocus == null || !isDescendantOf(currentFocus, screen)) { focusFirstInScreen(screen); return true }
        val antes = currentFocus
        val consumido = super.dispatchKeyEvent(event)
        if (currentFocus == null) antes?.requestFocus() ?: focusFirstInScreen(screen)
        return consumido
    }

    private fun isDescendantOf(v: View?, root: View): Boolean {
        var p: android.view.ViewParent? = v?.parent
        while (p != null) { if (p === root) return true; p = p.parent }
        return false
    }

    private fun focusFirstInScreen(screen: View): Boolean {
        val ids = when (screen.id) {
            R.id.adBlockScreen -> intArrayOf(R.id.rowAdBlockMaster, R.id.rowAdBlockBuiltin, R.id.rowAdBlockCustom, R.id.rowAdBlockSubscriptions, R.id.rowAdBlockCurrentSite, R.id.rowSettingBlockedStats)
            R.id.subscriptionsScreen -> intArrayOf(R.id.rowSubsInterval, R.id.btnSubsUpdate)
            R.id.navigationScreen -> intArrayOf(R.id.rowNavSpeed, R.id.rowNavMode, R.id.rowNavCenterCursor)
            R.id.appearanceScreen -> intArrayOf(R.id.rowAppNight, R.id.rowAppDesktop, R.id.rowAppIncognito)
            R.id.dnsScreen -> intArrayOf(R.id.rowDnsProvider, R.id.rowDnsCustom, R.id.rowDnsClearCache, R.id.rowDnsTest)
            R.id.advancedScreen -> intArrayOf(R.id.rowAdvDnsCache, R.id.rowAdvDnsTest, R.id.rowAdvClearHistory, R.id.rowAdvClearCookies)
            else -> intArrayOf(R.id.rowMenuGeneral, R.id.rowMenuAppearance, R.id.rowMenuPrivacy, R.id.rowMenuDns, R.id.rowMenuAdvanced, R.id.rowMenuAbout)
        }
        for (id in ids) {
            val v = findViewById<View>(id) ?: continue
            if (v.visibility == View.VISIBLE && v.isEnabled && v.requestFocus()) return true
        }
        return screen.findViewById<View>(R.id.btnHeaderBack)?.requestFocus() ?: false
    }

    private fun dispatchKeyEventInQuickMenu(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (currentFocus == null) { focusFirstQuickMenuItem(); return true }
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isQuickMenuFocused()) { focusFirstQuickMenuItem(); return true }
                return moveFocusInGrid(event)
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED -> { if (isDown) hideQuickMenu(); return true }
            else -> return true
        }
    }

    private fun moveFocusInGrid(event: KeyEvent): Boolean {
        val grid = GRID_IDS.mapNotNull { id -> findViewById<View>(id) }
        val top = TOP_IDS.mapNotNull { id -> findViewById<View>(id) }
        val focused = currentFocus
        val topIndex = top.indexOf(focused)
        val gridIndex = grid.indexOf(focused)
        if (topIndex < 0 && gridIndex < 0) { focusFirstQuickMenuItem(); return true }
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) return super.dispatchKeyEvent(event)
        if (!isDown) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (gridIndex < 0) { val column = if (topIndex == 0) 0 else GRID_COLS - 1; grid.getOrNull(column)?.requestFocus() }
                else if (gridIndex + GRID_COLS <= grid.lastIndex) grid[gridIndex + GRID_COLS].requestFocus()
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (gridIndex >= 0) {
                    if (gridIndex - GRID_COLS >= 0) grid[gridIndex - GRID_COLS].requestFocus()
                    else top.getOrNull(if (gridIndex == 0) 0 else 1)?.requestFocus()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (topIndex > 0) top[topIndex - 1].requestFocus()
                else if (gridIndex % GRID_COLS > 0) grid[gridIndex - 1].requestFocus()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (topIndex >= 0 && topIndex < top.lastIndex) top[topIndex + 1].requestFocus()
                else if (gridIndex % GRID_COLS < GRID_COLS - 1 && gridIndex + 1 <= grid.lastIndex) grid[gridIndex + 1].requestFocus()
            }
            else -> return true
        }
        return true
    }

    override fun onDestroy() {
        backHandler.removeCallbacks(backLongPressRunnable)
        if (customView != null) hideCustomView()
        faviconExecutor.shutdownNow()
        DnsManager.stop()
        super.onDestroy()
    }

    override fun onPause() { saveSessionIfNeeded(); super.onPause() }

    private fun saveSessionIfNeeded() {
        if (preferenceManager.isIncognito()) return
        val savedTabs = tabsList.filter { (it.startsWith("http://") || it.startsWith("https://")) && !it.contains("dns_custom.html") }
        if (savedTabs.isEmpty()) return
        val selectedUrl = tabsList.getOrNull(currentTabIndex)
        val savedIndex = savedTabs.indexOf(selectedUrl).takeIf { it >= 0 } ?: savedTabs.lastIndex
        preferenceManager.saveSessionTabs(savedTabs, savedIndex)
    }

    private fun restorePreviousTabs() {
        val savedTabs = preferenceManager.getSavedSessionTabs()
        if (savedTabs.isEmpty()) { toast(R.string.no_previous_tabs); return }
        tabsList.clear(); tabsList.addAll(savedTabs)
        currentTabIndex = preferenceManager.getSavedSessionIndex().coerceIn(0, tabsList.lastIndex)
        webView.loadUrl(tabsList[currentTabIndex]); webView.applyDnsProxyIfNeeded()
    }

    private fun updateHomePageContent() {
        val bookmarks = preferenceManager.getBookmarks()
        val history = preferenceManager.getHistory().take(20)
        val bookmarkData = homePageItemsJson(bookmarks)
        val historyData = homePageItemsJson(history)
        val savedTabCount = preferenceManager.getSavedSessionTabs().size
        webView.evaluateJavascript("window.naviaHome && window.naviaHome.render($bookmarkData, $historyData, $savedTabCount);", null)
        requestMissingFavicons(bookmarks + history)
    }

    private fun homePageItemsJson(items: List<Pair<String, String>>): JSONArray = JSONArray().apply {
        items.forEach { (url, title) ->
            val host = Uri.parse(url).host
            put(JSONObject().put("url", url).put("title", title).put("icon", host?.let(preferenceManager::getCachedFavicon).orEmpty()))
        }
    }

    private fun requestMissingFavicons(items: List<Pair<String, String>>) {
        val hosts = items.mapNotNull { (url, _) -> Uri.parse(url).host?.lowercase() }.distinct().filter { host ->
            preferenceManager.getCachedFavicon(host) == null && requestedFavicons.add(host)
        }
        hosts.forEach { host ->
            faviconExecutor.execute {
                val dataUri = downloadFavicon(host)
                if (dataUri != null) {
                    preferenceManager.saveFavicon(host, dataUri)
                    runOnUiThread { if (webView.url?.substringBefore('#') == homePageUrl) updateHomePageContent() }
                }
            }
        }
    }

    private fun downloadFavicon(host: String): String? {
        for (path in listOf("/favicon.ico", "/favicon.png", "/apple-touch-icon.png")) {
            val connection = (URL("https://$host$path").openConnection() as? HttpURLConnection) ?: continue
            try {
                connection.connectTimeout = 3500; connection.readTimeout = 3500
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(this))
                connection.connect()
                if (connection.responseCode !in 200..299 || connection.url.protocol != "https" || connection.contentLengthLong > MAX_FAVICON_BYTES) continue
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(4096); var total = 0
                    while (true) { val read = input.read(buffer); if (read < 0) break; total += read; if (total > MAX_FAVICON_BYTES) return null; output.write(buffer, 0, read) }
                    output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_FAVICON_DIMENSION || bounds.outHeight > MAX_FAVICON_DIMENSION) continue
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) return encodeFavicon(bitmap)
            } catch (e: IOException) { android.util.Log.d(TAG, "No se pudo obtener el icono de $host$path", e) }
            finally { connection.disconnect() }
        }
        return null
    }

    private fun encodeFavicon(bitmap: Bitmap): String? {
        val ratio = minOf(1f, 64f / maxOf(bitmap.width, bitmap.height))
        val normalized = if (ratio < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        else bitmap
        val output = ByteArrayOutputStream()
        if (!normalized.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
        return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && isQuickMenuOpen) hideQuickMenu()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) webView.clearCache(false)
    }

    private fun showQuickMenu() {
        if (isQuickMenuOpen) return
        if (isYoutubeTvSite) { showBottomSheetForTv(); return }
        lastFocusedView = currentFocus
        if (!quickMenuHooked) { hookQuickMenu(); quickMenuHooked = true }
        isQuickMenuOpen = true
        val panel = findViewById<View>(R.id.quickMenuPanel)
        quickMenu.visibility = View.VISIBLE
        quickMenu.alpha = 0f
        quickMenu.animate().alpha(1f).setDuration(180).start()
        val density = resources.displayMetrics.density
        val hiddenX = (panel.width.toFloat().takeIf { it > 0f } ?: (400f * density))
        panel.translationX = hiddenX; panel.alpha = 0.8f; panel.scaleX = 0.95f; panel.scaleY = 0.95f
        panel.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        trapFocusBehindMenu(true)
        quickMenu.post { focusFirstQuickMenuItem() }
    }

    private fun hideQuickMenu() {
        if (!isQuickMenuOpen) return
        if (isYoutubeTvSite) { hideBottomSheetForTv(); return }
        isQuickMenuOpen = false
        val panel = findViewById<View>(R.id.quickMenuPanel)
        val density = resources.displayMetrics.density
        val hiddenX = (panel.width.toFloat().takeIf { it > 0f } ?: (400f * density))
        panel.animate().translationX(hiddenX).alpha(0.8f).scaleX(0.95f).scaleY(0.95f).setDuration(180).setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
            quickMenu.visibility = View.GONE; panel.translationX = 0f; panel.alpha = 1f; panel.scaleX = 1f; panel.scaleY = 1f
        }.start()
        quickMenu.animate().alpha(0f).setDuration(180).start()
        trapFocusBehindMenu(false)
        val target = lastFocusedView ?: findViewById<View>(R.id.btnSettings)
        if (target != null && target.isAttachedToWindow) target.requestFocus()
        else findViewById<View>(R.id.btnSettings)?.requestFocus()
    }

    private fun showBottomSheetForTv() {
        if (isQuickMenuOpen) return
        lastFocusedView = currentFocus
        if (!quickMenuBottomHooked) { hookQuickMenuBottom(); quickMenuBottomHooked = true }
        // Actualiza el icono del AdBlock segun su estado
        findViewById<ImageView>(R.id.qmbAdBlockIcon)?.setImageResource(
            if (preferenceManager.isAdBlockEnabled()) R.drawable.ic_via_shield else R.drawable.ic_via_shield_off
        )
        isQuickMenuOpen = true
        val panel = findViewById<View>(R.id.quickMenuBottomPanel)
        quickMenuBottom.visibility = View.VISIBLE
        quickMenuBottom.alpha = 0f
        quickMenuBottom.animate().alpha(1f).setDuration(180).start()
        val density = resources.displayMetrics.density
        val hiddenY = (panel.height.toFloat().takeIf { it > 0f } ?: (240f * density))
        panel.translationY = hiddenY
        panel.alpha = 0.85f
        panel.scaleX = 0.96f
        panel.scaleY = 0.96f
        panel.animate()
            .translationY(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        for (v in listOf(webView, cursorOverlay, settingsScreen, toolbarContainer)) {
            v.isFocusable = false
            v.isFocusableInTouchMode = false
        }
        findViewById<View>(R.id.qmbBrowser)?.requestFocus()
    }

    private fun hideBottomSheetForTv() {
        if (!isQuickMenuOpen) return
        isQuickMenuOpen = false
        val panel = findViewById<View>(R.id.quickMenuBottomPanel)
        val density = resources.displayMetrics.density
        val hiddenY = (panel.height.toFloat().takeIf { it > 0f } ?: (240f * density))
        panel.animate()
            .translationY(hiddenY)
            .alpha(0.85f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                quickMenuBottom.visibility = View.GONE
                panel.translationY = 0f
                panel.alpha = 1f
                panel.scaleX = 1f
                panel.scaleY = 1f
            }
            .start()
        quickMenuBottom.animate().alpha(0f).setDuration(200).start()
        for (v in listOf(webView, cursorOverlay, settingsScreen, toolbarContainer)) {
            v.isFocusable = true
            v.isFocusableInTouchMode = true
        }
        if (isYoutubeTvSite) {
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            webView.requestFocus()
        }
    }

    private fun hookQuickMenuBottom() {
        findViewById<View>(R.id.qmbBrowser)?.setOnClickListener {
            hideBottomSheetForTv()
            openOrSwitchToTab(homePageUrl)
            // Fix: al ir a la home, forzar navegacion de home (oculta cursor, foco WebView)
            webView.post { setHomePageNavigation(true) }
        }
        findViewById<View>(R.id.qmbAdBlock)?.setOnClickListener {
            val enabled = !preferenceManager.isAdBlockEnabled()
            preferenceManager.setAdBlockEnabled(enabled)
            Toast.makeText(this, if (enabled) R.string.adblock_active else R.string.adblock_disabled, Toast.LENGTH_SHORT).show()
            findViewById<ImageView>(R.id.qmbAdBlockIcon)?.setImageResource(
                if (enabled) R.drawable.ic_via_shield else R.drawable.ic_via_shield_off
            )
            webView.reload()
        }
        findViewById<View>(R.id.qmbExit)?.setOnClickListener { hideBottomSheetForTv(); finish() }
        findViewById<View>(R.id.qmbSettings)?.setOnClickListener { hideBottomSheetForTv(); showSettingsScreen() }
    }

    private fun isQuickMenuFocused(): Boolean {
        val f = currentFocus ?: return false
        return TOP_IDS.any { it == f.id } || GRID_IDS.any { it == f.id }
    }

    private fun focusFirstQuickMenuItem(): Boolean {
        val orden = GRID_IDS + TOP_IDS
        for (id in orden) {
            val v = findViewById<View>(id) ?: continue
            if (v.visibility == View.VISIBLE && v.requestFocus()) return true
        }
        return false
    }

    private fun trapFocusBehindMenu(blocked: Boolean) {
        for (v in listOf(webView, cursorOverlay, settingsScreen, toolbarContainer)) {
            v.isFocusable = !blocked
            v.isFocusableInTouchMode = !blocked
        }
        if (blocked && !isQuickMenuFocused()) focusFirstQuickMenuItem()
    }

    private fun hookQuickMenu() {
        findViewById<View>(R.id.qmClose).setOnClickListener { hideQuickMenu() }
        findViewById<View>(R.id.qmPower).setOnClickListener { hideQuickMenu(); showExitConfirmationDialog() }
        findViewById<View>(R.id.qmSettings).setOnClickListener { hideQuickMenu(); showSettingsScreen() }
        findViewById<View>(R.id.qmTabs).setOnClickListener { hideQuickMenu(); showTabsDrawer() }
        findViewById<View>(R.id.qmHistory).setOnClickListener { hideQuickMenu(); showHistoryDialog() }
        findViewById<View>(R.id.qmBookmarks).setOnClickListener { hideQuickMenu(); showBookmarksDialog() }
        findViewById<View>(R.id.qmAddBookmark).setOnClickListener { hideQuickMenu(); toggleCurrentBookmark() }
        findViewById<View>(R.id.qmDownloads).setOnClickListener { hideQuickMenu(); openDownloadsFolder() }
        findViewById<View>(R.id.qmNight).setOnClickListener { hideQuickMenu(); toggleNightMode() }
        findViewById<View>(R.id.qmIncognito).setOnClickListener { hideQuickMenu(); toggleIncognito() }
        findViewById<View>(R.id.qmDesktop).setOnClickListener { hideQuickMenu(); toggleDesktopMode() }
        findViewById<View>(R.id.qmTools).setOnClickListener { hideQuickMenu(); showMoreMenu() }
        findViewById<View>(R.id.qmFindInPage).setOnClickListener { hideQuickMenu(); showFindInPageDialog() }
    }

    private fun toast(resId: Int) { Toast.makeText(this, resId, Toast.LENGTH_SHORT).show() }

    private fun toggleCurrentBookmark() {
        val url = webView.url ?: return
        val title = webView.title.orEmpty().ifBlank { Uri.parse(url).host ?: url }
        val current = preferenceManager.getBookmarks()
        val exists = current.any { it.first == url }
        if (exists) { preferenceManager.setBookmarks(current.filter { it.first != url }); toast(R.string.qm_bookmark_exists) }
        else { preferenceManager.setBookmarks(current + (url to title)); toast(R.string.qm_bookmark_added) }
    }

    private fun showBookmarksDialog() {
        val items = preferenceManager.getBookmarks()
        if (items.isEmpty()) { AlertDialog.Builder(this).setTitle(R.string.qm_bookmarks).setMessage(R.string.qm_bookmarks_empty).setPositiveButton(R.string.dialog_ok, null).showThemed(); return }
        val labels = items.map { it.second }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.qm_bookmarks)
            .setItems(labels) { d, which -> webView.loadUrl(items[which].first); webView.applyDnsProxyIfNeeded(); d.dismiss() }
            .setNegativeButton(R.string.dialog_cancel, null).showThemed()
    }

    private fun showHistoryDialog() {
        val items = preferenceManager.getHistory()
        if (items.isEmpty()) { AlertDialog.Builder(this).setTitle(R.string.qm_history).setMessage(R.string.qm_history_empty).setPositiveButton(R.string.dialog_ok, null).showThemed(); return }
        val labels = items.map { it.second }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.qm_history)
            .setItems(labels) { d, which -> webView.loadUrl(items[which].first); webView.applyDnsProxyIfNeeded(); d.dismiss() }
            .setNeutralButton(R.string.qm_history_clear) { _, _ -> preferenceManager.clearHistory(); toast(R.string.qm_history_cleared) }
            .setNegativeButton(R.string.dialog_cancel, null).showThemed()
    }

    private fun openDownloadsFolder() {
        try {
            val intent = android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) { toast(R.string.qm_no_downloads_app) }
    }

    private fun toggleNightMode() {
        isNightMode = !isNightMode
        preferenceManager.setNightModeEnabled(isNightMode)
        applyNightMode()
        toast(if (isNightMode) R.string.qm_night_on else R.string.qm_night_off)
    }

    private fun applyNightMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            webView.settings.forceDark = if (isNightMode) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        } else {
            webView.evaluateJavascript(if (isNightMode) NIGHT_MODE_JS else NIGHT_MODE_OFF_JS, null)
        }
        updateAppThemeColors()
    }

    private fun updateAppThemeColors() {
        val toolbarContent = Color.parseColor("#3C3C3C")
        toolbarContainer.setBackgroundColor(Color.WHITE)
        toolbarContainer.getChildAt(0)?.setBackgroundResource(R.drawable.bg_via_url_pill_light)
        etUrl.setTextColor(toolbarContent)
        etUrl.setHintTextColor(Color.parseColor("#757575"))
        ivSecurity.setColorFilter(Color.parseColor("#5F6368"))
        tvBlockedBadge.setTextColor(Color.parseColor("#d64146"))
        for (id in intArrayOf(R.id.btnRefresh, R.id.btnBack, R.id.btnForward, R.id.btnHome, R.id.btnAdBlock, R.id.btnTabs, R.id.btnSettings)) {
            findViewById<ImageButton>(id).apply { setColorFilter(toolbarContent); setBackgroundResource(R.drawable.bg_via_toolbar_button_light) }
        }
        for (v in setupScreens()) { v.setBackgroundColor(Color.WHITE); updateTextViewsRecursive(v, false); updateSetupImagesRecursive(v, false); updateSetupRowsBackgroundRecursive(v, false) }
        val qmCardContainer = (quickMenu as? ViewGroup)?.getChildAt(0)
        qmCardContainer?.background?.setTint(Color.WHITE)
        for (id in TOP_IDS + GRID_IDS) {
            val cell = findViewById<View>(id) ?: continue
            if (cell is ViewGroup) for (i in 0 until cell.childCount) when (val child = cell.getChildAt(i)) {
                is ImageView -> child.setColorFilter(Color.parseColor("#1C1B1D"))
                is TextView -> child.setTextColor(Color.parseColor("#3C3C3C"))
            }
        }
    }

    private fun updateSetupRowsBackgroundRecursive(view: View, isNight: Boolean) {
        val bgRes = if (isNight) R.drawable.bg_setup_item else R.drawable.bg_setup_item_light
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if ((child.isClickable || child.isFocusable) && child.id != R.id.btnNewTab && child.id != R.id.btnTabsDrawerClose) child.setBackgroundResource(bgRes)
                updateSetupRowsBackgroundRecursive(child, isNight)
            }
        }
    }

    private fun updateSetupImagesRecursive(view: View, isNight: Boolean) {
        if (view is ViewGroup) for (i in 0 until view.childCount) updateSetupImagesRecursive(view.getChildAt(i), isNight)
        else if (view is ImageView) view.setColorFilter(if (isNight) Color.WHITE else Color.parseColor("#5F6368"))
    }

    private fun updateTextViewsRecursive(view: View, isNight: Boolean) {
        val textColor = if (isNight) Color.parseColor("#F8FAFC") else Color.parseColor("#DE000000")
        val textSecColor = if (isNight) Color.parseColor("#94A3B8") else Color.parseColor("#8A000000")
        if (view is ViewGroup) for (i in 0 until view.childCount) updateTextViewsRecursive(view.getChildAt(i), isNight)
        else if (view is TextView) {
            val primaryTextSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
            view.setTextColor(if (view.textSize >= primaryTextSize || view.id == R.id.tvHeaderTitle) textColor else textSecColor)
        }
    }

    private fun AlertDialog.Builder.showThemed(): AlertDialog {
        val dialog = this.create()
        val isNight = isNightMode
        val bgColor = if (isNight) Color.parseColor("#1E293B") else Color.WHITE
        dialog.window?.let { w ->
            w.setGravity(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
            val dm = resources.displayMetrics
            w.setLayout((430 * dm.density).toInt(), (dm.heightPixels * 0.88f).toInt())
            w.setWindowAnimations(R.style.RightDrawerAnimation)
            w.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = 24f * dm.density; setColor(bgColor) })
            val lp = w.attributes; lp.x = (28 * dm.density).toInt(); w.attributes = lp
        }
        dialog.setOnShowListener {
            val decor = dialog.window?.decorView
            if (decor != null) applyThemeToDialogView(decor, isNight)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { setTextColor(getColor(R.color.setup_accent)); setPadding(dp(18), dp(12), dp(18), dp(16)) }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(getColor(R.color.setup_text_secondary)); setPadding(dp(18), dp(12), dp(18), dp(16)) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply { setTextColor(getColor(R.color.setup_text_secondary)); setPadding(dp(18), dp(12), dp(18), dp(16)) }
        }
        dialog.show()
        return dialog
    }

    private fun applyThemeToDialogView(view: View, isNight: Boolean) {
        val textColor = if (isNight) Color.parseColor("#F8FAFC") else Color.parseColor("#1C1B1D")
        if (view is ViewGroup) for (i in 0 until view.childCount) applyThemeToDialogView(view.getChildAt(i), isNight)
        else if (view is TextView) view.setTextColor(textColor)
    }

    private fun toggleIncognito() {
        val on = !preferenceManager.isIncognito()
        preferenceManager.setIncognito(on)
        if (on) {
            val cm = android.webkit.CookieManager.getInstance()
            cm.removeAllCookies(null); cm.flush()
            webView.clearHistory(); webView.clearCache(true); webView.clearFormData()
            preferenceManager.clearHistory()
            toast(R.string.qm_incognito_on)
        } else toast(R.string.qm_incognito_off)
        webView.reload()
    }

    private fun showFindInPageDialog() {
        val input = EditText(this).apply { hint = getString(R.string.qm_find_hint); setSingleLine(); setPadding(48, 32, 48, 32) }
        AlertDialog.Builder(this).setTitle(R.string.qm_find).setView(input)
            .setPositiveButton(R.string.qm_find_next) { _, _ -> val q = input.text.toString(); if (q.isNotEmpty()) { webView.findAllAsync(q); toast(R.string.qm_find_started) } }
            .setNeutralButton(R.string.qm_find_clear) { _, _ -> webView.clearMatches() }
            .setNegativeButton(R.string.dialog_cancel, null).showThemed()
    }

    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        val ua = if (isDesktopMode) getString(R.string.ua_desktop) else null
        webView.settings.userAgentString = ua ?: android.webkit.WebSettings.getDefaultUserAgent(this)
        webView.reload()
        toast(if (isDesktopMode) R.string.qm_desktop_on else R.string.qm_desktop_off)
    }

    private fun applyDesktopViewport() {
        val viewportWidth = webView.width.coerceAtLeast(1)
        webView.evaluateJavascript("""
            (() => { const viewports = Array.from(document.querySelectorAll('meta[name="viewport"]')); let viewport = viewports[0]; if (!viewport) { viewport = document.createElement('meta'); viewport.name = 'viewport'; document.head.appendChild(viewport); } const directives = (viewport.content || '').split(',').map(v => v.trim()).filter(v => v && !/^(width|initial-scale)\s*=/i.test(v)); viewport.content = ['width=${'$'}viewportWidth', ...directives].join(', '); viewports.slice(1).forEach(i => i.remove()); })();
        """.trimIndent(), null)
    }

    private fun showMoreMenu() {
        val modes = DPadMode.entries
        val labels = modes.map { getString(it.labelResId) }.toTypedArray()
        val current = dpadController.currentMode.ordinal
        AlertDialog.Builder(this).setTitle(R.string.mode_dialog_title)
            .setSingleChoiceItems(labels, current) { dialog, which -> dpadController.switchMode(modes[which]); dialog.dismiss() }
            .setNeutralButton(R.string.setting_center_cursor_summary) { _, _ -> cursorOverlay.centerCursor() }
            .setNegativeButton(R.string.dialog_cancel, null).showThemed()
    }

    fun openOrSwitchToTab(url: String) {
        val existingIndex = tabsList.indexOf(url)
        if (existingIndex >= 0) currentTabIndex = existingIndex
        else { tabsList.add(url); currentTabIndex = tabsList.lastIndex }
        hideTabsDrawer(); hideQuickMenu(); showBrowserScreen()
        webView.loadUrl(url); webView.applyDnsProxyIfNeeded()
    }

    fun closeCurrentTabOrGoBack() {
        if (tabsList.size > 1) {
            tabsList.removeAt(currentTabIndex)
            currentTabIndex = currentTabIndex.coerceIn(0, tabsList.lastIndex)
            webView.loadUrl(tabsList[currentTabIndex]); webView.applyDnsProxyIfNeeded()
        } else {
            tabsList[0] = homePageUrl
            webView.loadUrl(homePageUrl); webView.applyDnsProxyIfNeeded()
        }
    }

    private fun showTabsDrawer() {
        if (isTabsDrawerOpen) return
        tabsDrawerReturnFocus = currentFocus
        isTabsDrawerOpen = true
        tabsDrawerItems.removeAllViews()
        tabsDrawerFocusItems.clear()
        tabsList.forEachIndexed { index, url ->
            val isCurrent = index == currentTabIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(76); setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true; isFocusable = true; isFocusableInTouchMode = false; isSelected = isCurrent
                setBackgroundResource(R.drawable.bg_tab_drawer_row)
                contentDescription = getString(if (isCurrent) R.string.tab_current_description else R.string.tab_select_description, titleForTab(url))
            }
            val iconContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#EDF2F7")) }
            }
            val iconView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), android.view.Gravity.CENTER)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val host = Uri.parse(url).host?.lowercase()
                val cachedDataUri = host?.let { preferenceManager.getCachedFavicon(it) }
                if (cachedDataUri != null && cachedDataUri.startsWith("data:image")) {
                    try { val base64 = cachedDataUri.substringAfter(","); val bytes = Base64.decode(base64, Base64.DEFAULT); setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) }
                    catch (e: Exception) { setImageResource(R.drawable.ic_via_tabs); setColorFilter(getColor(R.color.setup_accent)) }
                } else if (url.contains("dns_custom.html")) { setImageResource(R.drawable.ic_via_shield); setColorFilter(getColor(R.color.setup_accent)) }
                else if (url.startsWith("file://")) { setImageResource(R.drawable.ic_via_home); setColorFilter(getColor(R.color.setup_accent)) }
                else { setImageResource(R.drawable.ic_via_tabs); setColorFilter(getColor(R.color.setup_accent)) }
            }
            iconContainer.addView(iconView)
            row.addView(iconContainer)
            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, 0, dp(10), 0) }
            textColumn.addView(TextView(this).apply {
                text = titleForTab(url); setTextColor(getColor(R.color.setup_text)); textSize = 15.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            textColumn.addView(TextView(this).apply {
                text = when { url.contains("dns_custom.html") -> "Ajustes del navegador"; url.startsWith("file://") -> "Página principal"; else -> url }
                setTextColor(getColor(R.color.setup_text_secondary)); textSize = 12.5f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(2), 0, 0)
            })
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (isCurrent) row.addView(TextView(this).apply {
                text = "ACTUAL"; textSize = 11f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(getColor(R.color.setup_accent))
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#EEF3FF")) }
                setPadding(dp(8), dp(4), dp(8), dp(4))
            })
            if (tabsList.size > 1) {
                val btnCloseTab = ImageButton(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) }
                    background = getDrawable(R.drawable.bg_setup_item_light); setImageResource(R.drawable.ic_close)
                    setColorFilter(getColor(R.color.setup_text_secondary)); setPadding(dp(10), dp(10), dp(10), dp(10))
                    scaleType = ImageView.ScaleType.FIT_CENTER; isFocusable = true; isFocusableInTouchMode = false; contentDescription = "Cerrar pestaña"
                    setOnClickListener {
                        tabsList.removeAt(index)
                        if (currentTabIndex >= tabsList.size) currentTabIndex = tabsList.lastIndex
                        if (index == currentTabIndex) { webView.loadUrl(tabsList[currentTabIndex]); webView.applyDnsProxyIfNeeded() }
                        showTabsDrawer()
                    }
                }
                row.addView(btnCloseTab); tabsDrawerFocusItems.add(btnCloseTab)
            }
            row.setOnClickListener { currentTabIndex = index; webView.loadUrl(tabsList[index]); webView.applyDnsProxyIfNeeded(); hideTabsDrawer() }
            tabsDrawerItems.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            tabsDrawerFocusItems.add(row)
        }
        tabsDrawerFocusItems.add(btnNewTab)
        tabsDrawer.visibility = View.VISIBLE
        tabsDrawer.post { val active = tabsDrawerItems.getChildAt(currentTabIndex); if (active == null || !active.requestFocus()) btnNewTab.requestFocus() }
    }

    private fun hideTabsDrawer() {
        if (!isTabsDrawerOpen) return
        isTabsDrawerOpen = false
        tabsDrawer.visibility = View.GONE
        val target = tabsDrawerReturnFocus
        tabsDrawerReturnFocus = null
        if (target != null && target.isAttachedToWindow && target.isShown) target.requestFocus()
        else webView.requestFocus()
    }

    private fun dispatchKeyEventInTabsDrawer(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_RED -> { if (isDown) hideTabsDrawer(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (currentFocus == null) { focusFirstTabsDrawerItem(); return true }
                return super.dispatchKeyEvent(event)
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isDown) return true
                val items = listOf(btnTabsDrawerClose) + tabsDrawerFocusItems
                val current = items.indexOf(currentFocus)
                val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                val next = if (current < 0) 0 else (current + step).coerceIn(0, items.lastIndex)
                items[next].requestFocus(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            else -> return true
        }
    }

    private fun focusFirstTabsDrawerItem() { (tabsDrawerItems.getChildAt(currentTabIndex) ?: btnNewTab).requestFocus() }

    private fun titleForTab(url: String): String = when {
        url.startsWith(dnsCustomUrl) || url.contains("dns_custom.html") -> getString(R.string.setting_dns_custom)
        url.startsWith("file://") -> getString(R.string.new_tab)
        else -> Uri.parse(url).host ?: url
    }

    private fun showDownloadConfirmationDialog(url: String, mimeType: String?) {
        AlertDialog.Builder(this).setTitle("Descargar Archivo").setMessage("¿Deseas descargar este archivo mediante el gestor del sistema?")
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, Uri.parse(url).lastPathSegment ?: "archivo")
                    }
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    Toast.makeText(this, "Descarga iniciada", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { Toast.makeText(this, "Error al iniciar la descarga", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton(R.string.dialog_no, null).showThemed()
    }

    private fun showHttpWarningDialog(url: String, onProceed: () -> Unit) {
        AlertDialog.Builder(this).setTitle(R.string.http_warning_title).setMessage(R.string.http_warning_message)
            .setPositiveButton(R.string.dialog_yes) { _, _ -> onProceed() }
            .setNegativeButton(R.string.dialog_no, null).showThemed()
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this).setTitle(R.string.exit_dialog_title).setMessage(R.string.exit_dialog_message)
            .setPositiveButton(R.string.dialog_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.dialog_no, null).showThemed()
    }

    private fun showSettingsScreen() { lastFocusedView = currentFocus; showSetup(null, R.id.rowMenuGeneral) }

    private fun goBackInSetup() {
        val subs = findViewById<View>(R.id.subscriptionsScreen)
        val adb = findViewById<View>(R.id.adBlockScreen)
        when (currentSetupScreen) {
            subs -> showSetup(adb, R.id.rowAdBlockSubscriptions)
            adb -> showSetup(null, R.id.rowMenuPrivacy)
            findViewById<View>(R.id.navigationScreen) -> showSetup(null, R.id.rowMenuGeneral)
            findViewById<View>(R.id.appearanceScreen) -> showSetup(null, R.id.rowMenuAppearance)
            findViewById<View>(R.id.dnsScreen) -> showSetup(null, R.id.rowMenuDns)
            findViewById<View>(R.id.advancedScreen) -> showSetup(null, R.id.rowMenuAdvanced)
            findViewById<View>(R.id.aboutScreen) -> showSetup(null, R.id.rowMenuAbout)
            else -> showBrowserScreen()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showSetup(screen: View?, focusId: Int) {
        for (v in setupScreens()) v.visibility = View.GONE
        val target = screen ?: settingsScreen
        target.visibility = View.VISIBLE
        currentSetupScreen = target
        val titleRes = when (target.id) {
            settingsScreen.id -> R.string.settings_title
            R.id.navigationScreen -> R.string.settings_section_navigation
            R.id.appearanceScreen -> R.string.settings_section_appearance
            R.id.adBlockScreen -> R.string.settings_adblock_title
            R.id.dnsScreen -> R.string.settings_section_dns
            R.id.advancedScreen -> R.string.settings_section_advanced
            R.id.aboutScreen -> R.string.settings_section_about
            else -> null
        }
        titleRes?.let { target.findViewById<TextView>(R.id.tvHeaderTitle)?.setText(it) }
        setFocusableBehindSetup(true)
        updateSetupValues()
        target.post { target.findViewById<View>(focusId)?.requestFocus() ?: findViewById<View>(focusId)?.requestFocus() }
    }

    private fun setFocusableBehindSetup(blocked: Boolean) {
        for (v in listOf(webView, toolbarContainer)) { v.isFocusable = !blocked; v.isFocusableInTouchMode = !blocked }
    }

    private fun setupScreens(): List<View> = listOf(
        settingsScreen, findViewById(R.id.adBlockScreen), findViewById(R.id.subscriptionsScreen),
        findViewById(R.id.navigationScreen), findViewById(R.id.appearanceScreen), findViewById(R.id.dnsScreen),
        findViewById(R.id.advancedScreen), findViewById(R.id.aboutScreen)
    )

    private fun showBrowserScreen() {
        for (v in setupScreens()) v.visibility = View.GONE
        currentSetupScreen = null
        setFocusableBehindSetup(false)
        val target = lastFocusedView ?: findViewById<View>(R.id.btnSettings)
        if (target != null && target.isAttachedToWindow) target.requestFocus()
        else findViewById<View>(R.id.btnSettings)?.requestFocus()
    }

    private fun isSetupVisible(): Boolean = setupScreens().any { it.visibility == View.VISIBLE }

    private fun updateSetupValues() {
        updateSettingsScreen(); updateAdBlockScreen(); updateSubscriptionsScreen(); updateNavigationScreen()
        updateAppearanceScreen(); updateDnsScreen(); updateAdvancedScreen(); updateAboutScreen()
    }

    private fun updateNavigationScreen() {
        val speed = preferenceManager.getCursorSpeedMultiplier()
        val speedLabel = when { speed < 0.8f -> R.string.speed_slow; speed > 1.2f -> R.string.speed_fast; else -> R.string.speed_medium }
        findViewById<TextView>(R.id.tvNavSpeedValue).setText(speedLabel)
        findViewById<TextView>(R.id.tvNavModeValue).setText(preferenceManager.getSavedMode().labelResId)
    }

    private fun updateAppearanceScreen() {
        setSwitch(R.id.swAppNight, isNightMode)
        setSwitch(R.id.swAppDesktop, isDesktopMode)
        setSwitch(R.id.swAppIncognito, preferenceManager.isIncognito())
    }

    private fun updateDnsScreen() {
        val resolver = DnsManager.currentResolver
        findViewById<TextView>(R.id.tvDnsProviderValue).text = DnsProvider.fromId(preferenceManager.getDnsProvider()).title
        findViewById<TextView>(R.id.tvDnsCustomValue).text = preferenceManager.getDnsCustomUrl().ifBlank { getString(R.string.dns_custom_unconfigured) }
        findViewById<TextView>(R.id.tvDnsStatusValue).text = DnsManager.stats()
        findViewById<TextView>(R.id.tvDnsCacheValue).text = (resolver?.cacheSize() ?: 0).toString()
    }

    private fun updateAdvancedScreen() {
        findViewById<TextView>(R.id.tvAdvDnsCacheValue).text = (DnsManager.currentResolver?.cacheSize() ?: 0).toString()
    }

    private fun updateAboutScreen() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val vName = packageInfo.versionName.orEmpty().ifBlank { "1.0" }
        findViewById<TextView>(R.id.tvAboutVersion).text = "Versión $vName"
        findViewById<TextView>(R.id.tvAboutRules).text = adBlockerManager.statsLine()
        val activeSubscriptions = adBlockerManager.subscriptions.states().values.count { it.enabled }
        findViewById<TextView>(R.id.tvAboutSubs).text = "$activeSubscriptions listas · ${adBlockerManager.subscriptions.activeRuleCount()} reglas"
        findViewById<TextView>(R.id.tvAboutBlocked).text = preferenceManager.getBlockedTotal().toString()
        findViewById<TextView>(R.id.tvAboutDns).text = DnsProvider.fromId(preferenceManager.getDnsProvider()).title
    }

    private fun updateSubscriptionsScreen() {
        val days = preferenceManager.getSubsIntervalDays()
        findViewById<TextView>(R.id.tvSubsInterval).text = if (days <= 0) getString(R.string.subs_interval_never) else getString(R.string.subs_interval_days, days)
    }

    private fun updateAdBlockScreen() {
        val on = preferenceManager.isAdBlockEnabled()
        val total = preferenceManager.getBlockedTotal()
        val subsEnabled = preferenceManager.isAdBlockSubscriptionsEnabled()
        val builtinEnabled = preferenceManager.isAdBlockBuiltinEnabled()
        findViewById<TextView>(R.id.tvAdBlockMasterSummary).text = if (on) getString(R.string.setting_adblock_master_summary_on, total) else getString(R.string.setting_adblock_master_summary_off)
        setSwitch(R.id.swAdBlockMaster, on); setSwitch(R.id.swAdBlockBuiltin, builtinEnabled); setSwitch(R.id.swAdBlockCustom, subsEnabled)
        val states = adBlockerManager.subscriptions.states()
        val active = states.values.count { it.enabled && it.rules > 0 }
        val totalRules = adBlockerManager.subscriptions.activeRuleCount()
        findViewById<TextView>(R.id.tvAdBlockCustomSummary).text = if (active > 0) getString(R.string.setting_adblock_custom_summary_active, active, totalRules) else getString(R.string.setting_adblock_custom_summary_none)
        val currentUrl = webView.url.orEmpty()
        val currentHost = Uri.parse(currentUrl).host
        val siteRow = findViewById<View>(R.id.rowAdBlockCurrentSite)
        val siteHost = findViewById<TextView>(R.id.tvCurrentSiteHost)
        if (currentHost == null) { siteHost.setText(R.string.setting_adblock_site_no_page); siteRow.isEnabled = false; siteRow.isFocusable = false; setSwitch(R.id.swAdBlockCurrentSite, false) }
        else { val whitelisted = adBlockerManager.domainWhitelist.isWhitelisted(currentUrl); siteHost.text = currentHost; siteRow.isEnabled = true; siteRow.isFocusable = true; setSwitch(R.id.swAdBlockCurrentSite, !whitelisted) }
        findViewById<TextView>(R.id.tvSettingBlockedStats).text = getString(R.string.setting_blocked_stats_value_format, total)
    }

    private fun setSwitch(id: Int, on: Boolean) { findViewById<View>(id)?.setBackgroundResource(if (on) R.drawable.bg_switch_on else R.drawable.bg_switch_off) }
    private fun updateSettingsScreen() {}

    private fun setupSettingsActions() {
        findViewById<View>(R.id.settingsHeader).findViewById<View>(R.id.btnHeaderBack).setOnClickListener { showBrowserScreen() }
        findViewById<View>(R.id.rowMenuGeneral).setOnClickListener { showSetup(findViewById(R.id.navigationScreen), R.id.rowNavSpeed) }
        findViewById<View>(R.id.rowMenuAppearance).setOnClickListener { showSetup(findViewById(R.id.appearanceScreen), R.id.rowAppNight) }
        findViewById<View>(R.id.rowMenuPrivacy).setOnClickListener { showSetup(findViewById(R.id.adBlockScreen), R.id.rowAdBlockMaster) }
        findViewById<View>(R.id.rowMenuDns).setOnClickListener { showSetup(findViewById(R.id.dnsScreen), R.id.rowDnsProvider) }
        findViewById<View>(R.id.rowMenuAdvanced).setOnClickListener { showSetup(findViewById(R.id.advancedScreen), R.id.rowAdvDnsCache) }
        findViewById<View>(R.id.rowMenuAbout).setOnClickListener { showSetup(findViewById(R.id.aboutScreen), R.id.btnHeaderBack) }
        findViewById<View>(R.id.adBlockHeader).findViewById<View>(R.id.btnHeaderBack).setOnClickListener { showSetup(null, R.id.rowMenuPrivacy) }
        findViewById<View>(R.id.navigationScreen).findViewById<View>(R.id.btnHeaderBack).setOnClickListener { showSetup(null, R.id.rowMenuGeneral) }
        findViewById<View>(R.id.appearanceScreen).findViewById<View>(R.id.btnHeaderBack).setOnClickListener { showSetup(null, R.id.rowMenuAppearance) }
        findViewById<View>(R.id.dnsScreen).findViewById<View>(R.id.btnHeaderBack).setOnClickListener { showSetup(null, R.id.rowMenuDns) }
        findViewById<View>(R.id.advancedScreen).findViewById<View>(R.id.btnHeaderBack).setOnClickListener { showSetup(null, R.id.rowMenuAdvanced) }
        findViewById<View>(R.id.aboutScreen).findViewById<View>(R.id.btnHeaderBack).setOnClickListener { showSetup(null, R.id.rowMenuAbout) }
        findViewById<View>(R.id.rowNavSpeed).setOnClickListener {
            val speed = preferenceManager.getCursorSpeedMultiplier()
            val next = when { speed < 0.8f -> 1.0f; speed < 1.2f -> 2.0f; else -> 0.5f }
            preferenceManager.setCursorSpeedMultiplier(next); updateNavigationScreen()
        }
        findViewById<View>(R.id.rowNavMode).setOnClickListener { dpadController.switchMode(preferenceManager.getSavedMode().next()); updateNavigationScreen() }
        findViewById<View>(R.id.rowNavCenterCursor).setOnClickListener { cursorOverlay.centerCursor() }
        findViewById<View>(R.id.rowAppNight).setOnClickListener { toggleNightMode(); updateAppearanceScreen() }
        findViewById<View>(R.id.rowAppDesktop).setOnClickListener { toggleDesktopMode(); updateAppearanceScreen() }
        findViewById<View>(R.id.rowAppIncognito).setOnClickListener { toggleIncognito(); updateAppearanceScreen() }
        findViewById<View>(R.id.rowDnsProvider).setOnClickListener { showDnsSelectionDialog() }
        findViewById<View>(R.id.rowDnsCustom).setOnClickListener { showDnsCustomUrlDialog() }
        findViewById<View>(R.id.rowDnsClearCache).setOnClickListener { DnsManager.currentResolver?.clearCache(); updateDnsScreen(); toast(R.string.dns_cache_cleared) }
        findViewById<View>(R.id.rowDnsTest).setOnClickListener { runDnsTest() }
        findViewById<View>(R.id.rowAdvDnsCache).setOnClickListener { DnsManager.currentResolver?.clearCache(); updateAdvancedScreen(); toast(R.string.dns_cache_cleared) }
        findViewById<View>(R.id.rowAdvDnsTest).setOnClickListener { runDnsTest() }
        findViewById<View>(R.id.rowAdvClearHistory).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.setting_clear_history).setMessage(R.string.setting_clear_history_summary)
                .setPositiveButton(R.string.dialog_yes) { _, _ -> webView.clearHistory(); preferenceManager.clearHistory(); toast(R.string.qm_history_cleared) }
                .setNegativeButton(R.string.dialog_cancel, null).showThemed()
        }
        findViewById<View>(R.id.rowAdvClearCookies).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.setting_clear_cookies).setMessage(R.string.setting_clear_cookies_summary)
                .setPositiveButton(R.string.dialog_yes) { _, _ ->
                    val cm = android.webkit.CookieManager.getInstance()
                    cm.removeAllCookies { runOnUiThread { cm.flush(); webView.clearCache(true); webView.clearFormData(); android.webkit.WebStorage.getInstance().deleteAllData(); webView.reload(); toast(R.string.setting_data_cleared) } }
                }
                .setNegativeButton(R.string.dialog_cancel, null).showThemed()
        }
        findViewById<View>(R.id.rowAdBlockMaster).setOnClickListener { preferenceManager.setAdBlockEnabled(!preferenceManager.isAdBlockEnabled()); updateAdBlockScreen(); webView.reload() }
        findViewById<View>(R.id.rowAdBlockBuiltin).setOnClickListener { preferenceManager.setAdBlockBuiltinEnabled(!preferenceManager.isAdBlockBuiltinEnabled()); updateAdBlockScreen(); webView.reload() }
        findViewById<View>(R.id.rowAdBlockCustom).setOnClickListener { preferenceManager.setAdBlockSubscriptionsEnabled(!preferenceManager.isAdBlockSubscriptionsEnabled()); updateAdBlockScreen(); webView.reload() }
        findViewById<View>(R.id.rowAdBlockSubscriptions).setOnClickListener { showSetup(findViewById(R.id.subscriptionsScreen), R.id.rowSubsInterval); refreshSubscriptionsList() }
        findViewById<View>(R.id.rowAdBlockCurrentSite).setOnClickListener {
            val currentUrl = webView.url.orEmpty()
            if (Uri.parse(currentUrl).host == null) return@setOnClickListener
            val whitelisted = adBlockerManager.domainWhitelist.isWhitelisted(currentUrl)
            adBlockerManager.domainWhitelist.setWhitelisted(currentUrl, !whitelisted)
            updateAdBlockScreen(); webView.reload()
        }
        findViewById<View>(R.id.rowSettingBlockedStats).setOnClickListener { preferenceManager.resetBlockedTotal(); updateAdBlockScreen(); toast(R.string.setting_blocked_reset) }
        findViewById<View>(R.id.tvSettingBlockedReset).setOnClickListener { preferenceManager.resetBlockedTotal(); updateAdBlockScreen(); toast(R.string.setting_blocked_reset) }
        findViewById<View>(R.id.btnSubsBack).setOnClickListener { showSetup(findViewById(R.id.adBlockScreen), R.id.rowAdBlockSubscriptions) }
        findViewById<View>(R.id.btnSubsUpdate).setOnClickListener { downloadAllSubscriptions() }
        findViewById<View>(R.id.rowSubsDownloadAll).setOnClickListener { downloadAllSubscriptions() }
        findViewById<View>(R.id.rowSubsInterval).setOnClickListener { showSubsIntervalDialog() }
    }

    private fun refreshSubscriptionsList() {
        val container = findViewById<LinearLayout>(R.id.subsListContainer)
        val empty = findViewById<View>(R.id.tvSubsEmpty)
        container.removeAllViews()
        val states = adBlockerManager.subscriptions.states()
        for (f in adBlockerManager.subscriptions.catalog) {
            container.addView(buildSubsRow(f, states[f.id] ?: FilterSubscriptions.State()))
            container.addView(buildDivider())
        }
        empty.visibility = View.GONE
        val days = preferenceManager.getSubsIntervalDays()
        findViewById<TextView>(R.id.tvSubsInterval).text = if (days <= 0) getString(R.string.subs_interval_never) else getString(R.string.subs_interval_days, days)
    }

    private fun buildSubsRow(f: FilterSubscriptions.Filter, st: FilterSubscriptions.State): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14)); isFocusable = true; isFocusableInTouchMode = false; isClickable = true
            setBackgroundResource(R.drawable.bg_setup_item); minimumHeight = dp(72)
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        textCol.addView(TextView(this).apply { text = f.name; setTextColor(getColor(R.color.setup_text)); textSize = 16f })
        textCol.addView(TextView(this).apply {
            text = when { st.rules > 0 -> getString(R.string.subs_updated_format, st.rules, st.dateLabel()); st.enabled -> getString(R.string.subs_not_downloaded); else -> f.description }
            setTextColor(getColor(R.color.setup_text_secondary)); textSize = 13f; setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol)
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(50), dp(28)).apply { marginStart = dp(16) }; setBackgroundResource(if (st.enabled && st.rules > 0) R.drawable.bg_switch_on else R.drawable.bg_switch_off) })
        row.setOnClickListener {
            if (st.rules <= 0) downloadSingle(f)
            else { adBlockerManager.subscriptions.setEnabled(f.id, !st.enabled); adBlockerManager.reloadSubscriptions(); refreshSubscriptionsList(); webView.reload() }
        }
        return row
    }

    private fun buildDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(16) }
        setBackgroundColor(getColor(R.color.setup_divider))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun downloadSingle(filter: FilterSubscriptions.Filter) {
        Toast.makeText(this, getString(R.string.subs_downloading, 1), Toast.LENGTH_SHORT).show()
        Thread {
            val r = adBlockerManager.subscriptions.download(filter)
            runOnUiThread {
                if (r.ok) { adBlockerManager.reloadSubscriptions(); refreshSubscriptionsList(); updateAdBlockScreen(); webView.reload() }
                else toastMsg(getString(R.string.subs_download_failed, r.error ?: "?"))
            }
        }.apply { isDaemon = true; name = "via2-subs" }.start()
    }

    private fun downloadAllSubscriptions() {
        val all = adBlockerManager.subscriptions.catalog
        toastMsg(getString(R.string.subs_downloading, all.size))
        Thread {
            var ok = 0; var fail = 0; var rules = 0
            for ((index, f) in all.withIndex()) {
                runOnUiThread { toastMsg("(${index + 1}/${all.size}) Descargando: ${f.name}...") }
                val r = adBlockerManager.subscriptions.download(f)
                if (r.ok) { ok++; rules += r.rules } else fail++
                runOnUiThread { refreshSubscriptionsList() }
            }
            preferenceManager.setSubsLastUpdate(System.currentTimeMillis())
            runOnUiThread {
                adBlockerManager.reloadSubscriptions(); refreshSubscriptionsList(); updateAdBlockScreen(); webView.reload()
                val msg = if (fail == 0) getString(R.string.subs_download_done, ok, rules)
                else if (ok == 0) getString(R.string.subs_download_failed, "$fail listas")
                else getString(R.string.subs_download_partial, ok, fail)
                toastMsg(msg)
            }
        }.apply { isDaemon = true; name = "via2-subs-all" }.start()
    }

    private fun showSubsIntervalDialog() {
        val options = arrayOf(getString(R.string.subs_interval_never), getString(R.string.subs_interval_days, 1), getString(R.string.subs_interval_days, 3), getString(R.string.subs_interval_days, 7), getString(R.string.subs_interval_days, 30))
        val values = intArrayOf(0, 1, 3, 7, 30)
        val current = values.indexOf(preferenceManager.getSubsIntervalDays()).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(R.string.subs_interval)
            .setSingleChoiceItems(options, current) { d, which -> preferenceManager.setSubsIntervalDays(values[which]); refreshSubscriptionsList(); d.dismiss() }
            .setNegativeButton(R.string.dialog_cancel, null).showThemed()
    }

    private fun toastMsg(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun runDnsTest() {
        val resolver = DnsManager.currentResolver
        if (resolver == null || !resolver.isEnabled) {
            AlertDialog.Builder(this).setTitle(R.string.setting_dns_test).setMessage(R.string.setting_dns_test_no_doh).setPositiveButton(R.string.dialog_ok, null).showThemed(); return
        }
        val started = System.currentTimeMillis()
        Thread(Runnable {
            val ips = try { resolver.resolveAllIpv4("example.com") } catch (e: Exception) { emptyList() }
            val ms = System.currentTimeMillis() - started
            runOnUiThread {
                val mensaje = if (ips.isNotEmpty()) getString(R.string.setting_dns_test_ok_format, "example.com", ips.first(), ms)
                else getString(R.string.setting_dns_test_fail_format, "example.com", ms, resolver.lastError ?: getString(R.string.setting_dns_test_no_response))
                AlertDialog.Builder(this).setTitle(R.string.setting_dns_test).setMessage(mensaje).setPositiveButton(R.string.dialog_ok, null).showThemed()
                updateDnsScreen(); updateAdvancedScreen()
            }
        }, "via2-dns-test").apply { isDaemon = true }.start()
        toast(R.string.setting_dns_test_running)
    }

    private fun showDnsCustomUrlDialog() {
        val current = preferenceManager.getDnsCustomUrl()
        val encodedCurrent = try { java.net.URLEncoder.encode(current, "UTF-8") } catch (e: Exception) { "" }
        openOrSwitchToTab(if (current.isNotBlank()) "$dnsCustomUrl?current=$encodedCurrent" else dnsCustomUrl)
    }

    private fun showDnsSelectionDialog() {
        val providers = DnsProvider.entries.toTypedArray()
        val titles = providers.map { it.title }.toTypedArray()
        val currentId = preferenceManager.getDnsProvider()
        val currentIndex = providers.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(R.string.dns_dialog_title)
            .setSingleChoiceItems(titles, currentIndex) { dialog, which ->
                val selected = providers[which]
                preferenceManager.setDnsProvider(selected.id)
                DnsManager.applyFromPreferences(this, preferenceManager)
                webView.applyDnsProxyIfNeeded()
                updateDnsScreen(); updateAdvancedScreen()
                Toast.makeText(this, getString(R.string.dns_applied, selected.title), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null).showThemed()
    }

    companion object {
        private const val TAG = "NaviaBrowser"
        private const val MAX_FAVICON_BYTES = 128 * 1024
        private const val MAX_FAVICON_DIMENSION = 512
        private const val GRID_COLS = 4
        private val TOP_IDS = arrayOf(R.id.qmPower, R.id.qmClose)
        private val GRID_IDS = arrayOf(R.id.qmNight, R.id.qmBookmarks, R.id.qmHistory, R.id.qmDownloads, R.id.qmIncognito, R.id.qmAddBookmark, R.id.qmDesktop, R.id.qmTools, R.id.qmSettings, R.id.qmFindInPage, R.id.qmTabs)

        private const val YOUTUBE_TV_SHADOW_JS = """
        (function(){
            var id='via2-yt-tv-clean';
            if(document.getElementById(id)) return;
            var s=document.createElement('style');
            s.id=id;
            s.textContent = [
                'ytlr-video-title, ytlr-video-title * { background: transparent !important; background-color: transparent !important; }',
                'ytlr-video-byline, ytlr-video-byline * { background: transparent !important; background-color: transparent !important; }',
                'ytlr-tile-metadata, ytlr-tile-metadata * { background: transparent !important; background-color: transparent !important; }',
                '.ytlr-video-title, .ytlr-video-byline { background: transparent !important; }',
                '[class*="video-title"], [class*="video-title"] *, [class*="video-byline"], [class*="video-byline"] * { background: transparent !important; background-color: transparent !important; }',
                '[class*="tile-metadata"], [class*="tile-metadata"] *, [class*="metadata-renderer"], [class*="metadata-renderer"] * { background: transparent !important; background-color: transparent !important; }',
                'ytlr-video-title, .ytlr-video-title, [class*="video-title"] { text-shadow: 0 2px 6px rgba(0,0,0,1), 0 1px 3px rgba(0,0,0,1) !important; color: #fff !important; }',
                'ytlr-video-byline, .ytlr-video-byline, [class*="video-byline"] { text-shadow: 0 1px 4px rgba(0,0,0,0.9) !important; color: rgba(255,255,255,0.85) !important; }',
                'body::before { content: ""; position: fixed; top: 0; left: 0; right: 0; height: 160px; background: linear-gradient(to bottom, rgba(0,0,0,0.45) 0%, rgba(0,0,0,0) 100%); pointer-events: none; z-index: 2147483000; }'
            ].join(' ');
            (document.head||document.documentElement).appendChild(s);
        })();
        """

        private const val NIGHT_MODE_JS = """
        (function(){
          var id='via2-night';
          if(document.getElementById(id)) return;
          function luminance(color){ var m=color.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/); if(!m) return null; return (0.2126*Number(m[1])+0.7152*Number(m[2])+0.0722*Number(m[3]))/255; }
          var bodyBg=luminance(getComputedStyle(document.body).backgroundColor);
          var pageBg=luminance(getComputedStyle(document.documentElement).backgroundColor);
          if((bodyBg!==null && bodyBg<0.45)||(pageBg!==null && pageBg<0.45)) return;
          var s=document.createElement('style');
          s.id=id;
          s.textContent='html{filter:invert(1) hue-rotate(180deg);background:#000}'+ 'img,video,picture,canvas,svg,iframe,[style*="background-image"]{filter:invert(1) hue-rotate(180deg)}';
          (document.head||document.documentElement).appendChild(s);
        })();
        """

        private const val NIGHT_MODE_OFF_JS = """
        (function(){
          var s=document.getElementById('via2-night');
          if(s) s.remove();
        })();
        """
    }
}
