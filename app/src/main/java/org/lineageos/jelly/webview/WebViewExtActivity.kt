/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jelly.webview

import android.content.DialogInterface
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import org.lineageos.jelly.models.PwaManifest
import org.lineageos.jelly.models.WebShare

abstract class WebViewExtActivity : AppCompatActivity() {
    abstract fun downloadFileAsk(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    )

    abstract fun showSheetMenu(url: String, shouldAllowDownload: Boolean)

    abstract fun launchFileRequest(mimeTypes: Array<String>?)

    abstract fun setFileRequestCallback(cb: (data: List<Uri>) -> Unit)

    abstract fun showLocationDialog(
        origin: String,
        callback: GeolocationPermissions.Callback
    )

    abstract fun onFaviconLoaded(favicon: android.graphics.Bitmap?)

    abstract fun onShowCustomView(view: android.view.View?, callback: WebChromeClient.CustomViewCallback)

    abstract fun onHideCustomView()

    abstract fun updateHistory(title: String, url: String)

    abstract fun replaceHistory(title: String, url: String, newUrl: String)

    abstract fun setPwaManifest(manifest: PwaManifest?)

    abstract fun webRequestPermissions(permissions: Array<String>, cb: (granted: Array<String>) -> Unit)

    abstract fun webProtectedMedia(origin: String, cb: (granted: Boolean) -> Unit)

    abstract fun onWebShare(value: WebShare)

    // New: notify activity that a tab's favicon/title changed
    open fun onTabUpdated(tab: WebViewExt) {}

    // New: notify activity that a tab started loading a URL (used to hide the
    // start page the moment the user navigates away from a blank tab).
    open fun onPageLoadStarted(tab: WebViewExt) {}

    // New: the element picker reported a tapped element (host + CSS selector
    // + tag name of the selection, for the confirmation sheet).
    open fun onElementPicked(host: String, selector: String, tag: String) {}

    // New: a page favicon arrived; persist it for history/favorites rows.
    open fun onHistoryFavicon(url: String?, favicon: ByteArray?) {}
}
