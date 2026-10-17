package com.chriscorbell.visor

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.util.Locale

/**
 * The screen: a header over a 4×4 grid. The Camera takes the top-right 2×2
 * block; machines fill the other cells in order, and the fleet summary and
 * the event log take the last two, under the Camera.
 */
class Dashboard(context: Context, private val theme: Theme, val surface: SurfaceView) : ViewGroup(context) {
    val header = Header(context, theme)
    val machines = List(MACHINE_SLOTS) { MachineTile(context, theme) }
    val fleetTile = FleetTile(context, theme)
    val logTile = LogTile(context, theme)
    val camera = CameraHud(context, theme)
    private val background = theme.fill(Theme.BG)

    init {
        setWillNotDraw(false)
        // The picture first, so everything else draws over it.
        addView(surface)
        addView(header)
        machines.forEach(::addView)
        addView(fleetTile)
        addView(logTile)
        addView(camera)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val margin = theme.dp(12f)
        val gap = theme.dp(8f)
        val headerH = theme.dp(40f)
        place(header, margin, margin * 0.5f, w - margin, margin * 0.5f + headerH)

        val gridTop = margin * 0.5f + headerH + gap
        val cellW = (w - 2 * margin - 3 * gap) / 4
        val cellH = (h - gridTop - margin - 3 * gap) / 4
        fun cell(col: Int, row: Int) = RectF(
            margin + col * (cellW + gap), gridTop + row * (cellH + gap),
            margin + col * (cellW + gap) + cellW, gridTop + row * (cellH + gap) + cellH,
        )

        val slots = SLOTS.map { (col, row) -> cell(col, row) }
        machines.forEachIndexed { i, tile -> place(tile, slots[i]) }
        place(fleetTile, slots[MACHINE_SLOTS])
        place(logTile, slots[MACHINE_SLOTS + 1])

        val cam = RectF(cell(2, 0).left, cell(2, 0).top, cell(3, 1).right, cell(3, 1).bottom)
        place(camera, cam)
        // CameraHud works out its picture area when it gets its size.
        camera.post {
            val v = camera.video
            surface.layout(
                (cam.left + v.left).toInt(), (cam.top + v.top).toInt(),
                (cam.left + v.right).toInt(), (cam.top + v.bottom).toInt(),
            )
        }
    }

    private fun place(view: View, r: RectF) = place(view, r.left, r.top, r.right, r.bottom)

    private fun place(view: View, left: Float, top: Float, right: Float, bottom: Float) {
        val wSpec = MeasureSpec.makeMeasureSpec((right - left).toInt(), MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec((bottom - top).toInt(), MeasureSpec.EXACTLY)
        view.measure(wSpec, hSpec)
        view.layout(left.toInt(), top.toInt(), left.toInt() + view.measuredWidth, top.toInt() + view.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
    }

    companion object {
        const val MACHINE_SLOTS = 10

        /** Grid cells (column, row) outside the Camera's block, in reading order. */
        val SLOTS = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1, 0 to 2, 1 to 2, 2 to 2, 3 to 2, 0 to 3, 1 to 3, 2 to 3, 3 to 3)
    }
}

/**
 * A small menu of the tablet's other apps, opened by holding the header.
 * visor is the home screen, so this is the way out to camview and the rest.
 */
class Launcher(context: Context, private val theme: Theme, private val onClose: () -> Unit) : View(context) {
    private data class Entry(val label: String, val intent: Intent)

    private val entries = APPS.mapNotNull { (label, pkg) ->
        val intent = if (pkg == LAUNCHER3) {
            // The stock launcher only answers as a home screen.
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
                .takeIf { context.packageManager.resolveActivity(it, 0) != null }
        } else {
            context.packageManager.getLaunchIntentForPackage(pkg)
        }
        intent?.let { Entry(label, it) }
    }
    private val dim = theme.fill(Theme.withAlpha(Theme.BG, 215))
    private val title = theme.text(10f, Theme.MUTED, bold = true).apply { letterSpacing = 0.15f }
    private val item = theme.text(16f, Theme.TEXT, bold = true).apply { letterSpacing = 0.1f }
    private val prompt = theme.text(16f, Theme.CYAN, bold = true)
    private val box = RectF()
    private val rowH get() = theme.dp(44f)
    private val closeLater = Runnable { onClose() }

    init {
        postDelayed(closeLater, 15_000)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val bw = theme.dp(360f)
        val bh = theme.dp(48f) + rowH * entries.size + theme.dp(12f)
        box.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        theme.drawPanel(canvas, box)
        val p = theme.dp(16f)
        canvas.drawText("LAUNCH // SELECT TARGET", box.left + p, box.top + theme.dp(26f), title)
        var y = box.top + theme.dp(48f) + rowH * 0.62f
        for (entry in entries) {
            canvas.drawText(">", box.left + p, y, prompt)
            canvas.drawText(entry.label.uppercase(Locale.US), box.left + p + theme.dp(22f), y, item)
            y += rowH
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val row = ((event.y - box.top - theme.dp(48f)) / rowH).toInt()
        val inside = box.contains(event.x, event.y) && event.y > box.top + theme.dp(48f)
        if (inside && row in entries.indices) {
            context.startActivity(entries[row].intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        removeCallbacks(closeLater)
        onClose()
        return true
    }

    private companion object {
        const val LAUNCHER3 = "com.android.launcher3"
        val APPS = listOf(
            "camview" to "com.chriscorbell.camview",
            "Reolink" to "com.mcu.reolink",
            "Firefox" to "org.mozilla.firefox",
            "Launcher" to LAUNCHER3,
            "Settings" to "com.android.settings",
        )
    }
}
