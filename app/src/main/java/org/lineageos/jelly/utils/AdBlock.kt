/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jelly.utils

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object AdBlock {
    private const val TAG = "AdBlock"

    /** Magic URL that serves the built-in ad-block test page. */
    const val TEST_PAGE_URL = "about:adblocktest"

    // Immutable snapshot swapped in atomically once loaded. Reads never lock,
    // and shouldInterceptRequest (which runs on the UI thread) is never
    // blocked parsing the hosts file — previously the whole list was read
    // synchronously on the UI thread on the first network request, which
    // froze the app for seconds right when the user pressed Search (an ANR
    // that looks exactly like a crash).
    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    private var currentLevel: String? = null

    private val loadScheduled = AtomicBoolean(false)
    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jelly-adblock-loader").apply { isDaemon = true }
    }

    /** Number of requests blocked since the app started (for the test page). */
    private val blockedRequests = AtomicLong(0)
    val blockedCount: Long get() = blockedRequests.get()

    private const val ASSET_LITE = "adblock_hosts_lite.txt"
    private const val ASSET_MODERATE = "adblock_hosts_moderate.txt"
    private const val ASSET_AGGRESSIVE = "adblock_hosts_aggressive.txt"
    private const val ASSET_TEST_PAGE = "adblock_test.html"

    /**
     * A 1x1 transparent GIF served for every blocked resource. Using a real
     * (tiny) image instead of an empty body has two benefits: images/iframes
     * don't show broken placeholders, and the test page can deterministically
     * detect a blocked request (any <img> that decodes to exactly 1x1 px was
     * intercepted by us — no real ad server serves a 1x1 GIF at our probe
     * URLs).
     */
    private val GIF_1X1 = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00,
        0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
    )

    /**
     * Schedules a background (re)load of the hosts list for [level] if it
     * isn't already loaded or the level changed. Safe to call on every
     * intercepted request.
     */
    fun ensureLevel(context: Context, level: String) {
        if (currentLevel == level && blocked.isNotEmpty()) return
        if (!loadScheduled.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        loader.execute {
            var asset = ASSET_LITE
            try {
                asset = when (level) {
                    "aggressive" -> ASSET_AGGRESSIVE
                    "moderate" -> ASSET_MODERATE
                    else -> ASSET_LITE
                }
                val set = HashSet<String>(120_000)
                appContext.assets.open(asset).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        normalizeHost(line)?.let { set.add(it) }
                    }
                }
                // Publish the snapshot only once fully built.
                blocked = set
                currentLevel = level
                Log.d(TAG, "Loaded ${set.size} hosts ($level)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load adblock ($asset)", e)
            } finally {
                loadScheduled.set(false)
            }
        }
    }

    /**
     * Turns one raw list line into a bare lowercase hostname, or null if the
     * line isn't a blockable host. Handles the formats found in the shipped
     * lists plus the common ones:
     *   - "domain$"        (this repo's lists carry a trailing "$")
     *   - "0.0.0.0 domain" / "127.0.0.1 domain" (classic hosts files)
     *   - "||domain^"      (Adblock/uBlock style)
     *   - "# comment" / "! comment"
     */
    private fun normalizeHost(line: String): String? {
        var s = line.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("#") || s.startsWith("!")) return null

        // adblock-style rules: ||domain^..., strip anchors
        if (s.startsWith("||")) s = s.substring(2)
        s = s.substringBefore('^')

        // everything from "$" on is filter options, not part of the host
        // (this is the fix: "doubleclick.net$" now yields "doubleclick.net")
        s = s.substringBefore('$')

        // classic hosts-file lines: "0.0.0.0 host" or "127.0.0.1 host"
        val parts = s.split(Regex("\\s+"))
        if (parts.size > 1) {
            s = parts.lastOrNull()?.takeUnless { it.contains('.') }?.let { parts.firstOrNull() }
                ?: parts.last()
        }

        s = s.trim().lowercase()
        // sanity: must look like a hostname (letters/digits, dots, hyphens)
        if (s.isEmpty() || !Regex("^[a-z0-9.-]+$").matches(s) || !s.contains('.')) return null
        return s
    }

    fun isAd(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val snapshot = blocked
        if (snapshot.isEmpty()) return false
        return try {
            val host = extractHost(url) ?: return false
            val h = host.lowercase()
            if (snapshot.contains(h)) return true
            var dot = h.indexOf('.')
            while (dot > 0) {
                val parent = h.substring(dot + 1)
                if (snapshot.contains(parent)) return true
                val n = h.indexOf('.', dot + 1)
                if (n < 0) break
                dot = n
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun createBlockedResponse(url: String?): WebResourceResponse {
        blockedRequests.incrementAndGet()
        Log.d(TAG, "Blocked: $url")
        // Fresh stream per response — streams aren't shareable across readers.
        return WebResourceResponse(
            "image/gif", "utf-8", ByteArrayInputStream(GIF_1X1)
        )
    }

    /**
     * Serves the built-in ad-block test page (see assets/adblock_test.html).
     * The page probes a set of known ad hosts per level plus control hosts
     * that must never be blocked, so you can verify the blocker in-app.
     */
    fun createTestPageResponse(context: Context): WebResourceResponse? {
        return try {
            val html = context.assets.open(ASSET_TEST_PAGE)
                .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                .replace("{{LEVEL}}", currentLevel ?: "not loaded")
                .replace("{{BLOCKED_TOTAL}}", blockedCount.toString())
            WebResourceResponse(
                "text/html", "utf-8", ByteArrayInputStream(html.toByteArray(StandardCharsets.UTF_8))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serve adblock test page", e)
            null
        }
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
