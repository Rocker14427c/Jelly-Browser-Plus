/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.downloads

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.lineageos.jelly.R
import org.lineageos.jelly.utils.DownloadEngine

class DownloadAdapter(
    private val onRowClick: (DownloadEngine.Info) -> Unit,
    private val onPauseResume: (DownloadEngine.Info) -> Unit,
    private val onCancel: (DownloadEngine.Info) -> Unit,
    private val onLongClick: (DownloadEngine.Info) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.VH>() {

    /** Fired with the selected count whenever selection changes. */
    var onSelectionChanged: ((Int) -> Unit) = {}

    private var items: List<DownloadEngine.Info> = emptyList()

    /** Multi-select mode: rows show a check and taps toggle selection. */
    private var selectMode = false
    private var selected: Set<Long> = emptySet()

    fun isSelectMode() = selectMode

    fun setSelectMode(mode: Boolean) {
        selectMode = mode
        if (!mode) selected = emptySet()
        notifyDataSetChanged()
    }

    fun isSelected(id: Long) = id in selected

    fun selectedCount() = selected.size

    /** Toggles [id] and returns whether it is now selected. */
    fun toggleSelected(id: Long): Boolean {
        selected = if (id in selected) selected - id else selected + id
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
        return id in selected
    }

    fun submitList(list: List<DownloadEngine.Info>) {
        items = list
        // Drop selections for downloads that disappeared mid-selection.
        selected = selected.filterTo(HashSet()) { id ->
            list.any { it.id == id }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.downloadName)
        private val info: TextView = view.findViewById(R.id.downloadInfo)
        private val progress: LinearProgressIndicator = view.findViewById(R.id.downloadProgress)
        private val pauseResume: ImageButton = view.findViewById(R.id.downloadPauseResume)
        private val cancel: ImageButton = view.findViewById(R.id.downloadCancel)
        private val selectedIcon: ImageView = view.findViewById(R.id.downloadSelected)

        fun bind(d: DownloadEngine.Info) {
            name.text = d.fileName
            val statusLine = when (d.status) {
                DownloadEngine.STATUS_RUNNING -> buildString {
                    append(DownloadEngine.formatBytes(d.bytesDone))
                    if (d.totalBytes > 0) {
                        append(" / ").append(DownloadEngine.formatBytes(d.totalBytes))
                    }
                    if (d.speedBps > 0) {
                        append("  ·  ").append(DownloadEngine.formatBytes(d.speedBps)).append("/s")
                    }
                }
                DownloadEngine.STATUS_PAUSED -> itemView.context.getString(
                    R.string.download_status_paused,
                    DownloadEngine.formatBytes(d.bytesDone),
                    DownloadEngine.formatBytes(d.totalBytes.coerceAtLeast(d.bytesDone))
                )
                DownloadEngine.STATUS_COMPLETED -> itemView.context.getString(
                    R.string.download_status_completed,
                    DownloadEngine.formatBytes(d.totalBytes.coerceAtLeast(d.bytesDone))
                )
                DownloadEngine.STATUS_FAILED -> itemView.context.getString(
                    R.string.download_status_failed,
                    DownloadEngine.formatBytes(d.bytesDone)
                )
                else -> itemView.context.getString(R.string.download_status_queued)
            }
            info.text = statusLine

            val isRunning = d.status == DownloadEngine.STATUS_RUNNING
            val isFinished = d.status == DownloadEngine.STATUS_COMPLETED
            val isQueued = d.status == DownloadEngine.STATUS_QUEUED

            progress.isVisible = !isFinished
            if (isFinished) {
                progress.isIndeterminate = false
                progress.progress = 100
            } else if (d.progress >= 0) {
                progress.isIndeterminate = false
                progress.progress = d.progress
            } else {
                progress.isIndeterminate = true
            }

            pauseResume.isVisible = !isFinished && !isQueued
            pauseResume.setImageResource(
                if (isRunning) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp
            )
            pauseResume.contentDescription = itemView.context.getString(
                if (isRunning) R.string.download_pause else R.string.download_resume
            )
            cancel.isVisible = !isFinished
            cancel.contentDescription = itemView.context.getString(R.string.download_cancel)

            // In multi-select mode taps toggle the check instead of opening;
            // pause/resume/cancel are hidden to avoid accidental taps.
            selectedIcon.isVisible = selectMode
            selectedIcon.setImageResource(
                if (isSelected(d.id)) R.drawable.ic_check_circle else R.drawable.ic_check_circle_off
            )
            pauseResume.isVisible = !selectMode && !isFinished && !isQueued
            cancel.isVisible = !selectMode && !isFinished
            pauseResume.setOnClickListener { onPauseResume(d) }
            cancel.setOnClickListener { onCancel(d) }
            itemView.setOnClickListener {
                if (selectMode) {
                    toggleSelected(d.id)
                } else {
                    onRowClick(d)
                }
            }
            // Long-press opens the action sheet (rename / copy link / delete)
            // — only outside selection mode.
            itemView.setOnLongClickListener {
                if (selectMode) {
                    toggleSelected(d.id)
                } else {
                    onLongClick(d)
                }
                true
            }
        }
    }
}
