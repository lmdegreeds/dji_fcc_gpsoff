package com.dji.fccgpsoff

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Optional foreground service that hosts the network [DiagServer] on :8899,
 * independent of the FCC keepalive. Toggle it only when you want to drive the
 * app / read logs over the network; leave it off for normal use.
 */
class DiagService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ForegroundServices.enter(this, NOTIF_ID, notif())
        DiagServer.start(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        DiagServer.stop()
        super.onDestroy()
    }

    private fun notif(): Notification = ForegroundServices.notification(
        this, "duml_diag", "Diag server",
        t("Веб-дашборд на :${DiagServer.PORT}", "Diag server on :${DiagServer.PORT}"),
        t("Сетевая диагностика включена", "Network diagnostics enabled"),
        android.R.drawable.ic_menu_view
    )

    companion object {
        private const val NOTIF_ID = 1002
        fun start(ctx: Context) = ForegroundServices.launch(ctx, DiagService::class.java)
        fun stop(ctx: Context) = ForegroundServices.stop(ctx, DiagService::class.java)
    }
}
