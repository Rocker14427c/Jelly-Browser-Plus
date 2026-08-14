/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.suggestions

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import org.lineageos.jelly.R
import java.util.Locale

class SuggestionsAdapter(context: Context) : BaseAdapter(), Filterable {
    private val inflater = LayoutInflater.from(context)
    private var items = listOf<SuggestItem>()
    private val filter = ItemFilter()
    private var queryText: String? = null

    var suggestionProvider: SuggestionProvider? = null

    /**
     * Local history provider. Its matches are always merged into the
     * suggestion list (Chrome-style: your own history first, then the
     * selected online provider), and it alone populates the dropdown when
     * the URL bar is focused with an empty query.
     */
    var historyProvider: SuggestionProvider? = null

    /** Disables history suggestions (incognito). */
    var historyEnabled = true

    override fun getCount() = items.size

    override fun getItem(position: Int) = items[position]

    override fun getItemId(position: Int) = 0L

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val layout = (convertView ?: inflater.inflate(
            R.layout.item_suggestion, parent, false
        )) as LinearLayout
        val suggestion = items[position]
        val title = layout.findViewById<TextView>(R.id.suggest_title)
        val url = layout.findViewById<TextView>(R.id.suggest_url)
        url.isVisible = suggestion.url != null
        queryText?.also { query ->
            title.text = getSpannable(query, suggestion.title)
            url.text = getSpannable(query, suggestion.url ?: "")
        } ?: run {
            title.text = suggestion.title
            url.text = suggestion.url
        }
        return layout
    }

    private fun getSpannable(query: String, text: String): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(text)
        val lcSuggestion = text.lowercase(Locale.getDefault())
        var queryTextPos = lcSuggestion.indexOf(query)
        while (queryTextPos >= 0) {
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                queryTextPos,
                queryTextPos + query.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            queryTextPos = lcSuggestion.indexOf(query, queryTextPos + query.length)
        }
        return spannable
    }

    override fun getFilter(): Filter = filter

    /**
     * Publishes the given items directly (used to show recent history when
     * the URL bar gains focus with an empty query).
     */
    fun publishItems(newItems: List<SuggestItem>) {
        items = newItems
        queryText = null
        notifyDataSetChanged()
    }

    /** Recent history for an empty query (blocking — call off the UI thread). */
    fun recentHistoryItems(): List<SuggestItem> =
        if (historyEnabled) historyProvider?.fetchResults("") ?: emptyList()
        else emptyList()

    private inner class ItemFilter : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val filterResults = FilterResults()
            val raw = constraint?.toString()?.trim() ?: ""
            val query = raw.lowercase(Locale.getDefault())
            val results = if (raw.isEmpty()) {
                // Empty query (e.g. user cleared the text) → recent history.
                if (historyEnabled) historyProvider?.fetchResults("") ?: emptyList()
                else emptyList()
            } else {
                // History matches first, then the selected online provider.
                val merged = LinkedHashMap<String, SuggestItem>()
                if (historyEnabled) {
                    historyProvider?.fetchResults(query)?.forEach {
                        merged[it.url ?: it.title] = it
                    }
                }
                suggestionProvider?.takeUnless {
                    it is SuggestionProvider.None || it is SuggestionProvider.History
                }?.fetchResults(query)?.forEach {
                    merged.putIfAbsent(it.url ?: it.title, it)
                }
                merged.values.take(MAX_RESULTS).toList()
            }
            filterResults.count = results.size
            filterResults.values = results
            queryText = raw.takeIf { it.isNotEmpty() }
            items = results
            return filterResults
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            notifyDataSetChanged()
        }
    }

    companion object {
        private const val MAX_RESULTS = 8
    }
}
