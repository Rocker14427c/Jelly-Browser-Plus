/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A download managed by the in-app download engine. */
@Entity(tableName = "downloads", indices = [Index(value = ["status"])])
data class DownloadEntry(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "_id") val id: Long,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "fileName") val fileName: String,
    @ColumnInfo(name = "mimeType") val mimeType: String?,
    @ColumnInfo(name = "totalBytes") val totalBytes: Long,
    /**
     * Where the data is written. Either a MediaStore content:// URI
     * (API 29+, public Downloads folder) or an absolute file path
     * (pre-Android-10 fallback / app-private storage).
     */
    @ColumnInfo(name = "savedUri") val savedUri: String?,
    /** queued | running | paused | completed | failed */
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "completedAt") val completedAt: Long?
)
