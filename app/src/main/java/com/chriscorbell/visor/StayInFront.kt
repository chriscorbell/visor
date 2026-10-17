package com.chriscorbell.visor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock

/**
 * Brings visor back after anything that kills it. Android only starts a home
 * screen when none is running, and the stock launcher always is, because it
 * draws Recents, so after an update or a crash it would take over the screen.
 * The default home app may start activities from the background.
 */
object StayInFront {
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val relaunch = PendingIntent.getActivity(app, 0, visor(app), PendingIntent.FLAG_IMMUTABLE)
            app.getSystemService(AlarmManager::class.java)
                .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, relaunch)
            previous?.uncaughtException(thread, error) ?: Process.killProcess(Process.myPid())
        }
    }

    fun visor(context: Context) =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Reopens visor right after a self-update replaces it. */
    class AfterUpdate : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) context.startActivity(visor(context))
        }
    }
}
