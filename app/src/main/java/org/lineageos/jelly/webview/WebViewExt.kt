/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jelly.webview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.WebView
import androidx.constraintlayout.widget.ConstraintLayout
import org.lineageos.jelly.R
import org.lineageos.jelly.js.JsManifest
import org.lineageos.jelly.js.JsMediaSession
import org.lineageos.jelly.js.JsShare
import org.lineageos.jelly.js.JsSyncUrl
import org.lineageos.jelly.js.JsElementPicker
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
    backgroundShortcutService: BackgroundShortcutService? = null,
    useDarkContext: Boolean = false
) : WebView(wrapContext(context, useDarkContext), attrs, defStyle) {

    /**
     * True when this WebView was created with a dark theme context. Such
     * WebViews report prefers-color-scheme: dark to pages and have
     * algorithmic darkening enabled, so Chromium itself renders pages in
     * Chrome-style dark mode; the JS fallback stays off for them.
     */
    val isDarkContext: Boolean = useDarkContext

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

    /** True once destroy() has been called; all further operations become no-ops. */
    var destroyed: Boolean = false
        private set

    private val sharedPreferencesExt by lazy { SharedPreferencesExt(context) }

    // Unique tab id for tab switcher
    var tabId: Long = System.currentTimeMillis()
    var tabTitle: String? = null
        private set
    var tabFavicon: Bitmap? = null
        private set

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        runCatching { stopLoading() }
        runCatching { removeAllViews() }
        runCatching { (parent as? android.view.ViewGroup)?.removeView(this) }
        runCatching { super.destroy() }
    }

    override fun loadUrl(url: String) {
        if (destroyed) return
        lastLoadedUrl = url
        runCatching { activity.onPageLoadStarted(this) }
        followUrl(url)
    }

    override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        if (destroyed) return
        lastLoadedUrl = url
        runCatching { activity.onPageLoadStarted(this) }
        followUrl(url)
    }

    override fun reload() {
        if (destroyed) return
        super.reload()
    }

    override fun goBack() {
        if (destroyed) return
        super.goBack()
    }

    override fun goForward() {
        if (destroyed) return
        super.goForward()
    }

    override fun onPause() {
        if (destroyed) return
        super.onPause()
    }

    override fun onResume() {
        if (destroyed) return
        super.onResume()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        val backgroundMode = backgroundShortcutService != null
        super.onWindowVisibilityChanged(
            if (backgroundMode) View.VISIBLE else visibility
        )
    }

    fun followUrl(url: String) {
        if (destroyed) return
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

        // Chrome-style dark mode: on dark-context WebViews, let Chromium's
        // algorithmic darkening (the same algorithm Chrome uses) darken pages
        // that don't define their own dark styles. Pages with native dark
        // themes render them via prefers-color-scheme. setForceDark is a
        // no-op for this app (targetSdk 36), so this is the only native path.
        if (isDarkContext && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { settings.isAlgorithmicDarkeningAllowed = true }
        }

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
            addJavascriptInterface(JsElementPicker(activity), JsElementPicker.INTERFACE)
        }
    }

    fun init(
        activity: WebViewExtActivity, urlBarLayout: UrlBarLayout, incognito: Boolean
    ) {
        if (destroyed) return
        if (initialized) {
            // Already initialized (e.g. a background-shortcut WebView re-attached
            // to a new activity instance): rebind everything that captured the
            // old activity instead of returning early and leaving stale refs.
            rebind(activity, urlBarLayout)
            return
        }
        this.activity = activity
        isIncognito = incognito
        setupClients(urlBarLayout)
        bindUrlBar(urlBarLayout)
        setup(urlBarLayout)
        initialized = true
    }

    /**
     * Rebinds this WebView to a possibly-new activity and URL bar. Call this
     * every time a tab becomes the active tab so URL-bar callbacks always
     * point at the visible WebView (searching used to invoke loadUrl() on the
     * last-initialized tab — which crashed once that tab had been destroyed).
     */
    fun rebind(activity: WebViewExtActivity, urlBarLayout: UrlBarLayout) {
        if (destroyed) return
        this.activity = activity
        setupClients(urlBarLayout)
        bindUrlBar(urlBarLayout)
    }

    private fun setupClients(urlBarLayout: UrlBarLayout) {
        val chromeClient = ChromeClient(activity, isIncognito, urlBarLayout, sharedPreferencesExt)
        webChromeClient = chromeClient
        webViewClient = WebClient(activity, urlBarLayout, this)
        setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            urlBarLayout.searchPositionInfo = Pair(activeMatchOrdinal, numberOfMatches)
        }
    }

    private fun bindUrlBar(urlBarLayout: UrlBarLayout) {
        urlBarLayout.onLoadUrlCallback = { loadUrl(it) }
        urlBarLayout.onStartSearchCallback = { findAllAsync(it) }
        urlBarLayout.onClearSearchCallback = { clearMatches() }
        urlBarLayout.onSearchPositionChangeCallback = { findNext(it) }
    }

    val snap: Bitmap
        get() {
            if (destroyed) {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
            measure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            layout(0, 0, measuredWidth, measuredHeight)
            val size = if (measuredWidth > measuredHeight) measuredHeight else measuredWidth
            if (size <= 0) {
                // Never laid out (detached/zero-size): Bitmap.createBitmap(0,0)
                // throws — hand back a harmless 1x1 instead.
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
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
            if (destroyed) return
            val s = settings
            s.userAgentString = if (desktopMode) desktopUserAgent else mobileUserAgent
            s.useWideViewPort = true
            s.loadWithOverviewMode = desktopMode
            setInitialScale(0)
            injectViewportAndDark()
            reload()
        }

    /** Whether dark mode is currently enabled on this particular WebView. */
    val darkModeEnabled: Boolean
        get() = darkMode

    fun setDarkMode(enabled: Boolean) {
        this.darkMode = enabled
        // Match the (softer) dark page background while loading, so pages
        // don't flash white in dark mode.
        setBackgroundColor(Color.parseColor(if (enabled) DARK_PAGE_BG else "#FFFFFF"))
        if (initialized) injectViewportAndDark()
    }

    fun onPageLoadedInject() {
        injectViewportAndDark()
    }

    private fun injectViewportAndDark() {
        if (!initialized || destroyed) return
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
            // Dark-context WebViews are darkened natively by Chromium
            // (algorithmic darkening / prefers-color-scheme) — injecting JS on
            // top would double-darken. The JS fallback only runs on normal
            // (light-context) WebViews.
            if (!isDarkContext) evaluateJavascript(DARK_MODE_JS, null)
        } else {
            evaluateJavascript(DARK_MODE_OFF_JS, null)
        }
    }

    /** Enters element-picker mode: tap any element to report it via
     *  JsElementPicker (Brave-style element block / Via-style mark as ad). */
    fun startElementPicker() {
        if (destroyed || !initialized) return
        evaluateJavascript(JsElementPicker.SCRIPT, null)
    }

    fun updateTabInfo(title: String?, favicon: Bitmap?) {        title?.let { tabTitle = it }
        // Store a private copy: the incoming bitmap is owned by the caller
        // (WebView/onFaviconLoaded) and may be recycled right after this call.
        // Keeping a borrowed reference caused a "Cannot write recycled bitmap"
        // crash in TaskDescription on every page load with a favicon.
        favicon?.takeUnless { it.isRecycled }?.let {
            runCatching {
                tabFavicon = it.copy(it.config ?: Bitmap.Config.ARGB_8888, true)
            }
        }
    }

    companion object {
        private const val TAG = "WebViewExt"
        private const val HEADER_DNT = "DNT"

        private fun wrapContext(context: Context, dark: Boolean): Context =
            if (dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextThemeWrapper(context, R.style.Theme_Jelly_WebViewDark)
            } else {
                context
            }

        /** Page background used while dark mode is active (Chrome's dark
         *  page color; matches the native darkening so nothing flashes). */
        private const val DARK_PAGE_BG = "#202124"

        // Modern Chrome Linux desktop user agent
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        /**
         * Chrome-style dark mode fallback (used only where native algorithmic
         * darkening is unavailable, i.e. light-context WebViews on older
         * devices). Instead of the old aggressive filter:invert(1)
         * hue-rotate(180deg) approach this:
         *  - uses Chrome's dark palette (#202124 background, #e8eaed text,
         *    #8ab4f8 links)
         *  - inverts only the LIGHTNESS of element colors while keeping hue
         *    and saturation, the same idea as Chromium's auto-darkening, so
         *    colors stay natural instead of turning into inverted negatives
         *  - never touches images, video, canvas, embeds or background images
         *  - leaves pages that are already dark natively completely alone
         *  - applies color-scheme:dark so form controls/scrollbars render dark
         *  - follows dynamically added content via a MutationObserver
         */
        private const val DARK_MODE_JS = """
(function(){
  try {
    var doc = document;
    var root = doc.documentElement;
    if (!root) return;
    var APPLIED = 'data-jelly-darkened';
    var counted = 0;
    var MAX_NODES = 8000;

    function parseRgb(s) {
      var m = s && s.match(/[\d.]+/g);
      if (!m || m.length < 3) return null;
      return [+m[0], +m[1], +m[2], m.length > 3 ? parseFloat(m[3]) : 1];
    }
    function lum(c) { return 0.2126*c[0] + 0.7152*c[1] + 0.0722*c[2]; }

    /* Invert lightness, preserve hue & (mostly) saturation. */
    function remap(c, floor, ceil) {
      var r = c[0]/255, g = c[1]/255, b = c[2]/255;
      var max = Math.max(r,g,b), min = Math.min(r,g,b);
      var l = (max+min)/2, d = max-min;
      var h = 0, s = 0;
      if (d > 0.0001) {
        s = l > 0.5 ? d/(2-max-min) : d/(max+min);
        if (max === r) h = ((g-b)/d)%6;
        else if (max === g) h = (b-r)/d + 2;
        else h = (r-g)/d + 4;
        h *= 60; if (h < 0) h += 360;
      }
      var nl = Math.max(floor, Math.min(ceil, 1 - l));
      s = Math.min(1, s * 0.9);
      var q = nl < 0.5 ? nl*(1+s) : nl + s - nl*s;
      var p = 2*nl - q;
      function t2c(t) {
        if (t < 0) t += 1; if (t > 1) t -= 1;
        if (t < 1/6) return p + (q-p)*6*t;
        if (t < 1/2) return q;
        if (t < 2/3) return p + (q-p)*(2/3-t)*6;
        return p;
      }
      var hh = h/360;
      return 'rgb(' + Math.round(t2c(hh+1/3)*255) + ',' +
                      Math.round(t2c(hh)*255) + ',' +
                      Math.round(t2c(hh-1/3)*255) + ')';
    }

    /* Never restyle media or form controls element-wise. */
    var SKIP = {IMG:1, PICTURE:1, VIDEO:1, AUDIO:1, CANVAS:1, SVG:1,
                IFRAME:1, FRAME:1, EMBED:1, OBJECT:1, SCRIPT:1, STYLE:1,
                LINK:1, META:1, NOSCRIPT:1, TEMPLATE:1, AREA:1, MAP:1,
                INPUT:1, SELECT:1, TEXTAREA:1, BUTTON:1, OPTION:1};

    function process(el) {
      if (counted >= MAX_NODES || el.hasAttribute(APPLIED)) return;
      if (SKIP[el.tagName]) return;
      var cs = getComputedStyle(el);
      if (!cs || cs.display === 'none' || cs.visibility === 'hidden') return;
      var bg = parseRgb(cs.backgroundColor);
      var bgImg = cs.backgroundImage;
      var hasImg = bgImg && bgImg !== 'none';
      var changed = false;
      if (bg && bg[3] > 0.5 && !hasImg && lum(bg) > 190) {
        el.__jdBg = el.style.backgroundColor || null;
        el.style.setProperty('background-color', remap(bg, 0.05, 0.92), 'important');
        changed = true;
      }
      var fg = parseRgb(cs.color);
      if (fg && fg[3] > 0.5 && lum(fg) < 150) {
        el.__jdFg = el.style.color || null;
        el.style.setProperty('color', remap(fg, 0.75, 0.96), 'important');
        changed = true;
      }
      if (changed) { el.setAttribute(APPLIED, '1'); counted++; }
    }

    function walk(node) {
      if (!node || counted >= MAX_NODES) return;
      if (node.nodeType === 1) process(node);
      var kids = node.children;
      for (var i = 0; i < kids.length; i++) { walk(kids[i]); if (counted >= MAX_NODES) return; }
    }

    /* Chrome dark-mode palette */
    var CSS =
      'html { background-color:#202124 !important; color-scheme: dark !important; }' +
      'body { background-color:#202124 !important; color:#e8eaed !important; }' +
      'a { color:#8ab4f8 !important; } a:visited { color:#c58af9 !important; }' +
      'input, textarea, select, button { background-color:#303134 !important; color:#e8eaed !important; border-color:#5f6368 !important; }' +
      'input::placeholder, textarea::placeholder { color:#9aa0a6 !important; }' +
      'table, th, td { border-color:#3c4043; }' +
      'mark { background-color:#41331c !important; color:#e8eaed !important; }' +
      'img, video, canvas, svg, embed, object, iframe { filter:none !important; }';

    function injectCss() {
      var s = doc.getElementById('__jelly_dark_css');
      if (!s) {
        s = doc.createElement('style');
        s.id = '__jelly_dark_css';
        (doc.head || root).appendChild(s);
      }
      s.textContent = CSS;
    }

    function isNativeDark() {
      var probe = doc.body || root;
      var bg = parseRgb(getComputedStyle(probe).backgroundColor);
      return !!(bg && bg[3] > 0.5 && lum(bg) < 128);
    }

    function apply() {
      if (isNativeDark()) {
        root.setAttribute('data-jelly-native-dark', '1');
        return;
      }
      injectCss();
      walk(doc.body || root);
      if (!window.__jdObserver) {
        window.__jdObserver = new MutationObserver(function(muts){
          var any = false;
          for (var i = 0; i < muts.length; i++) if (muts[i].addedNodes.length) { any = true; break; }
          if (!any || window.__jdRaf) return;
          window.__jdRaf = window.requestAnimationFrame(function(){
            window.__jdRaf = null;
            if (root.hasAttribute('data-jelly-native-dark')) apply();
            else walk(doc.body || root);
          });
        });
        window.__jdObserver.observe(doc.body || root, {childList:true, subtree:true});
      }
    }
    apply();
  } catch(e) {}
})();
"""

        private const val DARK_MODE_OFF_JS = """
(function(){
  try {
    var doc = document, root = doc.documentElement;
    var s = doc.getElementById('__jelly_dark_css');
    if (s && s.parentNode) s.parentNode.removeChild(s);
    if (window.__jdObserver) { window.__jdObserver.disconnect(); window.__jdObserver = null; }
    window.__jdRaf = null;
    var els = doc.querySelectorAll('[data-jelly-darkened]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.__jdBg) el.style.setProperty('background-color', el.__jdBg, 'important');
      else el.style.removeProperty('background-color');
      if (el.__jdFg) el.style.setProperty('color', el.__jdFg, 'important');
      else el.style.removeProperty('color');
      el.removeAttribute('data-jelly-darkened');
      delete el.__jdBg; delete el.__jdFg;
    }
    root.removeAttribute('data-jelly-native-dark');
  } catch(e) {}
})();
"""

        fun newInstance(
            context: Context,
            backgroundShortcut: BackgroundShortcut? = null,
            backgroundShortcutService: BackgroundShortcutService? = null,
            useDarkContext: Boolean = false
        ) = WebViewExt(
            context,
            backgroundShortcut = backgroundShortcut,
            backgroundShortcutService = backgroundShortcutService,
            useDarkContext = useDarkContext
        ).apply {
            id = R.id.webView
            isFocusable = true
            isFocusableInTouchMode = true
            if (useDarkContext) {
                // Match the page background so loading doesn't flash white.
                setBackgroundColor(Color.parseColor(DARK_PAGE_BG))
            }
            layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = R.id.appBarLayout
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
    }
}
