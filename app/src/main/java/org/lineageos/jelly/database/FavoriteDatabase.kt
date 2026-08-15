/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.lineageos.jelly.dao.FavoriteDao
import org.lineageos.jelly.model.Favorite

@Database(entities = [Favorite::class], version = 4)
abstract class FavoriteDatabase : RoomDatabase() {
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate table with auto incrementing id column.
                db.execSQL("CREATE TABLE favorites_new (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, url TEXT, color INTEGER)")
                // Copy data
                db.execSQL("INSERT INTO favorites_new (title, url, color) SELECT title, url, color FROM favorites")
                // Remove old table
                db.execSQL("DROP TABLE favorites")
                // Rename new table
                db.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Make _id, title and url columns not nullable
                db.execSQL("CREATE TABLE favorites_new (_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, color INTEGER NOT NULL)")
                // Copy data
                db.execSQL("INSERT INTO favorites_new (_id, title, url, color) SELECT _id, title, url, color FROM favorites")
                // Remove old table
                db.execSQL("DROP TABLE favorites")
                // Rename new table
                db.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Optional favicon (site icon) per favorite.
                db.execSQL("ALTER TABLE favorites ADD COLUMN favicon BLOB")
            }
        }

        // Singleton prevents multiple instances of database opening at the
        // same time.
        @Volatile
        private var INSTANCE: FavoriteDatabase? = null

        fun getDatabase(context: Context): FavoriteDatabase {
            // if the INSTANCE is not null, then return it,
            // if it is, then create the database
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext, FavoriteDatabase::class.java, "FavoriteDatabase"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = instance
                // return instance
                instance
            }
        }
    }

    abstract fun favoriteDao(): FavoriteDao
}
