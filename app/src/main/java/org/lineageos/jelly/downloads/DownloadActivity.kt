/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.lineageos.jelly.R
import org.lineageos.jelly.utils.DownloadEngine
import java.io.File

class DownloadActivity : AppCompatActivity(R.layout.activity_downloads) {

    private val toolbar by lazy { findViewById<Toolbar>(R.id.toolbar) }
    private val recycler by lazy { findViewById<RecyclerView>(R.id.downloadRecycler) }
    private val emptyView by lazy { findViewById<View>(R.id.downloadEmpty) }

    private val adapter by lazy {
        DownloadAdapter(
            onRowClick = { info -> openDownload(info) },
            onPauseResume = { info ->
                when (info.status) {
                    DownloadEngine.STATUS_RUNNING -> DownloadEngine.pause(this, info.id)
                    else -> DownloadEngine.resume(this, info.id)
                }
            },
            onCancel = { info -> DownloadEngine.cancel(this, info.id) },
            onLongClick = { info -> showActionSheet(info) }
        ).apply {
            onSelectionChanged = { if (isSelectMode()) updateSelectTitle() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // Back exits selection mode first (Chrome behavior).
        onBackPressedDispatcher.addCallback(this) {
            if (adapter.isSelectMode()) exitSelectMode() else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadEngine.state.collect { list ->
                    adapter.submitList(list)
                    emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    if (adapter.isSelectMode()) updateSelectTitle()
                }
            }
        }
        DownloadEngine.refreshInfos(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_downloads, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_download_select).isVisible = !adapter.isSelectMode()
        menu.findItem(R.id.menu_download_delete_selected).isVisible = adapter.isSelectMode()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (adapter.isSelectMode()) exitSelectMode() else finish()
                true
            }

            R.id.menu_download_select -> {
                if (adapter.isSelectMode()) exitSelectMode() else enterSelectMode()
                true
            }

            R.id.menu_download_delete_selected -> {
                confirmDeleteSelected()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun enterSelectMode() {
        adapter.setSelectMode(true)
        invalidateOptionsMenu()
        toolbar.setNavigationIcon(R.drawable.ic_close_24dp)
        updateSelectTitle()
    }

    private fun exitSelectMode() {
        adapter.setSelectMode(false)
        invalidateOptionsMenu()
        toolbar.setNavigationIcon(null)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.title = getString(R.string.menu_downloads)
    }

    private fun updateSelectTitle() {
        val n = adapter.selectedCount()
        toolbar.title = if (n > 0) getString(R.string.download_selected_count, n)
        else getString(R.string.download_select_hint)
    }

    private fun confirmDeleteSelected() {
        val n = adapter.selectedCount()
        if (n == 0) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.download_delete_confirm_title)
            .setMessage(getString(R.string.download_delete_selected_confirm, n))
            .setPositiveButton(R.string.download_action_delete) { _, _ ->
                DownloadEngine.state.value
                    .filter { adapter.isSelected(it.id) }
                    .forEach { DownloadEngine.delete(this, it.id) }
                exitSelectMode()
                Toast.makeText(this, R.string.download_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDownload(info: DownloadEngine.Info) {
        // Tapping a row that isn't finished yet used to silently do nothing;
        // give feedback instead.
        if (info.status != DownloadEngine.STATUS_COMPLETED) {
            val message = when (info.status) {
                DownloadEngine.STATUS_RUNNING, DownloadEngine.STATUS_QUEUED ->
                    R.string.download_still_running
                DownloadEngine.STATUS_PAUSED -> R.string.download_still_paused
                else -> R.string.download_open_failed
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        val saved = info.savedUri
        if (saved == null) {
            Toast.makeText(this, R.string.download_preparing, Toast.LENGTH_SHORT).show()
            return
        }

        // Resolve the real type from the file name/URL so the right apps get
        // offered: .apk -> package installer, .pdf -> PDF readers, etc.
        // (Previously a generic *&#47;* type meant the package installer never
        // appeared in the chooser at all.)
        val type = DownloadEngine.guessMime(info.fileName, info.url, info.mimeType)

        val intent = try {
            val uri = Uri.parse(saved)
            when (uri.scheme) {
                "content" -> Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                else -> {
                    val file = File(saved)
                    val contentUri = FileProvider.getUriForFile(
                        this, "${application.packageName}.fileprovider", file
                    )
                    Intent(Intent.ACTION_VIEW).setDataAndType(contentUri, type)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.putExtra(Intent.EXTRA_TITLE, info.fileName)

        try {
            val handlers = packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY
            )
            when {
                // One dedicated handler (e.g. only the package installer or a
                // single PDF reader): open it directly.
                handlers.size == 1 -> startActivity(intent)

                // APK with several handlers: launch the view directly — the
                // system shows the open-with dialog with Package installer
                // (the package-archive handler) at the top.
                type == "application/vnd.android.package-archive" -> startActivity(intent)

                // Several matching apps: show the chooser so the right one is
                // one tap away (Chrome behavior).
                else -> startActivity(Intent.createChooser(intent, info.fileName))
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Long-press action sheet: Rename / Copy download link / Delete (red).
     */
    private fun showActionSheet(info: DownloadEngine.Info) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(
            R.layout.sheet_download_actions, LinearLayout(this)
        )
        view.findViewById<View>(R.id.downloadSheetRename).setOnClickListener {
            sheet.dismiss()
            renameDownload(info)
        }
        view.findViewById<View>(R.id.downloadSheetCopy).setOnClickListener {
            sheet.dismiss()
            copyLink(info)
        }
        view.findViewById<View>(R.id.downloadSheetDelete).setOnClickListener {
            sheet.dismiss()
            deleteDownload(info)
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun renameDownload(info: DownloadEngine.Info) {
        val input = android.widget.EditText(this).apply {
            setText(info.fileName)
            setSelection(info.fileName.length)
            isSingleLine = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.download_rename_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) DownloadEngine.rename(this, info.id, name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyLink(info: DownloadEngine.Info) {
        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
            android.content.ClipData.newPlainText("URL", info.url)
        )
        Toast.makeText(this, R.string.download_link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun deleteDownload(info: DownloadEngine.Info) {
        // Deleting also removes the saved file — always confirm first.
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.download_delete_confirm_title)
            .setMessage(getString(R.string.download_delete_confirm_message, info.fileName))
            .setPositiveButton(R.string.download_action_delete) { _, _ ->
                DownloadEngine.delete(this, info.id)
                Toast.makeText(this, R.string.download_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
