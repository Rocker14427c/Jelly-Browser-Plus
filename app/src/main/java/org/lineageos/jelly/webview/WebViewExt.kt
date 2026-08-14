/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jelly.webview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.constraintlayout.widget.ConstraintLayout
import org.lineageos.jelly.R
import org.lineageos.jelly.js.JsManifest
import org.lineageos.jelly.js.JsMediaSession
import org.lineageos.jelly.js.JsShare
import org.lineageos.jelly.js.JsSyncUrl
import org.lineageos.jelly.shortcut.BackgroundShortcut
import org.lineageos.jelly.shortcut.BackgroundShortcutService
import org.lineageos.jelly.ui.UrlBarLayout
import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.utils.UrlUtils

class WebViewExt @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
    backgroundShortcut: BackgroundShortcut? = null,
    backgroundShortcutService: BackgroundShortcutService? = null
) : WebView(context, attrs, defStyle) {

    private lateinit var activity: WebViewExtActivity
    val requestHeaders = mutableMapOf<String?, String?>()
    private var desktopUserAgent: String? = null
    private var mobileUserAgent: String? = null
    var isIncognito = false
        private set
    private var desktopMode = false
    private var darkMode = false
    var lastLoadedUrl: String? = null
        private set
    var backgroundShortcut = backgroundShortcut
        private set
    var backgroundShortcutService = backgroundShortcutService
        private set
    var initialized: Boolean = false
        private set

    private val sharedPreferencesExt by lazy { SharedPreferencesExt(context) }

    // Unique tab id for tab switcher
    var tabId: Long = System.currentTimeMillis()
    var tabTitle: String? = null
        private set
    var tabFavicon: Bitmap? = null
        private set

    override fun loadUrl(url: String) {
        lastLoadedUrl = url
        followUrl(url)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        val backgroundMode = backgroundShortcutService != null
        super.onWindowVisibilityChanged(
            if (backgroundMode) View.VISIBLE else visibility
        )
    }

    fun followUrl(url: String) {
        UrlUtils.smartUrlFilter(url)?.let {
            super.loadUrl(it, this.requestHeaders)
            return
        }
        val templateUri = sharedPreferencesExt.searchEngine
        super.loadUrl(UrlUtils.getFormattedUri(templateUri, url), this.requestHeaders)
    }

    private fun setup(urlBarLayout: UrlBarLayout) {
        settings.javaScriptEnabled = sharedPreferencesExt.javascriptEnabled
        settings.javaScriptCanOpenWindowsAutomatically = sharedPreferencesExt.javascriptEnabled
        settings.setGeolocationEnabled(sharedPreferencesExt.locationEnabled)
        settings.setSupportMultipleWindows(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.domStorageEnabled = !isIncognito
        settings.databaseEnabled = !isIncognito
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(true)
        settings.useWideViewPort = true

        setOnLongClickListener(object : OnLongClickListener {
            override fun onLongClick(v: View): Boolean {
                val result = hitTestResult
                result.extra?.let {
                    when (result.type) {
                        HitTestResult.IMAGE_TYPE, HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                            activity.showSheetMenu(it, true)
                            return true
                        }
                        HitTestResult.SRC_ANCHOR_TYPE -> {
                            activity.showSheetMenu(it, false)
                            return true
                        }
                        else -> return false
                    }
                }
                return false
            }
        })
        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            activity.downloadFileAsk(url, userAgent, contentDisposition, mimeType, contentLength)
        }

        // Build clean mobile UA and modern desktop UA
        val systemUa = settings.userAgentString ?: ""
        mobileUserAgent = systemUa.replace("; wv", "").replace(" Version/4.0 ", " ")
        // Use a modern Chrome desktop UA that matches current WebView engine version when possible
        desktopUserAgent = DESKTOP_UA
        settings.userAgentString = mobileUserAgent

        if (sharedPreferencesExt.doNotTrackEnabled) {
            this.requestHeaders[HEADER_DNT] = "1"
        }

        if (settings.javaScriptEnabled) {
            addJavascriptInterface(JsSyncUrl(urlBarLayout, activity), JsSyncUrl.INTERFACE)
            addJavascriptInterface(JsManifest(activity), JsManifest.INTERFACE)
            addJavascriptInterface(JsMediaSession(this), JsMediaSession.INTERFACE)
            addJavascriptInterface(JsShare(activity), JsShare.INTERFACE)
        }
    }

    fun init(
        activity: WebViewExtActivity, urlBarLayout: UrlBarLayout, incognito: Boolean
    ) {
        if (initialized) return
        this.activity = activity
        isIncognito = incognito
        val chromeClient = ChromeClient(activity, incognito, urlBarLayout, sharedPreferencesExt)
        webChromeClient = chromeClient
        webViewClient = WebClient(activity, urlBarLayout, this)
        setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            urlBarLayout.searchPositionInfo = Pair(activeMatchOrdinal, numberOfMatches)
        }
        urlBarLayout.onLoadUrlCallback = { loadUrl(it) }
        urlBarLayout.onStartSearchCallback = { findAllAsync(it) }
        urlBarLayout.onClearSearchCallback = { clearMatches() }
        urlBarLayout.onSearchPositionChangeCallback = { findNext(it) }
        setup(urlBarLayout)
        initialized = true
    }

    val snap: Bitmap
        get() {
            measure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            layout(0, 0, measuredWidth, measuredHeight)
            val size = if (measuredWidth > measuredHeight) measuredHeight else measuredWidth
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val height = bitmap.height
            canvas.drawBitmap(bitmap, 0f, height.toFloat(), Paint())
            draw(canvas)
            return bitmap
        }

    var isDesktopMode: Boolean
        get() = desktopMode
        set(desktopMode) {
            this.desktopMode = desktopMode
            val s = settings
            s.userAgentString = if (desktopMode) desktopUserAgent else mobileUserAgent
            s.useWideViewPort = true
            s.loadWithOverviewMode = desktopMode
            setInitialScale(0)
            injectViewportAndDark()
            reload()
        }

    fun setDarkMode(enabled: Boolean) {
        this.darkMode = enabled
        if (initialized) injectViewportAndDark()
    }

    fun onPageLoadedInject() {
        injectViewportAndDark()
    }

    private fun injectViewportAndDark() {
        if (!initialized) return
        val viewportJs = if (desktopMode) {
            """
            (function(){
                var m = document.querySelector('meta[name=viewport]');
                if (!m) { m = document.createElement('meta'); m.name='viewport'; document.head.appendChild(m); }
                m.content = 'width=1280, initial-scale=1';
            })();
            """
        } else {
            """
            (function(){
                var m = document.querySelector('meta[name=viewport]');
                if (m) { m.content = 'width=device-width, initial-scale=1'; }
            })();
            """
        }
        evaluateJavascript(viewportJs, null)
        if (darkMode) {
            evaluateJavascript(DARK_MODE_JS, null)
        } else {
            evaluateJavascript(
                """(function(){
                    var s = document.getElementById('__jelly_dark_css');
                    if (s) s.remove();
                    document.documentElement.style.filter='';
                    document.documentElement.style.background='';
                })();""", null
            )
        }
    }

    fun updateTabInfo(title: String?, favicon: Bitmap?) {
        title?.let { tabTitle = it }
        favicon?.let { tabFavicon = it }
    }

    companion object {
        private const val TAG = "WebViewExt"
        private const val HEADER_DNT = "DNT"
        // Modern Chrome Linux desktop user agent
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private const val DARK_MODE_JS = """
(function(){
  var s = document.getElementById('__jelly_dark_css');
  if (!s) {
    s = document.createElement('style');
    s.id = '__jelly_dark_css';
    s.textContent = `
      html {
        filter: invert(0.90) hue-rotate(180deg) saturate(1.2) brightness(0.95) !important;
      }
      img, video, picture, canvas, svg, iframe[src*="youtube"], iframe[src*="vimeo"], [style*="background-image"] {
        filter: invert(1.11) hue-rotate(-180deg) saturate(0.83) !important;
      }
      html { background: #1a1a1a !important; }
      * { -webkit-font-smoothing: antialiased !important; text-shadow: none !important; }
    `;
    document.documentElement.appendChild(s);
  }
})();
"""

        fun newInstance(
            context: Context,
            backgroundShortcut: BackgroundShortcut? = null,
            backgroundShortcutService: BackgroundShortcutService? = null
        ) = WebViewExt(
            context,
            backgroundShortcut = backgroundShortcut,
            backgroundShortcutService = backgroundShortcutService
        ).apply {
            id = R.id.webView
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = R.id.appBarLayout
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
    }
}
