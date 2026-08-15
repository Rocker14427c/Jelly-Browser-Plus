/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 *
 * User-defined blocking filters:
 *  - "Mark as ad" (Via-style): a host whose requests get blocked network-wide.
 *  - "Block element" (Brave-style): a CSS selector hidden on every page.
 *
 * Rules persist in SharedPreferences and are applied by AdBlock (hosts) and
 * WebClient (selector CSS injection).
 */
package org.lineageos.jelly.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

object UserFilters {
    private const val PREFS_NAME = "user_filters"
    private const val KEY_HOSTS = "blocked_hosts"
    private const val KEY_SELECTORS = "blocked_selectors"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var hosts: MutableSet<String> = CopyOnWriteArraySet()

    @Volatile
    private var selectors: MutableSet<String> = CopyOnWriteArraySet()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        hosts = CopyOnWriteArraySet(p.getStringSet(KEY_HOSTS, emptySet()) ?: emptySet())
        selectors = CopyOnWriteArraySet(p.getStringSet(KEY_SELECTORS, emptySet()) ?: emptySet())
    }

    val blockedHosts: Set<String> get() = hosts
    val blockedSelectors: Set<String> get() = selectors

    fun isHostBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty() || hosts.isEmpty()) return false
        val h = host.lowercase()
        if (hosts.contains(h)) return true
        // domain-match like the built-in blocker
        var dot = h.indexOf('.')
        while (dot > 0) {
            if (hosts.contains(h.substring(dot + 1))) return true
            val n = h.indexOf('.', dot + 1)
            if (n < 0) break
            dot = n
        }
        return false
    }

    fun blockHost(host: String) {
        val clean = host.lowercase().trim()
        if (clean.isEmpty()) return
        hosts.add(clean)
        persist()
    }

    fun blockSelector(selector: String) {
        val clean = selector.trim()
        if (clean.isEmpty()) return
        selectors.add(clean)
        persist()
    }

    fun removeHost(host: String) {
        hosts.remove(host)
        persist()
    }

    fun removeSelector(selector: String) {
        selectors.remove(selector)
        persist()
    }

    private fun persist() {
        prefs?.edit()?.apply {
            putStringSet(KEY_HOSTS, hosts.toSet())
            putStringSet(KEY_SELECTORS, selectors.toSet())
            apply()
        }
    }
}
