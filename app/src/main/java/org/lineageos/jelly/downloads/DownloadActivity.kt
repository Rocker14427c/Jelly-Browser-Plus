/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        val adapter = DownloadAdapter(
            onRowClick = { info -> openDownload(info) },
            onPauseResume = { info ->
                when (info.status) {
                    DownloadEngine.STATUS_RUNNING -> DownloadEngine.pause(this, info.id)
                    else -> DownloadEngine.resume(this, info.id)
                }
            },
            onCancel = { info -> DownloadEngine.cancel(this, info.id) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadEngine.state.collect { list ->
                    adapter.submitList(list)
                    emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
        DownloadEngine.refreshInfos(this)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openDownload(info: DownloadEngine.Info) {
        if (info.status != DownloadEngine.STATUS_COMPLETED) return
        val saved = info.savedUri ?: return

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
}
