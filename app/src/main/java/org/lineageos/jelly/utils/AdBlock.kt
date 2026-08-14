/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jelly.utils

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

object AdBlock {
    private const val TAG = "AdBlock"
    private val EMPTY: InputStream = ByteArrayInputStream(ByteArray(0))
    private val blocked = HashSet<String>(120_000)
    private var currentLevel: String? = null

    private const val ASSET_LITE = "adblock_hosts_lite.txt"
    private const val ASSET_MODERATE = "adblock_hosts_moderate.txt"
    private const val ASSET_AGGRESSIVE = "adblock_hosts_aggressive.txt"

    @Synchronized
    fun ensureLevel(context: Context, level: String) {
        if (currentLevel == level && blocked.isNotEmpty()) return
        blocked.clear()
        val asset = when (level) {
            "aggressive" -> ASSET_AGGRESSIVE
            "moderate" -> ASSET_MODERATE
            else -> ASSET_LITE
        }
        try {
            context.assets.open(asset).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    val host = line.trim().lowercase()
                    if (host.isNotEmpty() && !host.startsWith("#")) {
                        blocked.add(host)
                    }
                }
            }
            Log.d(TAG, "Loaded ${blocked.size} hosts ($level)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load adblock ($asset)", e)
        }
        currentLevel = level
    }

    fun isAd(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return try {
            val host = extractHost(url) ?: return false
            val h = host.lowercase()
            if (blocked.contains(h)) return true
            var dot = h.indexOf('.')
            while (dot > 0) {
                val parent = h.substring(dot + 1)
                if (blocked.contains(parent)) return true
                val n = h.indexOf('.', dot + 1)
                if (n < 0) break
                dot = n
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", EMPTY)
    }

    private fun extractHost(url: String): String? {
        return try {
            var s = url
            val q = s.indexOf('?')
            if (q >= 0) s = s.substring(0, q)
            val h = s.indexOf('#')
            if (h >= 0) s = s.substring(0, h)
            val schemeEnd = s.indexOf("://")
            if (schemeEnd >= 0) s = s.substring(schemeEnd + 3)
            val slash = s.indexOf('/')
            if (slash >= 0) s = s.substring(0, slash)
            val colon = s.indexOf(':')
            if (colon >= 0) s = s.substring(0, colon)
            s.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
