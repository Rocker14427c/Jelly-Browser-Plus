/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 *
 * JavaScript bridge for the element/ad picker: the page-side script lets the
 * user tap any element; this interface receives the computed CSS selector
 * and the request host, and hands them to the activity for confirmation.
 */
package org.lineageos.jelly.js

import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import org.lineageos.jelly.webview.WebViewExtActivity

@Keep
class JsElementPicker(
    private val activity: WebViewExtActivity,
) {
    /** Called from JS on the WebView bridge thread. */
    @JavascriptInterface
    fun onPicked(host: String, selector: String) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.onElementPicked(host, selector)
            }
        }
    }

    companion object {
        const val INTERFACE = "JsElementPicker"

        /**
         * Page-side picker: highlights the element under the finger, and on
         * tap computes a stable CSS selector plus the resource host
         * (iframe/img/script/video src, or the page host) and reports it.
         */
        const val SCRIPT = """
(function(){
  try {
    if (window.__jellyPicker) return;
    var lastEl = null;

    function cssPath(el) {
      if (!el || el.nodeType !== 1) return '';
      var parts = [];
      var node = el;
      while (node && node.nodeType === 1 && node !== document.body) {
        var part = node.tagName.toLowerCase();
        if (node.id) {
          parts.unshift('#' + CSS.escape(node.id));
          break;
        }
        var classes = [];
        if (node.className && typeof node.className === 'string') {
          node.className.trim().split(/\s+/).slice(0, 2).forEach(function(c) {
            if (c) classes.push('.' + CSS.escape(c));
          });
        }
        part += classes.join('');
        var parent = node.parentElement;
        if (parent) {
          var same = Array.prototype.filter.call(parent.children, function(c) {
            return c.tagName === node.tagName;
          });
          if (same.length > 1) {
            part += ':nth-of-type(' + (Array.prototype.indexOf.call(same, node) + 1) + ')';
          }
        }
        parts.unshift(part);
        node = parent;
      }
      return parts.join(' > ');
    }

    function hostFrom(el) {
      var node = el;
      while (node && node.nodeType === 1) {
        var tag = node.tagName.toLowerCase();
        var src = node.getAttribute && (node.getAttribute('src') || node.getAttribute('data-src') || node.getAttribute('href'));
        if ((tag === 'img' || tag === 'iframe' || tag === 'script' || tag === 'video' || tag === 'audio' || tag === 'source') && src) {
          try {
            var u = new URL(src, document.baseURI);
            if (u.hostname && u.hostname !== location.hostname) return u.hostname;
          } catch(e) {}
        }
        node = node.parentElement;
      }
      return location.hostname;
    }

    function onMove(e) {
      var t = e.target;
      if (lastEl === t) return;
      if (lastEl) lastEl.style.outline = '';
      lastEl = t;
      if (t && t.style) t.style.outline = '2px solid #f44336';
    }

    function onTap(e) {
      e.preventDefault();
      e.stopPropagation();
      var el = e.target;
      var selector = cssPath(el);
      var host = hostFrom(el);
      if (lastEl) lastEl.style.outline = '';
      teardown();
      try { JsElementPicker.onPicked(host, selector); } catch(_) {}
    }

    function teardown() {
      document.removeEventListener('click', onTap, true);
      document.removeEventListener('touchmove', onMove, true);
      if (lastEl) { lastEl.style.outline = ''; lastEl = null; }
      window.__jellyPicker = false;
    }

    window.__jellyPicker = true;
    document.addEventListener('click', onTap, true);
    document.addEventListener('touchmove', onMove, true);
  } catch(e) {}
})();
"""
    }
}
