/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly

import android.app.Activity
import android.app.ActivityManager.TaskDescription
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient.CustomViewCallback
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.jelly.ext.buildShareIntent
import org.lineageos.jelly.favorite.FavoriteActivity
import org.lineageos.jelly.history.HistoryActivity
import org.lineageos.jelly.models.PwaManifest
import org.lineageos.jelly.models.WebShare
import org.lineageos.jelly.shortcut.BackgroundShortcut
import org.lineageos.jelly.shortcut.BackgroundShortcutActivity
import org.lineageos.jelly.ui.MenuDialog
import org.lineageos.jelly.ui.TabSwitcherActivity
import org.lineageos.jelly.ui.UrlBarLayout
import org.lineageos.jelly.utils.IntentUtils
import org.lineageos.jelly.utils.PermissionsUtils
import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.utils.TabUtils
import org.lineageos.jelly.utils.UiUtils
import org.lineageos.jelly.utils.UrlUtils
import org.lineageos.jelly.viewmodels.FavoriteViewModel
import org.lineageos.jelly.viewmodels.HistoryViewModel
import org.lineageos.jelly.viewmodels.SuggestionProviderViewModel
import org.lineageos.jelly.webview.WebViewExt
import org.lineageos.jelly.webview.WebViewExtActivity
import org.lineageos.jelly.shortcut.BackgroundShortcutService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.reflect.cast

