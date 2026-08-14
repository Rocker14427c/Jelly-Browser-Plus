/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jelly.utils

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.lineageos.jelly.webview.WebViewExt
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app tab manager. Holds multiple WebViews in a single activity so tabs
 * don't spawn separate entries in Android Recents.
 */
object TabUtils {
    data class Tab(
        val id: Long,
        val webView: WebViewExt,
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

    fun createTab(context: Context, url: String? = null, incognito: Boolean = false, shortcutId: String? = null): Tab {
        if (tabs.size >= MAX_TABS) {
            // close oldest non-active
            tabs.firstOrNull { it.id != activeTabId }?.let { closeTab(context, it.id) }
        }
        val wv = WebViewExt.newInstance(context)
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

    fun closeTab(context: Context, id: Long): Boolean {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val tab = tabs.removeAt(idx)
        runCatching {
            tab.webView.stopLoading()
            tab.webView.loadUrl("about:blank")
            tab.webView.clearHistory()
            tab.webView.removeAllViews()
            (tab.webView.parent as? android.view.ViewGroup)?.removeView(tab.webView)
            tab.webView.destroy()
        }
        if (tabs.isEmpty()) {
            createTab(context, null)
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

    fun updateTabInfo(id: Long, title: String? = null, favicon: Bitmap? = null, url: String? = null) {
        val tab = tabs.find { it.id == id } ?: return
        title?.let { tab.title = it }
        favicon?.let { tab.favicon = it }
        url?.let { tab.url = it }
        notifyChanged()
    }

    fun openInNewTab(context: Context, url: String? = null, incognito: Boolean = false) {
        createTab(context, url, incognito)
    }
}
