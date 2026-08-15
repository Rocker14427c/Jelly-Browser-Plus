/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly

import android.app.Application
import com.google.android.material.color.DynamicColors
import org.lineageos.jelly.database.FavoriteDatabase
import org.lineageos.jelly.database.HistoryDatabase
import org.lineageos.jelly.repository.FavoriteRepository
import org.lineageos.jelly.repository.HistoryRepository
import org.lineageos.jelly.utils.DownloadEngine
import org.lineageos.jelly.utils.UserFilters

class JellyApplication : Application() {
    private val historyDatabase by lazy { HistoryDatabase.getDatabase(this) }
    val historyRepository by lazy { HistoryRepository(historyDatabase.historyDao()) }

    private val favoriteDatabase by lazy { FavoriteDatabase.getDatabase(this) }
    val favoriteRepository by lazy { FavoriteRepository(favoriteDatabase.favoriteDao()) }

    override fun onCreate() {
        super.onCreate()

        // Observe dynamic colors changes
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Downloads left "running" by a killed process become resumable.
        DownloadEngine.recoverStale(this)

        // User blocking filters ("mark as ad" / "block element").
        UserFilters.init(this)
    }
}
