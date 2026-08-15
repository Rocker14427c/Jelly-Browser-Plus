/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jelly.webview

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.text.TextUtils
import android.view.LayoutInflater
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import org.lineageos.jelly.R
import org.lineageos.jelly.js.JsManifest
import org.lineageos.jelly.js.JsMediaSession
import org.lineageos.jelly.js.JsShare
import org.lineageos.jelly.js.JsSyncUrl
import org.lineageos.jelly.ui.UrlBarLayout
import org.lineageos.jelly.utils.AdBlock
import org.lineageos.jelly.utils.AssetLoader
import org.lineageos.jelly.utils.IntentUtils
import org.lineageos.jelly.utils.SharedPreferencesExt
import org.lineageos.jelly.utils.UrlUtils
import org.lineageos.jelly.utils.UserFilters
import java.net.URISyntaxException

internal class WebClient(
    private val context: Context,
    private val urlBarLayout: UrlBarLayout,
    private val webViewExt: WebViewExt
) : WebViewClient() {
    private val prefs by lazy { SharedPreferencesExt(context) }

    private val scripts by lazy {
        val mediaSessionAPI = AssetLoader.loadAsset(context.resources, "MediaSessionAPI.js")
        buildString {
            appendLine(mediaSessionAPI)
            appendLine(JsMediaSession.SCRIPT)
            appendLine(JsShare.SCRIPT)
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (prefs.adBlockEnabled) {
            AdBlock.ensureLevel(context, prefs.adBlockLevel)
            val url = request.url.toString()
            if (!request.isForMainFrame && AdBlock.isAd(url)) {
                return AdBlock.createBlockedResponse(url)
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        urlBarLayout.onPageLoadStarted(url)
        if (view.settings.javaScriptEnabled) {
            view.evaluateJavascript(scripts, null)
            // Hide user-blocked elements (Brave-style) as early as possible.
            userCssScript()?.let { view.evaluateJavascript(it, null) }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        urlBarLayout.onPageLoadFinished(view.certificate)
        if (view is WebViewExt) {
            view.onPageLoadedInject()
        }
        if (view.settings.javaScriptEnabled) {
            view.evaluateJavascript(JsSyncUrl.SCRIPT, null)
            view.evaluateJavascript(JsManifest.SCRIPT, null)
            userCssScript()?.let { view.evaluateJavascript(it, null) }
        }
    }

    /** CSS rules hiding user-blocked selectors, injected + observed so they
     *  survive SPA navigations and dynamically added content. */
    private fun userCssScript(): String? {
        val selectors = UserFilters.blockedSelectors
        if (selectors.isEmpty()) return null
        val rules = selectors.joinToString(",")
        return """
(function(){
  try {
    var id = '__jelly_user_css';
    var CSS = '$rules { display:none !important; visibility:hidden !important; }';
    var s = document.getElementById(id);
    if (!s) {
      s = document.createElement('style');
      s.id = id;
      (document.head || document.documentElement).appendChild(s);
    }
    s.textContent = CSS;
    if (!window.__jellyUserObserver) {
      window.__jellyUserObserver = new MutationObserver(function(){
        var s2 = document.getElementById(id);
        if (!s2 && document.head) {
          s2 = document.createElement('style');
          s2.id = id;
          document.head.appendChild(s2);
          s2.textContent = CSS;
        }
      });
      window.__jellyUserObserver.observe(document.documentElement || document,
        {childList:true, subtree:true});
    }
  } catch(e) {}
})();
""".trimIndent()
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        super.onReceivedSslError(view, handler, error)
        urlBarLayout.onSslError(error)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) {
            val webViewExt = view as WebViewExt
            val url = request.url.toString()
            val needsLookup = request.hasGesture() || !TextUtils.equals(url, webViewExt.lastLoadedUrl)
            if (!webViewExt.isIncognito && needsLookup && startActivityForUrl(view, url) && !request.isRedirect) {
                return true
            } else if (webViewExt.requestHeaders.isNotEmpty()) {
                webViewExt.followUrl(url)
                return true
            }
        }
        return false
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler, host: String, realm: String
    ) {
        // The dialog needs a live Activity context; background-shortcut
        // WebViews and finishing activities can't host it.
        val activity = context as? android.app.Activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            handler.cancel()
            return
        }
        val builder = AlertDialog.Builder(context)
        val layoutInflater = LayoutInflater.from(context)
        val dialogView = layoutInflater.inflate(R.layout.auth_dialog, LinearLayout(context))
        val usernameEditText = dialogView.findViewById<EditText>(R.id.usernameEditText)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.passwordEditText)
        val authDetailTextView = dialogView.findViewById<TextView>(R.id.authDetailTextView)
        authDetailTextView.text = context.getString(R.string.auth_dialog_detail, view.url)
        builder.setView(dialogView)
            .setTitle(R.string.auth_dialog_title)
            .setPositiveButton(R.string.auth_dialog_login) { _, _ ->
                handler.proceed(usernameEditText.text.toString(), passwordEditText.text.toString())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> handler.cancel() }
            .setOnDismissListener { handler.cancel() }
            .show()
    }

    private fun startActivityForUrl(view: WebView, url: String): Boolean {
        val context = view.context
        var intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (ex: URISyntaxException) {
            return false
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val m = UrlUtils.ACCEPTED_URI_SCHEMA.matcher(url)
        if (m.matches()) {
            intent = makeHandlerChooserIntent(context, intent, url) ?: return false
        } else {
            val packageName = intent.getPackage()
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(intent, ResolveInfoFlags.of(0))
            } else {
                context.packageManager.resolveActivity(intent, 0)
            }
            if (packageName != null && resolveInfo == null) {
                val storeUri = Uri.parse("market://search?q=pname:$packageName")
                intent = Intent(Intent.ACTION_VIEW, storeUri).addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
        try {
            context.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(view, context.getString(R.string.error_no_activity_found), Snackbar.LENGTH_LONG).show()
        }
        return false
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun makeHandlerChooserIntent(context: Context, intent: Intent, url: String): Intent? {
        val pm = context.packageManager
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, ResolveInfoFlags.of(flags.toLong()))
        } else {
            pm.queryIntentActivities(intent, flags)
        }
        if (activities.isEmpty()) return null
        val chooserIntents = ArrayList<Intent>()
        val ourPackageName = context.packageName
        activities.sortWith(ResolveInfo.DisplayNameComparator(pm))
        for (resolveInfo in activities) {
            val filter = resolveInfo.filter ?: continue
            val info = resolveInfo.activityInfo
            if (!info.enabled || !info.exported) continue
            if (filter.countDataAuthorities() == 0 && !TextUtils.equals(info.packageName, ourPackageName)) continue
            val targetIntent = Intent(intent)
            targetIntent.setPackage(info.packageName)
            chooserIntents.add(targetIntent)
        }
        if (chooserIntents.isEmpty()) return null
        val lastIntent = chooserIntents.removeAt(chooserIntents.size - 1)
        if (chooserIntents.isEmpty()) {
            return if (ourPackageName == lastIntent.getPackage()) null else lastIntent
        }
        val changeIntent = Intent(IntentUtils.EVENT_URL_RESOLVED)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
            .putExtra(IntentUtils.EXTRA_URL, url)
        val pi = PendingIntent.getBroadcast(
            context, 0, changeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT
        )
        val chooserIntent = Intent.createChooser(lastIntent, null)
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, chooserIntents.toTypedArray())
        chooserIntent.putExtra(Intent.EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER, pi.intentSender)
        return chooserIntent
    }
}
