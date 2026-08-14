/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jelly.webview

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.lineageos.jelly.R
import org.lineageos.jelly.js.JsManifest
import org.lineageos.jelly.ui.UrlBarLayout
import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.utils.TabUtils
import kotlin.reflect.cast

internal class ChromeClient(
    private val activity: WebViewExtActivity,
    private val incognito: Boolean,
    private val urlBarLayout: UrlBarLayout,
    private val sharedPreferencesExt: SharedPreferencesExt
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, progress: Int) {
        urlBarLayout.loadingProgress = progress
        super.onProgressChanged(view, progress)
    }

    override fun onReceivedTitle(view: WebView, title: String) {
        view.url?.let {
            if (!incognito) {
                activity.updateHistory(title, url = it)
            }
        }
        if (view is WebViewExt) {
            view.updateTabInfo(title, view.favicon)
            activity.onTabUpdated(view)
        }
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap) {
        if (!view.settings.javaScriptEnabled) {
            activity.onFaviconLoaded(icon)
            return
        }
        if (view is WebViewExt) {
            view.updateTabInfo(view.title, icon)
        }
        view.evaluateJavascript("${JsManifest.URL}()") { manifestUrl ->
            if (manifestUrl.isBlank() || manifestUrl == "\"\"") {
                activity.onFaviconLoaded(icon)
            }
        }
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = request.origin.toString()
        val resources = request.resources
        if (resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
            val permission = arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            val whitelist = sharedPreferencesExt.protectedMediaWhitelist.toMutableSet()
            when (whitelist.contains(origin)) {
                true -> request.grant(permission)
                false -> {
                    activity.webProtectedMedia(origin) { granted ->
                        if (!granted) return@webProtectedMedia
                        request.grant(permission)
                        whitelist.add(origin)
                        sharedPreferencesExt.protectedMediaWhitelist = whitelist.toSet()
                    }
                }
            }
            return
        }
        val permissions = buildList {
            if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                add(android.Manifest.permission.CAMERA)
            }
            if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                add(android.Manifest.permission.RECORD_AUDIO)
            }
        }
        if (permissions.isEmpty()) return
        activity.webRequestPermissions(permissions.toTypedArray()) { granted ->
            val grantedResources = buildList {
                if (granted.contains(android.Manifest.permission.CAMERA)) {
                    add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                }
                if (granted.contains(android.Manifest.permission.RECORD_AUDIO)) {
                    add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                }
            }
            if (grantedResources.isNotEmpty()) request.grant(grantedResources.toTypedArray())
        }
    }

    override fun onShowFileChooser(
        view: WebView, path: ValueCallback<Array<Uri>>,
        params: FileChooserParams
    ): Boolean {
        activity.setFileRequestCallback { path.onReceiveValue(it.toTypedArray()) }
        try {
            activity.launchFileRequest(params.acceptTypes.mapNotNull {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
            }.toTypedArray().takeIf { it.isNotEmpty() } ?: arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.error_no_activity_found), Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String, callback: GeolocationPermissions.Callback
    ) {
        activity.showLocationDialog(origin, callback)
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        activity.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        activity.onHideCustomView()
    }

    override fun onCreateWindow(
        view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
    ): Boolean {
        if (!sharedPreferencesExt.dynamicPopupEnabled && !isUserGesture) {
            // Popup blocker: consume the message WITHOUT attaching a WebView to
            // the transport. The renderer simply gets a null window (the classic
            // AOSP Browser pattern). Previously a freshly-destroyed WebView was
            // sent as the transport, which crashed the renderer as soon as the
            // site tried to use the window — exactly what happens constantly on
            // ad-heavy search results pages.
            runCatching { resultMsg.sendToTarget() }
            return true
        }

        // Intercept window.open / target="_blank" and route to a new in-app tab.
        // Create a hidden WebView, let the engine load the URL into it, then
        // capture that URL and move it into TabUtils without ever attaching the
        // temp view to a window (which would create a new Android task /
        // Recents entry).
        val transport = WebView.WebViewTransport::class.cast(resultMsg.obj)
        var handled = false
        val tempWebView = WebView(view.context)
        tempWebView.settings.javaScriptEnabled = view.settings.javaScriptEnabled
        tempWebView.settings.domStorageEnabled = false
        tempWebView.settings.databaseEnabled = false

        // Destroying a WebView from inside its own callback can deadlock or
        // crash Chromium, so the actual destroy always happens outside.
        fun destroyTemp() {
            view.post {
                runCatching {
                    tempWebView.stopLoading()
                    tempWebView.removeAllViews()
                    tempWebView.destroy()
                }
            }
        }

        fun routeAndCleanup(url: String) {
            if (handled) return
            handled = true
            destroyTemp()
            TabUtils.openInNewTab(activity, url, incognito)
        }

        tempWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame) routeAndCleanup(request.url.toString())
                return true
            }
            override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                routeAndCleanup(url)
                return true
            }
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                if (!url.isNullOrEmpty() && url != "about:blank") routeAndCleanup(url)
            }
        }
        transport.webView = tempWebView
        resultMsg.sendToTarget()

        // Safety fallback — if the URL never surfaces through the callbacks,
        // clean up the temp WebView after 800ms. No blank tab is created:
        // opening a useless blank tab is worse than dropping the popup.
        view.postDelayed({
            if (!handled) {
                handled = true
                destroyTemp()
            }
        }, 800)
        return true
    }
}
