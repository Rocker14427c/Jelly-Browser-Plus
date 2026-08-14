/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import android.content.Intent
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
        val intent = try {
            val uri = Uri.parse(saved)
            when (uri.scheme) {
                "content" -> Intent(Intent.ACTION_VIEW).setDataAndType(
                    uri, info.mimeType ?: "*/*"
                )
                else -> {
                    val file = File(saved)
                    val contentUri = FileProvider.getUriForFile(
                        this, "${application.packageName}.fileprovider", file
                    )
                    Intent(Intent.ACTION_VIEW).setDataAndType(
                        contentUri, info.mimeType ?: "*/*"
                    )
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, info.fileName))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
