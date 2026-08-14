/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jelly.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import org.lineageos.jelly.webview.WebViewExt
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app tab manager. Holds multiple WebViews in a single activity so tabs
 * don't spawn separate entries in Android Recents.
 */
object TabUtils {
    data class Tab(
        val id: Long,
        var webView: WebViewExt,
        var url: String? = null,
        var title: String? = null,
        var favicon: Bitmap? = null,
        var incognito: Boolean = false,
        var shortcutId: String? = null
    )

    interface TabListener {
        fun onTabsChanged() {}
        fun onActiveTabChanged(id: Long) {}
    }

    private val tabs = ArrayList<Tab>()
    private var activeTabId: Long = -1L
    private const val MAX_TABS = 20
    private val listeners = CopyOnWriteArrayList<TabListener>()
    private val handler = Handler(Looper.getMainLooper())

    val activeTab: Tab? get() = tabs.find { it.id == activeTabId }
    val allTabs: List<Tab> get() = ArrayList(tabs)
    val tabCount: Int get() = tabs.size

    fun addListener(l: TabListener) = listeners.addIfAbsent(l)
    fun removeListener(l: TabListener) = listeners.remove(l)

    private fun notifyChanged() {
        handler.post { listeners.forEach { it.onTabsChanged() } }
    }

    private fun notifyActiveChanged(id: Long) {
        handler.post { listeners.forEach { it.onActiveTabChanged(id); it.onTabsChanged() } }
    }

    fun createTab(
        context: Context,
        url: String? = null,
        incognito: Boolean = false,
        shortcutId: String? = null
    ): Tab {
        if (tabs.size >= MAX_TABS) {
            // close oldest non-active
            tabs.firstOrNull { it.id != activeTabId }?.let { closeTab(context, it.id) }
        }
        // Chrome-style dark mode: create the WebView with a dark theme context
        // when dark mode is on, so Chromium's algorithmic darkening and
        // prefers-color-scheme: dark engage natively.
        val dark = runCatching { SharedPreferencesExt(context).darkModeEnabled }
            .getOrDefault(false)
        val wv = WebViewExt.newInstance(context, useDarkContext = dark)
        val id = System.currentTimeMillis() + tabs.size
        wv.tabId = id
        val tab = Tab(id, wv, url = url, incognito = incognito, shortcutId = shortcutId)
        tabs.add(tab)
        activeTabId = id
        notifyActiveChanged(id)
        return tab
    }

    fun setActiveTab(id: Long): Tab? {
        val tab = tabs.find { it.id == id } ?: return null
        activeTabId = id
        notifyActiveChanged(id)
        return tab
    }

    /**
     * Replaces the WebView inside [id] with [wv]. The old WebView is detached
     * and destroyed safely. Used by the background-shortcut flow to swap in
     * the service-owned WebView without leaving a destroyed WebView behind in
     * the tab (which used to crash on any later interaction, e.g. searching).
     */
    fun replaceTabWebView(id: Long, wv: WebViewExt): Boolean {
        val tab = tabs.find { it.id == id } ?: return false
        val old = tab.webView
        runCatching {
            old.stopLoading()
            old.removeAllViews()
            (old.parent as? ViewGroup)?.removeView(old)
            old.destroy()
        }
        tab.webView = wv
        wv.tabId = id
        notifyChanged()
        return true
    }

    fun closeTab(context: Context, id: Long): Boolean {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val tab = tabs.removeAt(idx)
        // Detach before destroying; do NOT load about:blank first — that fires
        // WebView callbacks into the shared URL bar while we're tearing the tab
        // down, and it runs on a WebView that may already be half-dead.
        runCatching {
            tab.webView.stopLoading()
            tab.webView.removeAllViews()
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            tab.webView.destroy()
        }
        if (tabs.isEmpty()) {
            // No tabs left. Do NOT create a replacement here: the caller's
            // context may be a finishing activity (tab switcher). The
            // MainActivity auto-heals by creating a tab the next time the
            // active tab is requested, and the tab switcher returns
            // ACTION_NEW_TAB instead.
            activeTabId = -1L
            notifyActiveChanged(-1L)
            return true
        }
        if (activeTabId == id) {
            val newIdx = if (idx >= tabs.size) tabs.size - 1 else idx
            activeTabId = tabs[newIdx.coerceAtLeast(0)].id
            notifyActiveChanged(activeTabId)
        } else {
            notifyChanged()
        }
        return true
    }

    /** Destroys all tabs (used when the user really leaves the app). */
    fun destroyAllTabs() {
        tabs.forEach { tab ->
            runCatching {
                tab.webView.stopLoading()
                tab.webView.removeAllViews()
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                tab.webView.destroy()
            }
        }
        tabs.clear()
        activeTabId = -1L
    }

    fun updateTabInfo(id: Long, title: String? = null, favicon: Bitmap? = null, url: String? = null) {
        val tab = tabs.find { it.id == id } ?: return
        title?.let { tab.title = it }
        // The framework favicon can arrive already-recycled; storing it would
        // crash later users of it (tab switcher cards, TaskDescription).
        favicon?.takeUnless { it.isRecycled }?.let { tab.favicon = it }
        url?.let { tab.url = it }
        notifyChanged()
    }

    fun openInNewTab(
        context: Context,
        url: String? = null,
        incognito: Boolean = false,
        shortcutId: String? = null
    ) {
        createTab(context, url, incognito, shortcutId)
    }
}
