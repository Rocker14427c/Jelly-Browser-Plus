/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.lineageos.jelly.model.DownloadEntry
import org.lineageos.jelly.model.DownloadSegment

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY _id DESC")
    fun getAll(): Flow<List<DownloadEntry>>

    @Query("SELECT * FROM downloads ORDER BY _id DESC")
    fun getEntriesSnapshot(): List<DownloadEntry>

    @Query("SELECT * FROM downloads WHERE _id = :id")
    fun getEntry(id: Long): DownloadEntry?

    @Query("SELECT * FROM download_segments WHERE downloadId = :id ORDER BY segmentIndex")
    fun getSegments(id: Long): List<DownloadSegment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertEntry(entry: DownloadEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSegments(segments: List<DownloadSegment>)

    @Query("UPDATE downloads SET status = :status WHERE _id = :id")
    fun updateStatus(id: Long, status: String)

    @Query(
        "UPDATE downloads SET totalBytes = :total, savedUri = :savedUri," +
        " status = :status, completedAt = :completedAt WHERE _id = :id"
    )
    fun updateEntry(
        id: Long, total: Long, savedUri: String?, status: String, completedAt: Long?
    )

    @Query("UPDATE download_segments SET done = :done WHERE downloadId = :id AND segmentIndex = :index")
    fun updateSegmentDone(id: Long, index: Int, done: Long)

    @Query("DELETE FROM download_segments WHERE downloadId = :id")
    fun deleteSegments(id: Long)

    @Query("DELETE FROM downloads WHERE _id = :id")
    fun deleteEntry(id: Long)

    /** Stale-state recovery: downloads left "running" by a killed process. */
    @Query("UPDATE downloads SET status = 'paused' WHERE status = 'running'")
    fun markRunningAsPaused()

    @Query("DELETE FROM downloads")
    fun deleteAll()

    @Query("DELETE FROM download_segments")
    fun deleteAllSegments()
}
