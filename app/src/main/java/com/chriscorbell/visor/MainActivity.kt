package com.chriscorbell.visor

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject

/** visor: every machine's health, and the Camera, on the desk display all day. */
class MainActivity : Activity(), Beszel.Listener, FeedPlayer.Listener {
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("visor", Context.MODE_PRIVATE) }
    private val fleet = Fleet()

    private lateinit var theme: Theme
    private lateinit var root: FrameLayout
    private lateinit var dashboard: Dashboard
    private lateinit var player: FeedPlayer
    private lateinit var hub: Beszel
    private lateinit var updater: Updater
    private var launcher: Launcher? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var link = Beszel.Link.CONNECTING

    /** Machines with a sample the screen hasn't shown yet. */
    private val fresh = HashSet<Node>()
    private var logRevision = -1
    private var beat = false

    /**
     * Everything on screen changes on this one beat, twice a second, so the
     * tablet draws a single frame for it. Each machine's sample arrives at its
     * own moment in the second; drawing each one as it came would mean a
     * dozen full frames a second.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            beat = !beat
            if (beat) {
                for (tile in dashboard.machines) {
                    val node = tile.node ?: continue
                    // A machine that went quiet between the hub's status updates turns stale.
                    if (node in fresh || !node.live) tile.bind(node)
                }
                fresh.clear()
                dashboard.fleetTile.bind(fleet)
                dashboard.header.invalidate()
            }
            if (fleet.revision != logRevision) {
                logRevision = fleet.revision
                dashboard.logTile.bind(fleet)
            }
            dashboard.logTile.pulse(beat)
            dashboard.camera.pulse(beat)
            dashboard.machines.forEach { it.pulse(beat) }
            ui.postDelayed(this, 500 - System.currentTimeMillis() % 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StayInFront.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)

        theme = Theme(this)
        player = FeedPlayer(BuildConfig.FEED_URL, this, playAudio = false)
        hub = Beszel(BuildConfig.HUB_URL, ::credentials, this, Fleet.ORDER::contains)
        updater = Updater(applicationContext)

        val surface = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = player.attach(holder.surface)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = player.detach()
            })
        }
        dashboard = Dashboard(this, theme, surface)
        dashboard.header.setOnLongClickListener {
            openLauncher()
            true
        }
        root = FrameLayout(this).apply { addView(dashboard) }
        setContentView(root)
        fleet.log("visor", "BOOT // BUILD ${BuildConfig.VERSION_NAME}", Tone.INFO)
        refresh()

        // A home screen has nowhere to go back to.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                closeLauncher()
            }
        }
    }

    /**
     * Android 16 ignores visor's landscape request until a boot script lets
     * the display honor it, which can happen after visor has started. Asking
     * again once that's done turns the screen.
     */
    private val landscape = object : Runnable {
        override fun run() {
            if (resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ui.postDelayed(this, 5_000)
        }
    }

    override fun onStart() {
        super.onStart()
        hideSystemBars()
        ui.post(landscape)
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "visor").apply { acquire() }
        player.start()
        hub.start()
        updater.start()
        ui.post(heartbeat)
    }

    override fun onStop() {
        ui.removeCallbacks(landscape)
        ui.removeCallbacks(heartbeat)
        updater.stop()
        hub.stop()
        player.stop()
        wifiLock?.release()
        wifiLock = null
        closeLauncher()
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Set on the tablet over adb, never built into the app: see the README. */
    private fun credentials(): Beszel.Credentials? {
        val email = prefs.getString(PREF_EMAIL, null)
        val password = prefs.getString(PREF_PASSWORD, null)
        return if (email.isNullOrBlank() || password.isNullOrBlank()) null else Beszel.Credentials(email, password)
    }

    // ---- hub -------------------------------------------------------------------

    override fun onLink(link: Beszel.Link) {
        ui.post { showLink(link) }
    }

    private fun showLink(link: Beszel.Link) {
        val was = this.link
        this.link = link
        if (link != was) {
            when {
                link == Beszel.Link.LIVE -> fleet.log("hub", "LINK ESTABLISHED", Tone.GOOD)
                was == Beszel.Link.LIVE -> fleet.log("hub", "LINK LOST", Tone.ALERT)
                link == Beszel.Link.NO_CREDENTIALS -> fleet.log("hub", "NO CREDENTIALS SET", Tone.ALERT)
            }
        }
        dashboard.header.link = link
        dashboard.logTile.bind(fleet)
    }

    override fun onSystems(systems: List<SystemRecord>) {
        ui.post {
            fleet.replace(systems)
            refresh()
        }
    }

    override fun onSystem(system: SystemRecord) {
        ui.post {
            fleet.update(system)
            refresh()
        }
    }

    override fun onSystemDeleted(id: String) {
        ui.post {
            fleet.remove(id)
            refresh()
        }
    }

    override fun onMetrics(id: String, stats: JSONObject, info: JSONObject) {
        ui.post {
            fleet.sample(id, stats, info)?.let(fresh::add)
        }
    }

    /** Puts every machine in its slot and redraws the summary panels. */
    private fun refresh() {
        val slots = fleet.slots(dashboard.machines.size)
        dashboard.machines.forEachIndexed { i, tile ->
            slots[i]?.let(tile::bind) ?: tile.clear(Fleet.ORDER.getOrNull(i))
        }
        dashboard.fleetTile.bindNow(fleet)
        dashboard.logTile.bind(fleet)
        dashboard.header.online = fleet.online
        dashboard.header.total = fleet.nodes.size
        dashboard.header.invalidate()
    }

    // ---- camera ----------------------------------------------------------------

    override fun onState(state: FeedState) {
        ui.post { dashboard.camera.setState(state) }
    }

    override fun onVideoSize(width: Int, height: Int) = Unit

    // ---- launcher --------------------------------------------------------------

    private fun openLauncher() {
        if (launcher != null) return
        launcher = Launcher(this, theme) { closeLauncher() }.also {
            it.alpha = 0f
            root.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            it.animate().alpha(1f).setDuration(160).start()
        }
    }

    private fun closeLauncher() {
        val view = launcher ?: return
        launcher = null
        view.animate().alpha(0f).setDuration(140).withEndAction { root.removeView(view) }.start()
    }

    private fun hideSystemBars() {
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val PREF_EMAIL = "email"
        const val PREF_PASSWORD = "password"
    }
}
