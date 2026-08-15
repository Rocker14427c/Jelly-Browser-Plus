/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.history

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.jelly.MainActivity
import org.lineageos.jelly.R
import org.lineageos.jelly.model.History
import org.lineageos.jelly.utils.UiUtils
import org.lineageos.jelly.viewmodels.HistoryViewModel
import java.util.Calendar

class HistoryActivity : AppCompatActivity(R.layout.activity_history) {
    // View models
    private val model: HistoryViewModel by viewModels()

    private var currentAll: List<History> = emptyList()
    private var searchQuery: String? = null

    // Views
    private val historyEmptyLayout by lazy { findViewById<View>(R.id.historyEmptyLayout) }
    private val historyListView by lazy { findViewById<RecyclerView>(R.id.historyListView) }
    private val toolbar by lazy { findViewById<Toolbar>(R.id.toolbar) }

    private val adapter by lazy {
        HistoryAdapter(this).apply {
            setHasStableIds(true)
        }
    }

    private val adapterDataObserver: AdapterDataObserver = object : AdapterDataObserver() {
        override fun onChanged() {
            updateHistoryView(adapter.itemCount == 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        historyListView.layoutManager = LinearLayoutManager(this)
        historyListView.addItemDecoration(HistoryAnimationDecorator(this))
        historyListView.itemAnimator = DefaultItemAnimator()
        historyListView.adapter = adapter
        val helper =
            ItemTouchHelper(HistoryCallBack(this, object : HistoryCallBack.OnSwipeListener {
                override fun onItemSwiped(id: Long) {
                    lifecycleScope.launch {
                        val entry = runCatching { model.get(id) }.getOrNull() ?: return@launch
                        model.delete(id)
                        Snackbar.make(
                            findViewById(R.id.coordinatorLayout),
                            R.string.history_snackbar_item_deleted,
                            Snackbar.LENGTH_LONG
                        ).setAction(R.string.history_snackbar_item_deleted_message) {
                            model.insert(entry)
                        }.show()
                    }
                }
            }))
        helper.attachToRecyclerView(historyListView)
        val listTop = historyListView.top
        historyListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val elevate = recyclerView.getChildAt(0) != null &&
                        recyclerView.getChildAt(0).top < listTop
                toolbar.elevation = if (elevate) UiUtils.dpToPx(
                    resources,
                    resources.getDimension(R.dimen.toolbar_elevation)
                ) else 0f
            }
        })

        adapter.registerAdapterDataObserver(adapterDataObserver)
        adapter.onRowClick = {
            val intent = Intent(this, MainActivity::class.java).apply {
                data = it.url.toUri()
            }
            startActivity(intent)
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.history.collectLatest {
                    currentAll = it
                    // Keep any active search query applied to fresh data.
                    val query = searchQuery
                    if (query.isNullOrBlank()) {
                        groupAndSubmit(it)
                    } else {
                        groupAndSubmit(model.search(query))
                    }
                }
            }
        }
    }

    public override fun onDestroy() {
        adapter.unregisterAdapterDataObserver(adapterDataObserver)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)

        // Chrome-style history search.
        val searchItem = menu.findItem(R.id.menu_history_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.history_search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String) = false

            override fun onQueryTextChange(newText: String): Boolean {
                searchQuery = newText
                val expected = newText
                lifecycleScope.launch {
                    delay(200) // debounce typing
                    if (searchQuery != expected) return@launch
                    if (newText.isBlank()) {
                        groupAndSubmit(currentAll)
                    } else {
                        groupAndSubmit(model.search(newText))
                    }
                }
                return true
            }
        })
        return true
    }

    /** Groups entries under Today / Yesterday / date headers and submits. */
    private fun groupAndSubmit(list: List<History>) {
        val grouped = list.groupedForUi { timestamp ->
            headerLabel(timestamp)
        }
        adapter.submitList(grouped)
        val empty = grouped.isEmpty()
        historyListView.isVisible = !empty
        historyEmptyLayout.isVisible = empty
    }

    private fun headerLabel(timestamp: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        if (target.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        ) {
            return getString(R.string.history_header_today)
        }
        today.add(Calendar.DAY_OF_YEAR, -1)
        if (target.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        ) {
            return getString(R.string.history_header_yesterday)
        }
        return java.text.SimpleDateFormat(
            getString(R.string.history_header_date_format),
            java.util.Locale.getDefault()
        ).format(java.util.Date(timestamp))
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        R.id.menu_history_delete -> {
            AlertDialog.Builder(this)
                .setTitle(R.string.history_delete_title)
                .setMessage(R.string.history_delete_message)
                .setPositiveButton(R.string.history_delete_positive) { _, _ -> deleteAll() }
                .setNegativeButton(android.R.string.cancel) { d: DialogInterface, _ -> d.dismiss() }
                .show()
            true
        }

        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    private fun updateHistoryView(empty: Boolean) {
        historyEmptyLayout.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun deleteAll() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.history_delete_title)
            .setView(R.layout.history_deleting_dialog)
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            deleteAllHistory(dialog)
        }
    }

    private suspend fun deleteAllHistory(dialog: AlertDialog) {
        model.deleteAll()
        withContext(Dispatchers.Main) {
            delay(200)
            dialog.dismiss()
        }
    }
}
