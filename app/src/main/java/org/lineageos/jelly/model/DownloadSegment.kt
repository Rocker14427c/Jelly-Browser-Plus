/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One parallel segment of a download. Segmented downloads write different
 * byte ranges of the file concurrently (Range requests), which is what makes
 * the in-app downloader considerably faster than a single connection.
 *
 * [start]..[end] is the absolute byte range this segment covers
 * (end == -1 means "until EOF", used for single-connection downloads).
 * [done] counts the bytes already written by this segment, so a paused
 * download can be resumed with `Range: bytes={start + done}-{end}`.
 */
@Entity(
    tableName = "download_segments",
    primaryKeys = ["downloadId", "segmentIndex"],
    indices = [Index(value = ["downloadId"])]
)
data class DownloadSegment(
    @ColumnInfo(name = "downloadId") val downloadId: Long,
    @ColumnInfo(name = "segmentIndex") val segmentIndex: Int,
    @ColumnInfo(name = "start") val start: Long,
    @ColumnInfo(name = "end") val end: Long,
    @ColumnInfo(name = "done") val done: Long
)
