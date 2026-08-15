/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 *
 * JavaScript bridge for the element/ad picker.
 *
 * Two-phase UX (no more "tap and boom"):
 *  1. Pick mode: the element under the finger is highlighted; tapping
 *     FREEZES the selection — the element keeps a visible overlay box —
 *     and reports it to the app. Nothing is blocked yet.
 *  2. The app shows a sheet with Block element / Mark host as ad /
 *     Expand selection / Cancel. "Expand selection" calls expand(), which
 *     re-selects the parent element (growing the selection from a tiny
 *     span all the way up to the full page), and reports again.
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
    fun onPicked(host: String, selector: String, tag: String) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.onElementPicked(host, selector, tag)
            }
        }
    }

    companion object {
        const val INTERFACE = "JsElementPicker"

        /** Page-side picker state. */
        const val SCRIPT = """
(function(){
  try {
    var box = null;
    var pickedEl = null;

    function makeBox(el) {
      if (!el || el.nodeType !== 1) return;
      removeBox();
      box = document.createElement('div');
      box.setAttribute('data-jelly-picker-box', '1');
      box.__jellyEl = el;
      var r = el.getBoundingClientRect();
      box.style.cssText = [
        'position:fixed',
        'left:' + (r.left + window.scrollX) + 'px',
        'top:' + (r.top + window.scrollY) + 'px',
        'width:' + r.width + 'px',
        'height:' + r.height + 'px',
        'background:rgba(244,67,54,0.18)',
        'border:2px solid #f44336',
        'border-radius:4px',
        'z-index:2147483647',
        'pointer-events:none',
        'box-sizing:border-box'
      ].join(';');
      document.documentElement.appendChild(box);
    }

    function removeBox() {
      if (box && box.parentNode) box.parentNode.removeChild(box);
      box = null;
    }

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
        var src = node.getAttribute && (node.getAttribute('src') ||
                  node.getAttribute('data-src') || node.getAttribute('href'));
        if ((tag === 'img' || tag === 'iframe' || tag === 'script' ||
             tag === 'video' || tag === 'audio' || tag === 'source') && src) {
          try {
            var u = new URL(src, document.baseURI);
            if (u.hostname && u.hostname !== location.hostname) return u.hostname;
          } catch(e) {}
        }
        node = node.parentElement;
      }
      return location.hostname;
    }

    function report(el) {
      pickedEl = el;
      makeBox(el);
      try {
        JsElementPicker.onPicked(hostFrom(el), cssPath(el),
          (el.tagName || '').toLowerCase());
      } catch(_) {}
    }

    function onMove(e) {
      var t = e.target;
      if (t === pickedEl) return;
      if (pickedEl) return; // selection frozen until the app responds
      makeBox(t);
    }

    function onTap(e) {
      e.preventDefault();
      e.stopPropagation();
      var el = e.target;
      if (pickedEl) return;
      report(el);
    }

    function teardown() {
      document.removeEventListener('click', onTap, true);
      document.removeEventListener('touchmove', onMove, true);
      removeBox();
      pickedEl = null;
      window.__jellyPicker = false;
    }

    window.__jellyPicker = true;
    document.addEventListener('click', onTap, true);
    document.addEventListener('touchmove', onMove, true);
  } catch(e) {}
})();
"""

        /** Expands the current selection to its parent and reports again. */
        const val EXPAND: String = """
(function(){
  try {
    var box = document.querySelector('[data-jelly-picker-box]');
    var el = null;
    if (box && box.__jellyEl) el = box.__jellyEl;
    if (!el) {
      // fallback: find the highlighted element via outline
      var all = document.querySelectorAll('*');
      for (var i = 0; i < all.length; i++) {
        if (all[i].style && all[i].style.outline.indexOf('2px solid') === 0) {
          el = all[i];
          break;
        }
      }
    }
    if (!el) return;
    var target = el.parentElement || el;
    if (target === document.body || target === document.documentElement) {
      target = document.documentElement;
    }
    if (!target || target.nodeType !== 1) return;
    if (box && box.__jellyEl === target) return; // nothing to expand to
    // mark the target for the report above
    var b = document.createElement('div');
    b.setAttribute('data-jelly-picker-box', '1');
    b.__jellyEl = target;
    var r = target.getBoundingClientRect();
    b.style.cssText = [
      'position:fixed',
      'left:' + (r.left + window.scrollX) + 'px',
      'top:' + (r.top + window.scrollY) + 'px',
      'width:' + r.width + 'px',
      'height:' + r.height + 'px',
      'background:rgba(244,67,54,0.18)',
      'border:2px solid #f44336',
      'border-radius:4px',
      'z-index:2147483647',
      'pointer-events:none'
    ].join(';');
    document.documentElement.appendChild(b);
    try {
      JsElementPicker.onPicked(
        (function(){
          var n = target;
          while (n && n.nodeType === 1) {
            var tag = n.tagName.toLowerCase();
            var src = n.getAttribute && (n.getAttribute('src') || n.getAttribute('href'));
            if ((tag === 'img' || tag === 'iframe' || tag === 'script' ||
                 tag === 'video' || tag === 'source') && src) {
              try {
                var u = new URL(src, document.baseURI);
                if (u.hostname && u.hostname !== location.hostname) return u.hostname;
              } catch(e) {}
            }
            n = n.parentElement;
          }
          return location.hostname;
        })(),
        (function(){
          var parts = [];
          var node = target;
          while (node && node.nodeType === 1 && node !== document.body) {
            var part = node.tagName.toLowerCase();
            if (node.id) { parts.unshift('#' + CSS.escape(node.id)); break; }
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
                part += ':nth-of-type(' +
                  (Array.prototype.indexOf.call(same, node) + 1) + ')';
              }
            }
            parts.unshift(part);
            node = parent;
          }
          return parts.join(' > ');
        })(),
        (target.tagName || '').toLowerCase()
      );
    } catch(_) {}
  } catch(e) {}
})();
"""

        /** Clears the picker highlight without blocking anything. */
        const val CLEAR: String = """
(function(){
  try {
    var boxes = document.querySelectorAll('[data-jelly-picker-box]');
    for (var i = 0; i < boxes.length; i++) {
      if (boxes[i].parentNode) boxes[i].parentNode.removeChild(boxes[i]);
    }
  } catch(e) {}
})();
"""
    }
}
