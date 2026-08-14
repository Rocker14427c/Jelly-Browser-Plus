/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 *
 * Foreground service that keeps the process alive while downloads run, and
 * shows a progress notification for the in-app download engine.
 */
package org.lineageos.jelly.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.lineageos.jelly.R
import org.lineageos.jelly.utils.DownloadEngine

class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DownloadEngine.hasActive()) {
            startForeground(NOTIFICATION_ID, buildNotification(this))
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 7

        /** Starts/stops the service to mirror the engine's active state. */
        fun sync(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            try {
                if (DownloadEngine.hasActive()) {
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                }
            } catch (_: Exception) {
                // App in background w/o FGS allowance, or already stopped.
            }
        }

        /** Refreshes the notification progress (call from the engine). */
        fun updateNotification(context: Context) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (DownloadEngine.hasActive()) {
                    nm.notify(NOTIFICATION_ID, buildNotification(context))
                }
            }
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.download_notification_channel) }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        fun buildNotification(context: Context): Notification {
            createChannel(context)
            val running = DownloadEngine.state.value.filter {
                it.status == DownloadEngine.STATUS_RUNNING
            }
            val total = running.sumOf { it.totalBytes.coerceAtLeast(0) }
            val done = running.sumOf { it.bytesDone.coerceAtLeast(0) }
            val speed = running.sumOf { it.speedBps }
            val contentText = if (running.size == 1) {
                val d = running.first()
                DownloadEngine.formatBytes(d.bytesDone) + " / " +
                    DownloadEngine.formatBytes(d.totalBytes.coerceAtLeast(d.bytesDone)) +
                    " · " + DownloadEngine.formatBytes(speed) + "/s"
            } else {
                context.getString(R.string.download_notification_multi, running.size)
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(context.getString(R.string.download_notification_title))
                .setContentText(contentText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        context, 0,
                        Intent(context, DownloadActivity::class.java),
                        android.app.PendingIntent.FLAG_IMMUTABLE or
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .apply {
                    if (total > 0) {
                        setProgress(total.toInt(), done.toInt(), total <= done)
                    } else {
                        setProgress(0, 0, true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setForegroundServiceBehavior(
                            NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                        )
                    }
                }
                .build()
        }
    }
}
