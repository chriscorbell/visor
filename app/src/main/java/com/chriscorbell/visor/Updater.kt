package com.chriscorbell.visor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs new releases of this app from GitHub Releases on its own.
 *
 * Android 12+ lets an app update itself without a tap once it is the app's
 * installer of record, so only the first update after a manual install asks
 * for confirmation. Android refuses any APK not signed with this app's key.
 */
class Updater(private val context: Context) {
    private val thread = HandlerThread("updater").apply { start() }
    private val handler = Handler(thread.looper)
    private val check = object : Runnable {
        override fun run() {
            runCatching { checkOnce() }.onFailure { Log.w(TAG, "Update check failed", it) }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        // Debug builds are a separate app; releases are never meant for them.
        if (BuildConfig.DEBUG) return
        handler.removeCallbacks(check)
        handler.postDelayed(check, FIRST_CHECK_MS)
    }

    fun stop() = handler.removeCallbacks(check)

    private fun checkOnce() {
        val release = JSONObject(get(BuildConfig.RELEASES_URL).bufferedReader().use { it.readText() })
        val number = release.getString("tag_name").removePrefix(TAG_PREFIX).toIntOrNull() ?: return
        if (number <= BuildConfig.VERSION_CODE) return
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name") == APK_NAME }
            ?: return
        Log.i(TAG, "Installing release $number over ${BuildConfig.VERSION_CODE}")
        install(apk.getString("browser_download_url"))
    }

    private fun install(url: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            get(url).use { input ->
                session.openWrite(APK_NAME, 0, -1).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResult::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", "visor/${BuildConfig.VERSION_CODE}")
        setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        inputStream
    }

    /** The first self-update needs one confirmation; later ones never reach here. */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installed")
                else -> Log.w(TAG, "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }

    private companion object {
        const val TAG = "visor.update"
        const val TAG_PREFIX = "visor-"
        const val APK_NAME = "visor.apk"
        const val FIRST_CHECK_MS = 30_000L
        const val INTERVAL_MS = 15 * 60_000L
    }
}
