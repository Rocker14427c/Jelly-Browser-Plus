/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.history

import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.lineageos.jelly.R
import org.lineageos.jelly.model.History
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A row in the history list: either a date header or a history entry. */
sealed class HistoryListItem {
    data class Header(val label: String) : HistoryListItem()
    data class Entry(val history: History) : HistoryListItem()
}

class HistoryAdapter(context: Context) :
    ListAdapter<HistoryListItem, RecyclerView.ViewHolder>(diffCallback) {

    var onRowClick: ((History) -> Unit) = { }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is HistoryListItem.Header -> TYPE_HEADER
        is HistoryListItem.Entry -> TYPE_ENTRY
    }

    override fun getItemId(position: Int) = when (val item = getItem(position)) {
        is HistoryListItem.Header -> item.label.hashCode().toLong()
        is HistoryListItem.Entry -> item.history.id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                inflater.inflate(R.layout.item_history_header, parent, false)
            )
            else -> EntryHolder(
                inflater.inflate(R.layout.item_history, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HistoryListItem.Header -> (holder as HeaderHolder).bind(item)
            is HistoryListItem.Entry -> (holder as EntryHolder).bind(item.history)
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label = view.findViewById<TextView>(R.id.rowHistoryHeaderLabel)
        fun bind(item: HistoryListItem.Header) {
            label.text = item.label
        }
    }

    inner class EntryHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val layout = view.findViewById<View>(R.id.rowHistoryLayout)
        private val icon = view.findViewById<TextView>(R.id.rowHistoryIcon)
        private val favicon = view.findViewById<ImageView>(R.id.rowHistoryFavicon)
        private val title = view.findViewById<TextView>(R.id.rowHistoryTitleTextView)
        private val url = view.findViewById<TextView>(R.id.rowHistoryUrlTextView)
        private val time = view.findViewById<TextView>(R.id.rowHistoryTimeTextView)

        fun bind(history: History) {
            title.text = history.title.ifEmpty {
                history.url.split("/").getOrNull(2) ?: history.url
            }
            url.text = history.url
            time.text = timeFormat.format(Date(history.timestamp))
            // The site's web icon when known; the host's initial otherwise.
            val bytes = history.favicon
            val bmp = bytes?.let { runCatching {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }.getOrNull() }
            if (bmp != null) {
                favicon.setImageBitmap(bmp)
                favicon.isVisible = true
                icon.isVisible = false
            } else {
                favicon.setImageBitmap(null)
                favicon.isVisible = false
                icon.isVisible = true
                val host = history.url.split("/").getOrNull(2).orEmpty()
                icon.text = (host.substringBefore('.').ifEmpty { history.url })
                    .firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            }
            layout.setOnClickListener {
                onRowClick(history)
            }
        }
    }

    companion object {
        /** View type of date headers (non-swipeable rows). */
        const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1

        private val diffCallback = object : DiffUtil.ItemCallback<HistoryListItem>() {
            override fun areItemsTheSame(
                oldItem: HistoryListItem, newItem: HistoryListItem
            ) = when {
                oldItem is HistoryListItem.Header && newItem is HistoryListItem.Header ->
                    oldItem.label == newItem.label
                oldItem is HistoryListItem.Entry && newItem is HistoryListItem.Entry ->
                    oldItem.history.id == newItem.history.id
                else -> false
            }

            override fun areContentsTheSame(
                oldItem: HistoryListItem, newItem: HistoryListItem
            ): Boolean {
                // ByteArray equality is referential — compare everything
                // except favicon bytes, plus a favicon size check.
                if (oldItem is HistoryListItem.Entry && newItem is HistoryListItem.Entry) {
                    val a = oldItem.history
                    val b = newItem.history
                    return a.id == b.id && a.title == b.title && a.url == b.url &&
                        a.timestamp == b.timestamp &&
                        (a.favicon?.size ?: 0) == (b.favicon?.size ?: 0)
                }
                return oldItem == newItem
            }
        }
    }
}

/** Groups history entries under Today / Yesterday / date headers. */
fun List<History>.groupedForUi(headerLabels: (Long) -> String): List<HistoryListItem> {
    val items = mutableListOf<HistoryListItem>()
    var lastDay: Long = Long.MIN_VALUE
    for (entry in this) {
        val day = dayBucket(entry.timestamp)
        if (day != lastDay) {
            items.add(HistoryListItem.Header(headerLabels(entry.timestamp)))
            lastDay = day
        }
        items.add(HistoryListItem.Entry(entry))
    }
    return items
}

private fun dayBucket(timestamp: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    return cal.get(Calendar.YEAR) * 10000L + cal.get(Calendar.DAY_OF_YEAR)
}
