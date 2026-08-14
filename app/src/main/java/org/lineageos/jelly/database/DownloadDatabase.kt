/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.lineageos.jelly.dao.DownloadDao
import org.lineageos.jelly.model.DownloadEntry
import org.lineageos.jelly.model.DownloadSegment

@Database(
    entities = [DownloadEntry::class, DownloadSegment::class],
    version = 1,
    exportSchema = true
)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        fun getDatabase(context: Context): DownloadDatabase =
            INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext, DownloadDatabase::class.java,
                    "DownloadDatabase"
                ).build()
                INSTANCE = instance
                instance
            }
    }
}
