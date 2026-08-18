package com.dji.fccgpsoff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Shared foreground-service plumbing, so the three services (keepalive, diag,
 * overlay) don't each re-implement channel creation, notification building, the
 * `startForeground` FGS-type dance, and the `startForegroundService`-vs-`startService`
 * launch branch — and so all three get the same crash-safety.
 */
object ForegroundServices {

    /** Create the (idempotent) notification channel and build an ongoing notification. */
    fun notification(
        service: Service, channelId: String, channelName: String,
        title: String, text: String, smallIcon: Int
    ): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            service.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_MIN)
                )
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(service, channelId)
        else @Suppress("DEPRECATION") Notification.Builder(service)
        b.setContentTitle(title).setContentText(text)
            .setSmallIcon(smallIcon).setOngoing(true)
            .setContentIntent(openAppIntent(service))   // tap opens the app window
        // Show the app icon in the notification (small icons are alpha-only, so the
        // colourful launcher goes in the large-icon slot).
        runCatching {
            android.graphics.BitmapFactory.decodeResource(service.resources, R.mipmap.ic_launcher)
        }.getOrNull()?.let { b.setLargeIcon(it) }
        return b.build()
    }

    /** PendingIntent that brings MainActivity to the front (notification tap target). */
    private fun openAppIntent(service: Service): PendingIntent {
        val i = Intent(service, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            service, 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Enter the foreground with the `specialUse` FGS type on API 34+ (where the
     * type is real and the app holds FOREGROUND_SERVICE_SPECIAL_USE), and the plain
     * 2-arg form below that — passing the type on 29–33 can throw or mis-declare.
     *
     * Wrapped so a background-start restriction (ForegroundServiceStartNotAllowed
     * exception on Android 12+, e.g. when BootReceiver starts us outside the boot
     * window) is logged instead of crashing the process. Returns true on success.
     */
    fun enter(service: Service, notifId: Int, notif: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(notifId, notif)
        }
        true
    } catch (e: Exception) {
        DiagLog.err("${service.javaClass.simpleName}: startForeground failed — ${e.message}")
        false
    }

    /** Start a service in the foreground (API 26+) or plain (older), crash-safe. */
    fun launch(ctx: Context, cls: Class<out Service>, extras: (Intent.() -> Unit)? = null) {
        val i = Intent(ctx, cls).apply { extras?.invoke(this) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        } catch (e: Exception) {
            DiagLog.err("start ${cls.simpleName} failed — ${e.message}")
        }
    }

    fun stop(ctx: Context, cls: Class<out Service>) {
        runCatching { ctx.stopService(Intent(ctx, cls)) }
    }
}
