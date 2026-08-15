/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 *
 * JavaScript-based find-in-page with guaranteed-visible highlighting.
 *
 * The native WebView finder leaves its match highlight up to the engine
 * (which is nearly invisible in dark mode and gets overridden by injected
 * dark-mode CSS), so finding is reimplemented in JS: every match is wrapped
 * in a styled <mark data-jelly-find>, the current one gets an accent outline,
 * and the page scrolls it into view. The bridge reports
 * (currentIndex, totalMatches) back to the URL bar on the main thread.
 */
package org.lineageos.jelly.js

import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import org.lineageos.jelly.ui.UrlBarLayout

@Keep
class JsFindInPage(
    private val urlBarLayout: UrlBarLayout,
) {
    /** Called from JS on the WebView bridge thread. */
    @JavascriptInterface
    fun onResult(index: Int, count: Int) {
        urlBarLayout.post {
            runCatching {
                urlBarLayout.searchPositionInfo = Pair(index.coerceAtLeast(0), count)
            }
        }
    }

    companion object {
        const val INTERFACE = "JsFindInPage"

        private fun jsEscape(s: String) = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        /** Starts (or restarts) a search for [query]. */
        fun run(query: String): String {
            val q = jsEscape(query)
            return """
(function(){
  try {
    var F = window.__jellyFind;
    if (!F) {
      var CSS = 'mark[data-jelly-find]{background-color:#FFC107 !important;color:#000 !important;border-radius:2px;padding:0 1px;}' +
                'mark[data-jelly-find].current{background-color:#FF9800 !important;color:#000 !important;outline:2px solid #BF360C;}' +
                'mark[data-jelly-find] *{background-color:transparent !important;color:inherit !important;}';
      var style = document.createElement('style');
      style.id = '__jelly_find_css';
      style.textContent = CSS;
      (document.head || document.documentElement).appendChild(style);
      F = window.__jellyFind = {
        query: '', marks: [], index: -1,
        clearMarks: function(){
          for (var i = 0; i < this.marks.length; i++) {
            var m = this.marks[i];
            if (m.parentNode) m.parentNode.replaceChild(document.createTextNode(m.textContent), m);
          }
          this.marks = [];
          this.index = -1;
        },
        focus: function(){
          if (!this.marks.length) return;
          var m = this.marks[this.index];
          if (m.getBoundingClientRect) {
            try { m.scrollIntoView({block:'center', behavior:'smooth'}); } catch(e) { m.scrollIntoView(); }
          }
          for (var i = 0; i < this.marks.length; i++) this.marks[i].classList.remove('current');
          m.classList.add('current');
        },
        run: function(q){
          this.clearMarks();
          this.query = q.toLowerCase();
          if (!this.query) {
            try { JsFindInPage.onResult(0, 0); } catch(e) {}
            return;
          }
          var body = document.body;
          if (!body) return;
          var self = this;
          var walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, {
            acceptNode: function(n){
              var p = n.parentElement;
              if (!p) return NodeFilter.FILTER_REJECT;
              var t = p.tagName;
              if (t === 'SCRIPT' || t === 'STYLE' || t === 'NOSCRIPT' || t === 'TEXTAREA' || t === 'INPUT') return NodeFilter.FILTER_REJECT;
              if (p.getAttribute && p.getAttribute('data-jelly-find')) return NodeFilter.FILTER_REJECT;
              if (p.isContentEditable) return NodeFilter.FILTER_REJECT;
              return NodeFilter.FILTER_ACCEPT;
            }
          });
          var nodes = [];
          while (walker.nextNode()) nodes.push(walker.currentNode);
          for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var text = node.nodeValue;
            if (!text) continue;
            var lower = text.toLowerCase();
            var idx = lower.indexOf(self.query);
            if (idx < 0) continue;
            var frag = document.createDocumentFragment();
            var pos = 0;
            while (idx >= 0) {
              if (idx > pos) frag.appendChild(document.createTextNode(text.substring(pos, idx)));
              var mk = document.createElement('mark');
              mk.setAttribute('data-jelly-find', '1');
              mk.textContent = text.substring(idx, idx + self.query.length);
              self.marks.push(mk);
              frag.appendChild(mk);
              pos = idx + self.query.length;
              idx = lower.indexOf(self.query, pos);
            }
            if (pos < text.length) frag.appendChild(document.createTextNode(text.substring(pos)));
            if (node.parentNode) node.parentNode.replaceChild(frag, node);
          }
          if (this.marks.length) {
            this.index = 0;
            this.focus();
            try { JsFindInPage.onResult(0, this.marks.length); } catch(e) {}
          } else {
            try { JsFindInPage.onResult(0, 0); } catch(e) {}
          }
        },
        next: function(forward){
          if (!this.marks.length) return;
          this.index = (this.index + (forward ? 1 : -1) + this.marks.length) % this.marks.length;
          this.focus();
          try { JsFindInPage.onResult(this.index, this.marks.length); } catch(e) {}
        },
        clear: function(){
          this.clearMarks();
          try { JsFindInPage.onResult(0, 0); } catch(e) {}
        }
      };
    }
    F.run("$q");
  } catch(e) {}
})();
""".trimIndent()
        }

        /** Moves to the next (true) or previous (false) match. */
        val NEXT: String = """
(function(){
  try {
    var F = window.__jellyFind;
    if (F) F.next(###FORWARD###);
  } catch(e) {}
})();
""".trimIndent()

        fun next(forward: Boolean): String =
            NEXT.replace("###FORWARD###", if (forward) "true" else "false")

        /** Clears all highlights and the search state. */
        val CLEAR: String = """
(function(){
  try {
    var F = window.__jellyFind;
    if (F) F.clear();
  } catch(e) {}
})();
""".trimIndent()
    }
}
