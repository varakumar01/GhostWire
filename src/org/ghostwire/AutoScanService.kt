/*
 * Copyright 2026 Varakumar.
 *
 * This file is part of GhostWire.
 *
 * GhostWire is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GhostWire is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GhostWire.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.ghostwire

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps auto-scan "on forever": a START_STICKY foreground service holding an
 * ongoing notification. It does not drive the NFC controller itself (Android
 * only polls for a foreground activity's reader mode or via the system tag
 * dispatch) — its job is to keep the enabled flag and the stop control alive
 * across app exits. Tapped tags are captured by MainActivity's TECH_DISCOVERED
 * dispatch while this flag is set; the Walrus tab re-arms reader mode for rapid
 * taps while it's in the foreground.
 */
class AutoScanService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification())
        return START_STICKY
    }

    private fun notification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Auto-scan", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AutoScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("GhostWire auto-scan on")
            .setContentText("Tap a card to capture it. Tap Stop to turn off.")
            .setSmallIcon(R.drawable.ic_contactless)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "autoscan"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "org.ghostwire.STOP_AUTOSCAN"
        private const val KEY = "auto_scan"

        private fun prefs(ctx: Context) =
            ctx.getSharedPreferences("ghostwire", Context.MODE_PRIVATE)

        fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY, false)

        private fun setEnabled(ctx: Context, on: Boolean) =
            prefs(ctx).edit().putBoolean(KEY, on).apply()

        fun start(ctx: Context) {
            setEnabled(ctx, true)
            ContextCompat.startForegroundService(ctx, Intent(ctx, AutoScanService::class.java))
        }

        fun stop(ctx: Context) {
            // Routed through the service so it clears the flag and drops the
            // notification in onStartCommand.
            ctx.startService(Intent(ctx, AutoScanService::class.java).setAction(ACTION_STOP))
        }
    }
}
