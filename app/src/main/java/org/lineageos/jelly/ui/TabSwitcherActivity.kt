/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 *
 * Via-style full-screen tab switcher with 2-column grid of preview cards.
 */
package org.lineageos.jelly.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.lineageos.jelly.R
import org.lineageos.jelly.utils.IntentUtils
import org.lineageos.jelly.utils.TabUtils
import java.lang.ref.WeakReference

/**
 * Standalone activity that shows all open tabs in a grid of cards,
 * mimicking Via Browser's tab switcher UI.
 * Returns result with action + tab id.
 */
class TabSwitcherActivity : AppCompatActivity() {

    private lateinit var adapter: TabAdapter
    private var previews = HashMap<Long, Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tab_switcher)

        val recycler = findViewById<RecyclerView>(R.id.tabRecycler)
        val fab = findViewById<FloatingActionButton>(R.id.fabNewTab)
        val countView = findViewById<TextView>(R.id.tabSwitcherCount)

        // Use GridLayoutManager with 2 columns
        val columns = resources.configuration.screenWidthDp.let {
            when {
                it >= 600 -> 3
                it >= 480 -> 2
                else -> 2
            }
        }
        recycler.layoutManager = GridLayoutManager(this, columns)
        adapter = TabAdapter(this)
        recycler.adapter = adapter

        countView.text = TabUtils.tabCount.toString()

        fab.setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().apply {
                action = ACTION_NEW_TAB
            })
            finish()
            overridePendingTransition(0, 0)
        }

        refreshTabs()
    }

    private fun refreshTabs() {
        // Capture preview snapshots of active webviews (best effort, off-main-thread)
        val tabs = TabUtils.allTabs
        tabs.forEach { tab ->
            try {
                val wv = tab.webView
                if (wv.width > 0 && wv.height > 0) {
                    val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.RGB_565)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    wv.draw(canvas)
                    // Scale down for card previews
                    val scaledW = 320
                    val scaledH = (scaledW * bmp.height / bmp.width.toFloat()).toInt().coerceAtMost(480)
                    val scaled = Bitmap.createScaledBitmap(bmp, scaledW, scaledH, true)
                    if (scaled !== bmp) bmp.recycle()
                    previews[tab.id] = scaled
                }
            } catch (_: Exception) {}
        }
        adapter.submitList(tabs)
        findViewById<TextView>(R.id.tabSwitcherCount).text = tabs.size.toString()
    }

    override fun onResume() {
        super.onResume()
        refreshTabs()
    }

    override fun onDestroy() {
        super.onDestroy()
        previews.values.forEach { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        previews.clear()
    }

    private fun selectTab(id: Long) {
        setResult(Activity.RESULT_OK, Intent().apply {
            action = ACTION_SELECT_TAB
            putExtra(EXTRA_TAB_ID, id)
        })
        finish()
        overridePendingTransition(0, 0)
    }

    private fun closeTab(id: Long) {
        TabUtils.closeTab(this, id)
        if (TabUtils.tabCount == 0) {
            // All closed — create a fresh tab and exit
            TabUtils.createTab(this, null, false)
            setResult(Activity.RESULT_OK, Intent().apply {
                action = ACTION_NEW_TAB
            })
            finish()
            overridePendingTransition(0, 0)
            return
        }
        refreshTabs()
    }

    inner class TabAdapter(
        private val activity: TabSwitcherActivity
    ) : RecyclerView.Adapter<TabAdapter.VH>() {

        private var items: List<TabUtils.Tab> = emptyList()

        fun submitList(newList: List<TabUtils.Tab>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tab_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tab = items[position]
            holder.bind(tab)
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val card: MaterialCardView = view.findViewById(R.id.tabCard)
            private val title: TextView = view.findViewById(R.id.tabTitle)
            private val url: TextView = view.findViewById(R.id.tabUrl)
            private val favicon: ImageView = view.findViewById(R.id.tabFavicon)
            private val preview: ImageView = view.findViewById(R.id.tabPreview)
            private val close: ImageView = view.findViewById(R.id.tabClose)
            private val incognitoBadge: TextView = view.findViewById(R.id.tabIncognitoBadge)

            fun bind(tab: TabUtils.Tab) {
                val t = tab.title ?: tab.url ?: tab.webView.title
                ?: activity.getString(R.string.tab_new_tab_hint)
                title.text = t
                url.text = tab.url ?: tab.webView.url ?: ""
                url.visibility = if (tab.url != null || tab.webView.url != null) View.VISIBLE else View.GONE

                // Favicon
                val fav = tab.favicon ?: tab.webView.tabFavicon
                if (fav != null && !fav.isRecycled) {
                    favicon.setImageBitmap(fav)
                } else {
                    favicon.setImageResource(R.drawable.ic_web_24dp)
                }

                // Preview
                val bmp = previews[tab.id]
                if (bmp != null && !bmp.isRecycled) {
                    preview.setImageBitmap(bmp)
                    preview.visibility = View.VISIBLE
                    incognitoBadge.visibility = View.GONE
                } else {
                    preview.setImageBitmap(null)
                    if (tab.incognito) {
                        incognitoBadge.visibility = View.VISIBLE
                        preview.visibility = View.INVISIBLE
                    } else {
                        incognitoBadge.visibility = View.GONE
                        preview.visibility = View.VISIBLE
                        preview.setImageResource(R.drawable.ic_web_24dp)
                    }
                }

                // Active tab highlight via stroke
                val isActive = tab.id == TabUtils.activeTab?.id
                card.strokeWidth = if (isActive) 4 else 0
                val ta = activity.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorPrimaryContainer))
                val activeColor = ta.getColor(0, Color.LTGRAY)
                ta.recycle()
                card.setCardBackgroundColor(if (isActive) activeColor else Color.TRANSPARENT)

                card.setOnClickListener { activity.selectTab(tab.id) }
                close.setOnClickListener { activity.closeTab(tab.id) }
            }
        }
    }

    companion object {
        const val ACTION_NEW_TAB = "org.lineageos.jelly.action.NEW_TAB"
        const val ACTION_SELECT_TAB = "org.lineageos.jelly.action.SELECT_TAB"
        const val EXTRA_TAB_ID = "tab_id"

        fun startForResult(activity: Activity, requestCode: Int) {
            val i = Intent(activity, TabSwitcherActivity::class.java)
            activity.startActivityForResult(i, requestCode)
        }
    }
}