class MainActivity : WebViewExtActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    // View model
    private val historyViewModel: HistoryViewModel by viewModels()
    private val favoritesViewModel: FavoriteViewModel by viewModels()
    private val suggestionProviderViewModel: SuggestionProviderViewModel by viewModels()

    // Views
    private val appBarLayout by lazy { findViewById<AppBarLayout>(R.id.appBarLayout) }
    private val constraintLayout by lazy { findViewById<ConstraintLayout>(R.id.constraintLayout) }
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val urlBarLayout by lazy { findViewById<UrlBarLayout>(R.id.urlBarLayout) }

    /**
     * The active tab's WebView. If no tab exists (e.g. the user just closed
     * the last one from the tab switcher), self-heal by creating one — this
     * getter is called from WebView callbacks and lifecycle methods where a
     * null active tab used to crash the app with a KotlinNullPointerException.
     */
    private val webView: WebViewExt
        get() {
            val tab = TabUtils.activeTab
            if (tab != null && !tab.webView.destroyed) return tab.webView
            if (tab != null && tab.webView.destroyed) {
                TabUtils.closeTab(this, tab.id)
            }
            return TabUtils.createTab(this, null, false).webView
        }
    private var currentTabId: Long = -1L
    private var shortcutId: String? = null
    private var shortcutName: String? = null
    private var backgroundShortcutId: String? = null
    private var backgroundShortcutServiceConnected: Boolean = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            backgroundShortcutServiceConnected = true
            val binder = BackgroundShortcutService.ServiceBinder::class.cast(service)
            val id = backgroundShortcutId!!
            val name = shortcutName ?: id
            val backgroundShortcut = BackgroundShortcut(id, name, true)
            val bss = binder.getService()
            val wv = bss.getWebView(backgroundShortcut)
            // The service-owned WebView may have been attached to a previous
            // activity instance: detach it before re-adding, and rebind it to
            // this activity (clients/callbacks would otherwise keep calling
            // into a dead activity and crash on any interaction).
            runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
            val tab = TabUtils.activeTab
            if (tab == null) {
                // No tab at all — create one carrying the launcher URL.
                val newTab = TabUtils.createTab(this@MainActivity, url, false)
                if (!TabUtils.replaceTabWebView(newTab.id, wv)) {
                    wv.destroy()
                    return
                }
            } else if (tab.webView !== wv) {
                // onStart() re-binds the service on every resume; only swap
                // when the active tab doesn't already show this webview.
                if (!TabUtils.replaceTabWebView(tab.id, wv)) {
                    wv.destroy()
                    return
                }
            }
            showActiveTab()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            backgroundShortcutServiceConnected = false
        }
    }

    private var backgroundShortcutResultFinished: Boolean = true
    private val backgroundShortcutLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            backgroundShortcutResultFinished = true
            if (it.resultCode == RESULT_OK) finish()
            else prepareWebView()
        }

    private val shortcutManager by lazy { getSystemService(ShortcutManager::class.java) }

    private val fileRequest =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            fileRequestCallback.invoke(it)
        }
    private lateinit var fileRequestCallback: ((data: List<Uri>) -> Unit)

    private var pwaManifest: PwaManifest? = null
    private var webRequestPermissions: ((granted: Array<String>) -> Unit)? = null
    private var webRequestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val granted = ArrayList<String>()
            it.entries.forEach { permission ->
                if (permission.value) granted.add(permission.key)
            }
            webRequestPermissions?.invoke(granted.toTypedArray())
        }

    override fun launchFileRequest(mimeTypes: Array<String>?) {
        fileRequest.launch(mimeTypes ?: arrayOf("*/*"))
    }

    override fun setFileRequestCallback(cb: (data: List<Uri>) -> Unit) {
        fileRequestCallback = cb
    }

    private val urlResolvedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!intent.hasExtra(Intent.EXTRA_INTENT) ||
                !intent.hasExtra(Intent.EXTRA_RESULT_RECEIVER)
            ) {
                return
            }
            @Suppress("UnsafeIntentLaunch")
            val resolvedIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
            } else {
                @Suppress("Deprecation")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)!!
            }
            if (TextUtils.equals(packageName, resolvedIntent.getPackage())) {
                val url: String = intent.getStringExtra(IntentUtils.EXTRA_URL)!!
                webView.loadUrl(url)
            } else {
                startActivity(resolvedIntent)
            }
            val receiver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    Intent.EXTRA_RESULT_RECEIVER,
                    ResultReceiver::class.java
                )!!
            } else {
                @Suppress("Deprecation")
                intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER)!!
            }
            receiver.send(Activity.RESULT_CANCELED, Bundle())
        }
    }
    private var urlIcon: Bitmap? = null
    private var url: String? = null
    private var incognito = false
    private var isFullscreenPwa = false
    private var desktopMode = false
    private var darkMode = false
    private var customView: View? = null
    private var fullScreenCallback: CustomViewCallback? = null
    private lateinit var menuDialog: MenuDialog
    private var tabSwitcherLauncherOk = false
    private val tabSwitcherLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            when (data.action) {
                TabSwitcherActivity.ACTION_NEW_TAB -> {
                    val tab = TabUtils.createTab(this, null, false)
                    showActiveTab()
                    urlBarLayout.url = ""
                    webView.requestFocus()
                }
                TabSwitcherActivity.ACTION_SELECT_TAB -> {
                    val id = data.getLongExtra(TabSwitcherActivity.EXTRA_TAB_ID, -1L)
                    if (id >= 0) {
                        TabUtils.setActiveTab(id)
                        showActiveTab()
                    }
                }
            }
        }

    private val sharedPreferencesExt by lazy { SharedPreferencesExt(this) }

    private val permissionsUtils by lazy { PermissionsUtils(this) }

    private lateinit var locationDialogCallback: (() -> Unit)
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it.isNotEmpty()) {
            if (permissionsUtils.locationPermissionsGranted()) {
                // Precise or approximate location access granted.
                locationDialogCallback()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        shortcutId = intent.getStringExtra(IntentUtils.EXTRA_SHORTCUT_ID)
        shortcutName = intent.getStringExtra(IntentUtils.EXTRA_SHORTCUT_NAME)
        url = when (intent.getBooleanExtra(IntentUtils.EXTRA_IGNORE_DATA, false)) {
            true -> intent.getStringExtra(IntentUtils.EXTRA_PAGE_URL)
            false -> intent.dataString
        }
        incognito = intent.getBooleanExtra(IntentUtils.EXTRA_INCOGNITO, false)
        isFullscreenPwa = intent.getStringExtra(MANIFEST_DISPLAY)?.let {
            ALLOWED_FULLSCREEN_PWA_VALUES.contains(it)
        } ?: false
        desktopMode = false

        // Restore from previous instance
        savedInstanceState?.let {
            url = url?.takeIf { url ->
                url.isNotEmpty()
            } ?: it.getString(IntentUtils.EXTRA_URL, null)
            incognito = it.getBoolean(IntentUtils.EXTRA_INCOGNITO, incognito)
            isFullscreenPwa = it.getBoolean(IntentUtils.EXTRA_FULLSCREEN_PWA, isFullscreenPwa)
            desktopMode = it.getBoolean(IntentUtils.EXTRA_DESKTOP_MODE, false)
        }

        // Make sure prefs are set before loading them
        PreferenceManager.setDefaultValues(this, R.xml.settings, false)
        val preferenceManager = PreferenceManager.getDefaultSharedPreferences(this)
        preferenceManager.registerOnSharedPreferenceChangeListener(this)

        // Register app shortcuts
        registerShortcuts()

        urlBarLayout.isIncognito = incognito

        menuDialog = MenuDialog(this) { option: MenuDialog.Option ->
            when (option) {
                MenuDialog.Option.BACK -> webView.goBack()
                MenuDialog.Option.FORWARD -> webView.goForward()
                MenuDialog.Option.NEW_TAB -> {
                    val tab = TabUtils.createTab(this, null, false)
                    showActiveTab()
                    urlBarLayout.url = ""
                }
                MenuDialog.Option.NEW_PRIVATE_TAB -> {
                    TabUtils.createTab(this, null, true)
                    showActiveTab()
                    urlBarLayout.url = ""
                }
                MenuDialog.Option.REFRESH -> webView.reload()
                MenuDialog.Option.ADD_TO_FAVORITE ->
                    webView.title?.let { title ->
                        webView.url?.let { url ->
                            setAsFavorite(title, url)
                        }
                    }

                MenuDialog.Option.SHARE -> {
                    // Delay a bit to allow popup menu hide animation to play
                    Handler(Looper.getMainLooper()).postDelayed({
                        webView.url?.let { url -> shareUrl(url) }
                    }, 300)
                }

                MenuDialog.Option.FIND_IN_PAGE -> {
                    // Run the search setup
                    showSearch()
                }

                MenuDialog.Option.FAVORITES -> startActivity(
                    Intent(
                        this,
                        FavoriteActivity::class.java
                    )
                )

                MenuDialog.Option.HISTORY -> startActivity(
                    Intent(
                        this,
                        HistoryActivity::class.java
                    )
                )

                MenuDialog.Option.DOWNLOADS -> startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                MenuDialog.Option.ADD_TO_HOME_SCREEN -> addShortcut()
                MenuDialog.Option.PRINT -> {
                    val printManager = getSystemService(PrintManager::class.java)
                    val documentName = "Jelly document"
                    val printAdapter = webView.createPrintDocumentAdapter(documentName)
                    printManager.print(
                        documentName, printAdapter,
                        PrintAttributes.Builder().build()
                    )
                }

                MenuDialog.Option.DESKTOP_VIEW -> {
                    desktopMode = !desktopMode
                    webView.isDesktopMode = desktopMode
                    menuDialog.isDesktopMode = desktopMode
                }

                MenuDialog.Option.DARK_MODE -> {
                    darkMode = !darkMode
                    webView.setDarkMode(darkMode)
                    menuDialog.isDarkMode = darkMode
                }

                MenuDialog.Option.BACKGROUND_SHORTCUTS -> backgroundShortcutLauncher.launch(
                    Intent(
                        this,
                        BackgroundShortcutActivity::class.java
                    ).apply {
                        backgroundShortcutResultFinished = false
                        putExtra(IntentUtils.EXTRA_SHORTCUT_ID, shortcutId)
                    }
                )

                MenuDialog.Option.SETTINGS -> startActivity(
                    Intent(
                        this,
                        SettingsActivity::class.java
                    )
                )
            }
            menuDialog.dismiss()
        }
        urlBarLayout.onMoreButtonClickCallback = {
            UiUtils.hideKeyboard(window, urlBarLayout)
            menuDialog.isDarkMode = darkMode
            menuDialog.isDesktopMode = desktopMode
            menuDialog.showAsDropdownMenu(urlBarLayout, sharedPreferencesExt.reachModeEnabled)
        }
        urlBarLayout.onTabsButtonClickCallback = { showTabSwitcher() }
        urlBarLayout.tabCount = TabUtils.tabCount

        CookieManager.getInstance()
            .setAcceptCookie(!incognito && sharedPreferencesExt.cookiesEnabled)

        try {
            val httpCacheDir = File(cacheDir, "suggestion_responses")
            val httpCacheSize = 1024 * 1024.toLong() // 1 MiB
            HttpResponseCache.install(httpCacheDir, httpCacheSize)
        } catch (e: IOException) {
            Log.i(TAG, "HTTP response cache installation failed:$e")
        }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    urlBarLayout.currentMode == UrlBarLayout.UrlBarMode.SEARCH -> {
                        urlBarLayout.currentMode = UrlBarLayout.UrlBarMode.URL
                    }

                    customView != null -> {
                        onHideCustomView()
                    }

                    webView.canGoBack() -> {
                        webView.goBack()
                    }

                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                suggestionProviderViewModel.suggestionProvider().collectLatest {
                    urlBarLayout.setSuggestionsProvider(it)
                }
            }
        }

        appBarLayout.isVisible = !isFullscreenPwa
        intent.extras?.let {
            it.getString(MANIFEST_THEME_COLOR)?.let { themeColor ->
                setStatusBarColor(themeColor)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // With documentLaunchMode="never" and singleTask, links opened from
        // other apps arrive here — load them in the active tab.
        val newUrl = when (intent.getBooleanExtra(IntentUtils.EXTRA_IGNORE_DATA, false)) {
            true -> intent.getStringExtra(IntentUtils.EXTRA_PAGE_URL)
            false -> intent.dataString
        }
        if (!newUrl.isNullOrEmpty()) {
            url = newUrl
            runCatching { webView.loadUrl(newUrl) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                urlResolvedReceiver, IntentFilter(IntentUtils.EVENT_URL_RESOLVED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(urlResolvedReceiver, IntentFilter(IntentUtils.EVENT_URL_RESOLVED))
        }
        if (backgroundShortcutResultFinished) prepareWebView()
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        if (backgroundShortcutServiceConnected) {
            unbindService(serviceConnection)
            backgroundShortcutServiceConnected = false
        }
        unregisterReceiver(urlResolvedReceiver)
        HttpResponseCache.getInstalled().flush()
        super.onStop()
    }

    public override fun onPause() {
        runCatching { webView.takeUnless { it.backgroundShortcutService != null }?.onPause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        runCatching { webView.takeUnless { it.backgroundShortcutService != null }?.onResume() }
        val activeIncognito = TabUtils.activeTab?.incognito ?: incognito
        CookieManager.getInstance()
            .setAcceptCookie(!activeIncognito && sharedPreferencesExt.cookiesEnabled)
        if (sharedPreferencesExt.lookLockEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Preserve webView status
        outState.putString(IntentUtils.EXTRA_URL, webView.url)
        outState.putBoolean(IntentUtils.EXTRA_INCOGNITO, webView.isIncognito)
        outState.putBoolean(IntentUtils.EXTRA_DESKTOP_MODE, webView.isDesktopMode)

        // Pwa fullscreen status
        outState.putBoolean(IntentUtils.EXTRA_FULLSCREEN_PWA, isFullscreenPwa)
    }

    private fun prepareWebView() {
        // TabUtils.init called on creation
        TabUtils.addListener(tabListener)
        val backgroundShortcuts = sharedPreferencesExt.backgroundShortcuts
        val isBackgroundShortcut = backgroundShortcuts.contains(shortcutId)
        backgroundShortcutId = if (isBackgroundShortcut) shortcutId else null
        if (backgroundShortcutId == null) {
            if (TabUtils.tabCount == 0) {
                val tab = TabUtils.createTab(this, url.takeIf { !it.isNullOrEmpty() }
                    ?: if (sharedPreferencesExt.homePageAutoload) sharedPreferencesExt.homePage else null, incognito)
                currentTabId = tab.id
            }
            showActiveTab()
        } else {
            Intent(this, BackgroundShortcutService::class.java).let {
                it.action = BackgroundShortcutService.START_FOREGROUND_ACTION
                startForegroundService(it)
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    private fun showActiveTab() {
        val tab = TabUtils.activeTab ?: return
        if (tab.webView.destroyed) {
            // Guard: a destroyed WebView must never be shown. Replace it.
            val wv = WebViewExt.newInstance(this)
            TabUtils.replaceTabWebView(tab.id, wv)
        }
        // A tab created for a background shortcut needs its webview from the
        // service. Bind it now; onServiceConnected swaps the webview in.
        val isShortcutTab = tab.shortcutId != null
        val needsShortcutWebView = isShortcutTab && tab.webView.backgroundShortcutService == null
        if (needsShortcutWebView && !backgroundShortcutServiceConnected) {
            backgroundShortcutId = tab.shortcutId
            shortcutName = tab.shortcutId
            val intent = Intent(this, BackgroundShortcutService::class.java).apply {
                action = BackgroundShortcutService.START_FOREGROUND_ACTION
            }
            startForegroundService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        // Detach any currently-shown webview
        for (t in TabUtils.allTabs) {
            if (t.webView.parent != null) (t.webView.parent as ViewGroup).removeView(t.webView)
        }
        constraintLayout.addView(tab.webView)
        setUiMode()
        if (!tab.webView.initialized && !needsShortcutWebView) {
            tab.webView.init(this, urlBarLayout, tab.incognito)
            val prefs = sharedPreferencesExt
            tab.webView.setDarkMode(prefs.darkModeEnabled)
            // Only navigate if the tab has nothing meaningful to show yet.
            val currentUrl = tab.webView.url
            val blank = currentUrl.isNullOrEmpty() || currentUrl == "about:blank"
            val loadUrl = when {
                tab.url != null && blank -> tab.url
                tab.url == null && blank -> tab.webView.lastLoadedUrl
                ?: if (sharedPreferencesExt.homePageAutoload) sharedPreferencesExt.homePage else null
                else -> null
            }
            if (loadUrl != null) tab.webView.loadUrl(loadUrl)
        } else if (tab.webView.initialized && !needsShortcutWebView) {
            // Always rebind the URL bar to the visible WebView. Before this
            // fix the callbacks kept pointing at whichever tab had been
            // initialized last, so searching after switching/closing tabs
            // called loadUrl() on a hidden (possibly destroyed) WebView and
            // crashed the app.
            tab.webView.rebind(this, urlBarLayout)
            // Re-apply this tab's own dark-mode state.
            tab.webView.setDarkMode(tab.webView.darkModeEnabled)
        }
        urlBarLayout.isIncognito = tab.incognito
        urlBarLayout.url = tab.webView.url ?: tab.url
        currentTabId = tab.id
        desktopMode = tab.webView.isDesktopMode
        darkMode = tab.webView.darkModeEnabled
        urlIcon = tab.favicon ?: tab.webView.tabFavicon
        updateTaskDescription()
    }

    private val tabListener = object : TabUtils.TabListener {
        override fun onActiveTabChanged(id: Long) {
            if (isFinishing || isDestroyed) return
            runOnUiThread { if (!isFinishing && !isDestroyed) showActiveTab() }
        }
        override fun onTabsChanged() {
            if (isFinishing || isDestroyed) return
            runOnUiThread {
                if (!isFinishing && !isDestroyed) urlBarLayout.tabCount = TabUtils.tabCount
            }
        }
    }

    private fun registerShortcuts() {
        shortcutManager.dynamicShortcuts = listOf(
            ShortcutInfo.Builder(this, "new_incognito_tab_shortcut")
                .setShortLabel(getString(R.string.shortcut_new_incognito_tab))
                .setLongLabel(getString(R.string.shortcut_new_incognito_tab))
                .setIcon(Icon.createWithResource(this, R.drawable.shortcut_incognito))
                .setIntent(
                    Intent(this, MainActivity::class.java)
                        .putExtra("extra_incognito", true)
                        .setAction(Intent.ACTION_VIEW)
                )
                .build()
        )
    }

    private fun showSearch() {
        urlBarLayout.currentMode = UrlBarLayout.UrlBarMode.SEARCH
    }

    private fun showTabSwitcher() {
        // Launch Via-style full-screen tab switcher
        val intent = Intent(this, TabSwitcherActivity::class.java)
        tabSwitcherLauncher.launch(intent)
        overridePendingTransition(0, 0)
    }

    private fun shareUrl(url: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_TEXT, url)
        if (sharedPreferencesExt.advancedShareEnabled && url == webView.url) {
            val file = File(cacheDir, System.currentTimeMillis().toString() + ".png")
            try {
                FileOutputStream(file).use { out ->
                    val bm = webView.snap
                    bm.compress(Bitmap.CompressFormat.PNG, 70, out)
                    out.flush()
                    out.close()
                    intent.putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            this,
                            "${application.packageName}.fileprovider",
                            file
                        )
                    )
                    intent.type = "image/png"
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: IOException) {
                Log.e(TAG, "${e.message}", e)
            }
        } else {
            intent.type = "text/plain"
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }


    private fun setAsFavorite(title: String, url: String) {
        val color = urlIcon?.takeUnless { it.isRecycled }?.let { bitmap ->
            UiUtils.getColor(bitmap, false).takeUnless { it == Color.TRANSPARENT }
        } ?: ContextCompat.getColor(
            this, com.google.android.material.R.color.material_dynamic_primary50
        )
        favoritesViewModel.insert(title, url, color)
        Snackbar.make(
            constraintLayout, getString(R.string.favorite_added),
            Snackbar.LENGTH_LONG
        ).show()
    }

    override fun downloadFileAsk(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val fileName = UrlUtils.guessFileName(url, contentDisposition, mimeType)
        AlertDialog.Builder(this)
            .setTitle(R.string.download_title)
            .setMessage(getString(R.string.download_message, fileName))
            .setPositiveButton(
                getString(R.string.download_positive)
            ) { _: DialogInterface?, _: Int -> fetchFile(url, userAgent, fileName) }
            .setNegativeButton(
                getString(R.string.dismiss)
            ) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
            .show()
    }

    private fun fetchFile(url: String?, userAgent: String?, fileName: String) {
        val request = try {
            DownloadManager.Request(Uri.parse(url))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Cannot download non http or https scheme")
            return
        }

        // Let this downloaded file be scanned by MediaScanner - so that it can
        // show up in Gallery app, for example.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("Deprecation")
            request.allowScanningByMediaScanner()
        }
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        request.setMimeType(
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(url)
            )
        )
        userAgent?.let {
            request.addRequestHeader("User-Agent", it)
        }
        CookieManager.getInstance().getCookie(url)?.takeUnless { it.isEmpty() }?.let {
            request.addRequestHeader("Cookie", it)
        }
        getSystemService(DownloadManager::class.java).enqueue(request)
    }

    override fun showSheetMenu(url: String, shouldAllowDownload: Boolean) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_actions, LinearLayout(this))
        val tabLayout = view.findViewById<View>(R.id.sheetNewTabLayout)
        val shareLayout = view.findViewById<View>(R.id.sheetShareLayout)
        val favouriteLayout = view.findViewById<View>(R.id.sheetFavouriteLayout)
        val downloadLayout = view.findViewById<View>(R.id.sheetDownloadLayout)
        tabLayout.setOnClickListener {
            TabUtils.openInNewTab(this, url, TabUtils.activeTab?.incognito ?: incognito)
            showActiveTab()
            urlBarLayout.tabCount = TabUtils.tabCount
            sheet.dismiss()
        }
        shareLayout.setOnClickListener {
            shareUrl(url)
            sheet.dismiss()
        }
        favouriteLayout.setOnClickListener {
            setAsFavorite(url, url)
            sheet.dismiss()
        }
        if (shouldAllowDownload) {
            downloadLayout.setOnClickListener {
                downloadFileAsk(url, webView.settings.userAgentString, null, null, 0)
                sheet.dismiss()
            }
            downloadLayout.visibility = View.VISIBLE
        }
        sheet.setContentView(view)
        sheet.show()
    }

    /*
     * This is called only if GeolocationPermissions doesn't have an explicit entry for @origin
     */
    override fun showLocationDialog(origin: String, callback: GeolocationPermissions.Callback) {
        locationDialogCallback = {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_dialog_title)
                .setMessage(getString(R.string.location_dialog_message, origin))
                .setPositiveButton(R.string.location_dialog_allow) { _, _ ->
                    callback(origin, true, true)
                }
                .setNegativeButton(R.string.location_dialog_block) { _, _ ->
                    callback(origin, false, true)
                }
                .setNeutralButton(R.string.location_dialog_cancel) { _, _ ->
                    callback(origin, false, false)
                }
                .setOnCancelListener {
                    callback(origin, false, false)
                }
                .create()
                .show()
        }

        if (!permissionsUtils.locationPermissionsGranted()) {
            locationPermissionRequest.launch(PermissionsUtils.locationPermissions)
        } else {
            locationDialogCallback()
        }
    }

    override fun onFaviconLoaded(favicon: Bitmap?) {
        favicon?.let {
            if (it.isRecycled) {
                return
            }
            urlIcon = it.config?.let { config ->
                it.copy(config, true)
            }
            updateTaskDescription()
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }

    private fun updateTaskDescription() {
        setTaskDescription(
            @Suppress("Deprecation")
            TaskDescription(
                webView.title,
                urlIcon, Color.WHITE
            )
        )
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback) {
        customView?.let {
            callback.onCustomViewHidden()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        customView = view
        fullScreenCallback = callback
        setImmersiveMode(true)
        customView?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
        addContentView(
            customView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        appBarLayout.visibility = View.GONE
        webView.visibility = View.GONE
    }

    override fun onHideCustomView() {
        val customView = customView ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersiveMode(false)
        appBarLayout.isVisible = !isFullscreenPwa
        webView.visibility = View.VISIBLE
        val viewGroup = customView.parent as ViewGroup
        viewGroup.removeView(customView)
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
        this.customView = null
    }

    private fun addShortcut() {
        val shortcutInfo = buildShortcutInfo()
        shortcutManager.requestPinShortcut(shortcutInfo, null)
    }

    private fun buildShortcutInfo(): ShortcutInfo {
        val id = pwaManifest?.id ?: System.currentTimeMillis().toString()
        val shortName = pwaManifest?.shortName ?: webView.title.toString()
        val intent = Intent(this, MainActivity::class.java).apply {
            data = Uri.parse(pwaManifest?.startUrl ?: webView.url)
            action = Intent.ACTION_MAIN
            putExtra(IntentUtils.EXTRA_SHORTCUT_ID, id)
            putExtra(IntentUtils.EXTRA_SHORTCUT_NAME, shortName)
            pwaManifest?.let {
                putExtra(MANIFEST_DISPLAY, it.display)
                putExtra(MANIFEST_THEME_COLOR, it.themeColor)
            }
        }
        val launcherIcon = urlIcon?.let {
            Icon.createWithBitmap(UiUtils.getShortcutIcon(it, Color.WHITE))
        } ?: Icon.createWithResource(this, R.mipmap.ic_launcher)
        return ShortcutInfo.Builder(this, id).apply {
            setShortLabel(shortName)
            setIcon(launcherIcon)
            setIntent(intent)
            pwaManifest?.let {
                setLongLabel(it.name)
            }
        }.build()
    }

    override fun updateHistory(title: String, url: String) {
        historyViewModel.insertOrUpdate(title, url)
    }

    override fun replaceHistory(title: String, url: String, newUrl: String) {
        historyViewModel.replace(title, url, newUrl)
    }

    override fun setPwaManifest(manifest: PwaManifest?) {
        pwaManifest = manifest
        if (manifest == null) return
        // find and update pwa shortcut
        lifecycleScope.launch {
            val pinnedShortcuts = shortcutManager.pinnedShortcuts
            val index = pinnedShortcuts.indexOfFirst { it.id == manifest.id }
            if (index == -1) return@launch
            pinnedShortcuts[index] = buildShortcutInfo()
            shortcutManager.updateShortcuts(pinnedShortcuts)
        }
    }

    override fun webRequestPermissions(
        permissions: Array<String>,
        cb: ((granted: Array<String>) -> Unit)
    ) {
        webRequestPermissions = cb
        webRequestPermissionsLauncher.launch(permissions)
    }

    override fun webProtectedMedia(origin: String, cb: ((granted: Boolean) -> Unit)) {
        AlertDialog.Builder(this)
            .setTitle(origin)
            .setMessage(R.string.protected_media_dialog_message)
            .setPositiveButton(R.string.protected_media_dialog_grant) { _, _ ->
                cb(true)
            }.setNegativeButton(R.string.protected_media_dialog_deny) { _, _ ->
                cb(false)
            }.show()
    }

    override fun onWebShare(value: WebShare) {
        val intent = buildShareIntent(*value.files.toTypedArray())
        intent.putExtra(Intent.EXTRA_TITLE, value.title)

        buildString {
            value.text?.let { appendLine(it) }
            value.url?.let { appendLine(it) }
        }.trimEnd().takeUnless { it.isEmpty() }?.let {
            intent.putExtra(Intent.EXTRA_TEXT, it)
        }

        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun setImmersiveMode(enable: Boolean) {
        val decorView = window.decorView

        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("Deprecation")
                decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        )
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(WindowInsets.Type.systemBars())
            } else {
                @Suppress("Deprecation")
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(decorView) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = if (enable) systemBarsInsets.top else 0,
                bottom = if (enable) systemBarsInsets.bottom else 0,
                left = if (enable) systemBarsInsets.left else 0,
                right = if (enable) systemBarsInsets.right else 0
            )
            insets
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "key_reach_mode" -> setUiMode()
            "key_dark_mode" -> {
                darkMode = sharedPreferencesExt.darkModeEnabled
                // Apply to every open tab, not just the active one.
                TabUtils.allTabs.forEach {
                    runCatching { it.webView.setDarkMode(darkMode) }
                }
            }
            "key_adblock", "key_adblock_level" -> {
                runCatching { webView.reload() }
            }
        }
    }

    override fun onTabUpdated(tab: WebViewExt) {
        if (isFinishing || isDestroyed) return
        TabUtils.updateTabInfo(tab.tabId, title = tab.title, favicon = tab.favicon, url = tab.url)
        if (tab.tabId == currentTabId) {
            urlBarLayout.url = tab.url
            urlIcon = tab.tabFavicon
            updateTaskDescription()
        }
        urlBarLayout.tabCount = TabUtils.tabCount
    }

    override fun onDestroy() {
        super.onDestroy()
        TabUtils.removeListener(tabListener)
        // If the user really left the app (not a configuration change), tear
        // the tabs down. Previously the WebViews survived the activity and got
        // re-attached to the next instance, keeping references to a dead
        // activity and causing crashes on later navigation/search.
        if (isFinishing) {
            TabUtils.destroyAllTabs()
        }
    }

    private fun setUiMode() {
        // Now you don't see it
        constraintLayout.alpha = 0f
        // Magic happens
        changeUiMode(sharedPreferencesExt.reachModeEnabled)
        // Now you see it
        constraintLayout.alpha = 1f
    }

    private fun changeUiMode(isReachMode: Boolean) {
        appBarLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.UNSET
                false -> ConstraintLayout.LayoutParams.PARENT_ID
            }
            bottomToBottom = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.PARENT_ID
                false -> ConstraintLayout.LayoutParams.UNSET
            }
        }

        webView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            bottomToBottom = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.UNSET
                false -> ConstraintLayout.LayoutParams.PARENT_ID
            }
            bottomToTop = when (isReachMode) {
                true -> R.id.appBarLayout
                false -> ConstraintLayout.LayoutParams.UNSET
            }
            topToBottom = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.UNSET
                false -> R.id.appBarLayout
            }
            topToTop = when (isReachMode) {
                true -> ConstraintLayout.LayoutParams.PARENT_ID
                false -> ConstraintLayout.LayoutParams.UNSET
            }
        }
    }

    private fun setStatusBarColor(hex: String) {
        runCatching {
            val color = Color.parseColor(hex)
            if (UiUtils.isColorLight(color)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )
                } else {
                    @Suppress("Deprecation")
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }
            window.decorView.setBackgroundColor(color)
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val MANIFEST_DISPLAY = "manifest_display"
        private const val MANIFEST_THEME_COLOR = "manifest_theme_color"
        private val ALLOWED_FULLSCREEN_PWA_VALUES = arrayOf("fullscreen", "standalone")
    }
}
