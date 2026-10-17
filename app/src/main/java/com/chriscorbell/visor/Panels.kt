package com.chriscorbell.visor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val easeOut = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

/** A panel on the grid. Subclasses draw inside the frame [Theme.drawPanel] gives them. */
abstract class Panel(context: Context, protected val theme: Theme) : View(context) {
    protected val frame = RectF()
    protected val pad get() = theme.dp(12f)
    protected fun dp(v: Float) = theme.dp(v)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = dp(0.5f)
        frame.set(inset, inset, w - inset, h - inset)
    }
}

/**
 * Eases a set of numbers toward new targets, redrawing [view] as it goes.
 * Small changes, which is most of them, jump straight there: animating every
 * once-a-second update would keep the tablet redrawing without pause.
 */
class Tween(private val view: View, size: Int) {
    val shown = FloatArray(size)
    private val from = FloatArray(size)
    private val to = FloatArray(size)
    private var first = true
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 300
        interpolator = easeOut
        addUpdateListener {
            val f = it.animatedValue as Float
            for (i in shown.indices) shown[i] = from[i] + (to[i] - from[i]) * f
            view.invalidate()
        }
    }

    fun target(vararg values: Float) {
        animator.cancel()
        var jump = 0f
        for (i in values.indices) {
            from[i] = shown[i]
            to[i] = values[i]
            jump = maxOf(jump, kotlin.math.abs(values[i] - shown[i]))
        }
        if (first || jump < ANIMATE_FROM) {
            first = false
            System.arraycopy(values, 0, shown, 0, values.size)
            view.invalidate()
        } else {
            animator.start()
        }
    }

    private companion object {
        /** Percentage points: smaller changes aren't animated. */
        const val ANIMATE_FROM = 3f
    }
}

/** One machine: its name, health, four load bars and a minute of CPU history. */
class MachineTile(context: Context, theme: Theme) : Panel(context, theme) {
    var node: Node? = null
        private set
    private val tween = Tween(this, 4)

    private val name = theme.text(15f, Theme.TEXT, bold = true)
    private val role = theme.text(9.5f, Theme.MUTED).apply { letterSpacing = 0.08f }
    private val status = theme.text(10f, Theme.MUTED, align = Paint.Align.RIGHT)
    private val label = theme.text(10f, Theme.MUTED, bold = true)
    private val value = theme.text(12f, Theme.TEXT, align = Paint.Align.RIGHT)
    private val foot = theme.text(10f, Theme.MUTED)
    private val footRight = theme.text(10f, Theme.MUTED, align = Paint.Align.RIGHT)
    private val dot = theme.fill(Theme.GREEN)
    private val lost = theme.text(16f, Theme.MAGENTA, bold = true, align = Paint.Align.CENTER).apply { letterSpacing = 0.2f }
    private val lostEcho = theme.text(16f, Theme.withAlpha(Theme.CYAN, 120), bold = true, align = Paint.Align.CENTER).apply { letterSpacing = 0.2f }
    private val spark = RectF()
    private val gridLine = theme.stroke(Theme.withAlpha(Theme.CYAN, 22), 1f).apply {
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f)
    }
    private val scaleLabel = theme.text(8f, Theme.withAlpha(Theme.MUTED, 200))
    private var blink = false

    /** The heartbeat's half-second beat: a silent machine's warning blinks with it. */
    fun pulse(on: Boolean) {
        val n = node ?: return
        if (n.live) return
        blink = on
        invalidate()
    }

    /** The fleet machine this slot is kept for, while it has no agent reporting. */
    private var awaiting: String? = null

    fun clear(expected: String?) {
        node = null
        awaiting = expected
        invalidate()
    }

    // Text that changes at most once a second, prepared in bind() rather than on every frame.
    private var title = ""
    private var roleText = ""
    private var stateText = ""
    private var stateColor = Theme.MUTED
    private var fourthKey: String? = null
    private var netText = ""
    private var extrasText = ""

    fun bind(next: Node) {
        node = next
        val four = fourth(next)
        fourthKey = four?.first
        title = next.name.uppercase(Locale.US)
        roleText = Fleet.ROLES[next.name] ?: next.gpuName?.uppercase(Locale.US) ?: ""
        stateText = when {
            next.live -> "UP ${uptime(next.uptimeSec)}"
            next.status == Status.UP -> "STALE"
            next.status == Status.DOWN -> "OFFLINE"
            next.status == Status.PAUSED -> "PAUSED"
            else -> "PENDING"
        }
        stateColor = when {
            next.live -> Theme.GREEN
            next.status == Status.UP -> Theme.AMBER
            next.status == Status.DOWN -> Theme.MAGENTA
            else -> Theme.MUTED
        }
        dot.color = stateColor
        dot.setShadowLayer(dp(5f), 0f, 0f, stateColor)
        netText = "↑${rate(next.netUpBps)} ↓${rate(next.netDownBps)}"
        extrasText = buildList {
            if (four?.first != "TMP") next.tempC?.let { add("${it.toInt()}°C") }
            next.load1?.let { add("LA %.2f".format(Locale.US, it)) }
        }.joinToString("  ")
        tween.target(next.cpu, next.mem, next.disk, four?.second ?: 0f)
        invalidate()
    }

    /** The fourth bar: the GPU, else the biggest pool, else the temperature. */
    private fun fourth(n: Node): Pair<String, Float>? = when {
        n.gpu != null -> "GPU" to n.gpu!!
        n.pool != null -> "VOL" to n.pool!!.pct
        n.tempC != null -> "TMP" to n.tempC!!
        else -> null
    }

    override fun onDraw(canvas: Canvas) {
        val n = node ?: return drawEmpty(canvas)
        val live = n.live
        theme.drawPanel(canvas, frame, if (live) Theme.CYAN else if (n.status == Status.DOWN) Theme.MAGENTA else Theme.MUTED)
        val w = width.toFloat()
        val h = height.toFloat()
        val p = pad

        // Name, status and role.
        val top = p + dp(14f)
        name.color = if (live) Theme.TEXT else Theme.MUTED
        canvas.drawText(title, p, top, name)
        val right = w - p - dp(8f)
        canvas.drawText(stateText, right, top, status)
        canvas.drawCircle(right - status.measureText(stateText) - dp(8f), top - dp(3.5f), dp(3f), dot)
        canvas.drawText(roleText, p, top + dp(16f), role)

        if (!live) {
            drawLost(canvas, w, h, n)
            return
        }

        // Load bars.
        val barLeft = p + dp(34f)
        val barRight = w - p - dp(46f)
        var y = top + dp(30f)
        val rows = if (fourthKey != null) 4 else 3
        for (row in 0 until rows) {
            val key = ROW_KEYS[row] ?: fourthKey!!
            val pct = tween.shown[row]
            canvas.drawText(key, p, y + dp(7f), label)
            theme.drawBar(canvas, barLeft, y, barRight - barLeft, dp(7f), pct)
            value.color = Theme.level(pct)
            canvas.drawText(if (key == "TMP") "${pct.toInt()}°" else "${pct.toInt()}%", w - p, y + dp(7.5f), value)
            y += dp(21f)
        }

        // The last minute of CPU, filling the band above the footer. Its scale
        // follows the minute's peak, so a quiet machine isn't a flat line.
        spark.set(p, y - dp(4f), w - p, h - p - dp(16f))
        if (spark.height() > dp(8f)) {
            var peak = 0f
            for (i in 0 until n.cpuHistory.size) peak = maxOf(peak, n.cpuHistory[i])
            val scale = (Math.ceil(peak / 10.0) * 10).toFloat().coerceIn(10f, 100f)
            canvas.drawLine(spark.left, spark.bottom, spark.right, spark.bottom, gridLine)
            canvas.drawLine(spark.left, spark.centerY(), spark.right, spark.centerY(), gridLine)
            theme.drawSparkline(canvas, spark, n.cpuHistory, scale = scale)
            canvas.drawText("CPU 60S /${scale.toInt()}%", spark.left, spark.top + dp(1f), scaleLabel)
        }

        // Network, temperature and load along the bottom.
        val base = h - p
        canvas.drawText(netText, p, base, foot)
        canvas.drawText(extrasText, w - p, base, footRight)
    }

    private companion object {
        val ROW_KEYS = arrayOf<String?>("CPU", "MEM", "DSK", null)
    }

    /** A slot no machine has claimed yet. */
    private fun drawEmpty(canvas: Canvas) {
        theme.drawPanel(canvas, frame, Theme.withAlpha(Theme.MUTED, 120))
        val expected = awaiting
        if (expected != null) {
            val top = pad + dp(14f)
            name.color = Theme.withAlpha(Theme.MUTED, 170)
            canvas.drawText(expected.uppercase(Locale.US), pad, top, name)
            canvas.drawText(Fleet.ROLES[expected] ?: "", pad, top + dp(16f), role)
        }
        role.textAlign = Paint.Align.CENTER
        canvas.drawText(if (expected != null) "AWAITING AGENT" else "NO NODE // SLOT OPEN", width / 2f, height * 0.6f, role)
        role.textAlign = Paint.Align.LEFT
    }

    private fun drawLost(canvas: Canvas, w: Float, h: Float, n: Node) {
        val cy = h * 0.62f
        val text = when (n.status) {
            Status.DOWN, Status.UP -> "SIGNAL LOST"
            Status.PAUSED -> "PAUSED"
            Status.PENDING -> "AWAITING AGENT"
        }
        lost.alpha = if (blink && n.status == Status.DOWN) 110 else 255
        canvas.drawText(text, w / 2 + dp(1.5f), cy + dp(1f), lostEcho)
        canvas.drawText(text, w / 2, cy, lost)
        canvas.drawText("NO TELEMETRY", w / 2, cy + dp(18f), role.apply { textAlign = Paint.Align.CENTER })
        role.textAlign = Paint.Align.LEFT
    }
}

/** The whole fleet at once: how many machines are up, and their combined load. */
class FleetTile(context: Context, theme: Theme) : Panel(context, theme) {
    private val title = theme.text(10f, Theme.MUTED, bold = true).apply { letterSpacing = 0.12f }
    private val big = theme.text(30f, Theme.TEXT, bold = true).also { theme.glow(it, 8f, Theme.CYAN) }
    private val small = theme.text(9.5f, Theme.MUTED).apply { letterSpacing = 0.08f }
    private val label = theme.text(10f, Theme.MUTED, bold = true)
    private val value = theme.text(12f, Theme.TEXT, align = Paint.Align.RIGHT)
    private val foot = theme.text(10f, Theme.MUTED)
    private val footRight = theme.text(10f, Theme.MUTED, align = Paint.Align.RIGHT)
    private val tween = Tween(this, 2)
    private val history = Ring(Node.HISTORY)
    private val spark = RectF()
    private var online = 0
    private var total = 0
    private var memUsed = 0f
    private var memTotal = 0f
    private var up = 0L
    private var down = 0L
    private var lastPush = 0L
    private var countText = "00/00"
    private var netText = ""
    private var ramText = ""

    /** Redraws right away, for when machines come or go. */
    fun bindNow(fleet: Fleet) {
        lastPush = 0L
        bind(fleet)
    }

    fun bind(fleet: Fleet) {
        // Machines report one by one through each second; the summary moves once a second.
        val now = SystemClock.elapsedRealtime()
        if (now - lastPush < 950) return
        lastPush = now
        val nodes = fleet.nodes.values
        val live = nodes.filter { it.live }
        online = fleet.online
        total = nodes.size
        memUsed = live.sumOf { it.memUsedGb.toDouble() }.toFloat()
        memTotal = live.sumOf { it.memTotalGb.toDouble() }.toFloat()
        up = live.sumOf { it.netUpBps }
        down = live.sumOf { it.netDownBps }
        val cpu = if (live.isEmpty()) 0f else live.map { it.cpu }.average().toFloat()
        history.push(cpu)
        countText = "%02d/%02d".format(Locale.US, online, total)
        netText = "↑${rate(up)} ↓${rate(down)}"
        ramText = "${size(memUsed)} / ${size(memTotal)} RAM"
        tween.target(cpu, if (memTotal > 0) memUsed / memTotal * 100f else 0f)
    }

    override fun onDraw(canvas: Canvas) {
        theme.drawPanel(canvas, frame)
        val w = width.toFloat()
        val h = height.toFloat()
        val p = pad
        canvas.drawText("FLEET // AGGREGATE", p, p + dp(10f), title)
        val count = countText
        big.color = if (online == total && total > 0) Theme.TEXT else Theme.AMBER
        canvas.drawText(count, p, p + dp(44f), big)
        canvas.drawText("NODES ONLINE", p + big.measureText(count) + dp(10f), p + dp(44f), small)

        val barLeft = p + dp(34f)
        val barRight = w - p - dp(46f)
        var y = p + dp(56f)
        for ((key, pct) in listOf("CPU" to tween.shown[0], "MEM" to tween.shown[1])) {
            canvas.drawText(key, p, y + dp(7f), label)
            theme.drawBar(canvas, barLeft, y, barRight - barLeft, dp(7f), pct)
            value.color = Theme.level(pct)
            canvas.drawText("${pct.toInt()}%", w - p, y + dp(7.5f), value)
            y += dp(22f)
        }
        spark.set(p, y + dp(2f), w - p, h - p - dp(16f))
        theme.drawSparkline(canvas, spark, history)
        canvas.drawText(netText, p, h - p, foot)
        canvas.drawText(ramText, w - p, h - p, footRight)
    }
}

/** The newest events, oldest at the top, with a blinking cursor under them. */
class LogTile(context: Context, theme: Theme) : Panel(context, theme) {
    private val title = theme.text(10f, Theme.MUTED, bold = true).apply { letterSpacing = 0.12f }
    private val time = theme.text(10f, Theme.withAlpha(Theme.MUTED, 200))
    private val source = theme.text(10f, Theme.TEXT)
    private val line = theme.text(10f, Theme.CYAN)
    private val cursor = theme.fill(Theme.CYAN)
    private var events: List<Event> = emptyList()
    private var cursorOn = true

    fun bind(fleet: Fleet) {
        events = fleet.events.toList()
        invalidate()
    }

    fun pulse(on: Boolean) {
        cursorOn = on
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        theme.drawPanel(canvas, frame)
        val p = pad
        canvas.drawText("EVENT LOG", p, p + dp(10f), title)
        val lineHeight = dp(14f)
        val bottom = height - p - lineHeight
        val fits = ((bottom - (p + dp(26f))) / lineHeight).toInt() + 1
        val shown = events.takeLast(fits)
        var y = bottom - (shown.size - 1) * lineHeight
        val sourceX = p + time.measureText("00:00:00 ")
        val textX = sourceX + source.measureText("gaming-pc ")
        for (event in shown) {
            canvas.drawText(event.time, p, y, time)
            canvas.drawText(event.source.take(9), sourceX, y, source)
            line.color = Theme.tone(event.tone)
            canvas.drawText(event.text, textX, y, line)
            y += lineHeight
        }
        if (cursorOn) {
            val top = y - dp(9f)
            canvas.drawText(">", p, y, line.apply { color = Theme.CYAN })
            canvas.drawRect(p + dp(10f), top, p + dp(16f), top + dp(11f), cursor)
        }
    }
}

/**
 * The frame around the Camera's picture. The picture itself is a SurfaceView
 * behind this one, so the video area is left clear.
 */
class CameraHud(context: Context, theme: Theme) : Panel(context, theme) {
    val video = RectF()
    private val title = theme.text(10f, Theme.MUTED, bold = true).apply { letterSpacing = 0.12f }
    private val state = theme.text(10f, Theme.MUTED, bold = true, align = Paint.Align.RIGHT).apply { letterSpacing = 0.12f }
    private val rec = theme.fill(Theme.MAGENTA)
    private val bracket = theme.stroke(Theme.CYAN, 1.5f)
    private val shade = theme.fill(Theme.withAlpha(Theme.BG, 150))
    private var feed = FeedState.CONNECTING
    private var recOn = true

    fun setState(next: FeedState) {
        feed = next
        invalidate()
    }

    fun pulse(on: Boolean) {
        recOn = on
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The largest 16:9 picture under the title strip.
        val left = pad / 2
        val top = pad + dp(18f)
        val availW = w - pad
        val availH = h - top - pad / 2
        val (vw, vh) = if (availW / availH > 16f / 9f) availH * 16f / 9f to availH else availW to availW * 9f / 16f
        val x = left + (availW - vw) / 2
        video.set(x, top, x + vw, top + vh)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipOutRect(video)
        theme.drawPanel(canvas, frame)
        canvas.restore()
        val p = pad
        canvas.drawText("CAM-01 // FRONT DOOR", p, p + dp(10f), title)
        val (text, color) = when (feed) {
            FeedState.LIVE -> "LIVE" to Theme.TEXT
            FeedState.CONNECTING -> "LINKING" to Theme.AMBER
            FeedState.RECONNECTING -> "SIGNAL LOST" to Theme.MAGENTA
        }
        state.color = color
        val right = width - p - dp(10f)
        canvas.drawText(text, right, p + dp(10f), state)
        if (feed == FeedState.LIVE && recOn || feed != FeedState.LIVE) {
            rec.color = if (feed == FeedState.LIVE) Theme.MAGENTA else color
            rec.setShadowLayer(dp(5f), 0f, 0f, rec.color)
            canvas.drawCircle(right - state.measureText(text) - dp(8f), p + dp(6.5f), dp(3f), rec)
        }
        if (feed != FeedState.LIVE) canvas.drawRect(video, shade)
        // HUD brackets on the picture's corners.
        val arm = dp(16f)
        val v = video
        canvas.drawLine(v.left, v.top, v.left + arm, v.top, bracket)
        canvas.drawLine(v.left, v.top, v.left, v.top + arm, bracket)
        canvas.drawLine(v.right, v.top, v.right - arm, v.top, bracket)
        canvas.drawLine(v.right, v.top, v.right, v.top + arm, bracket)
        canvas.drawLine(v.left, v.bottom, v.left + arm, v.bottom, bracket)
        canvas.drawLine(v.left, v.bottom, v.left, v.bottom - arm, bracket)
        canvas.drawLine(v.right, v.bottom, v.right - arm, v.bottom, bracket)
        canvas.drawLine(v.right, v.bottom, v.right, v.bottom - arm, bracket)
    }
}

/** The top strip: the name, the hub link, how many machines are up, and the time. */
class Header(context: Context, theme: Theme) : View(context) {
    private val t = theme
    private val logo = theme.text(18f, Theme.CYAN, bold = true).apply { letterSpacing = 0.25f }.also { theme.glow(it, 8f) }
    private val sub = theme.text(10f, Theme.MUTED, bold = true).apply { letterSpacing = 0.15f }
    private val info = theme.text(10f, Theme.MUTED, bold = true, align = Paint.Align.RIGHT).apply { letterSpacing = 0.12f }
    private val clock = theme.text(18f, Theme.TEXT, bold = true, align = Paint.Align.RIGHT).also { theme.glow(it, 6f, Theme.CYAN) }
    private val rule = theme.stroke(Theme.withAlpha(Theme.CYAN, 60), 1f)
    private val tick = theme.stroke(Theme.withAlpha(Theme.CYAN, 110), 1f)
    private val dot = theme.fill(Theme.GREEN)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("EEE dd MMM", Locale.US)
    var link = Beszel.Link.CONNECTING
        set(value) {
            field = value
            invalidate()
        }
    var online = 0
    var total = 0

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = h - t.dp(13f)
        canvas.drawText("VISOR", 0f, base, logo)
        canvas.drawText("// FLEET TELEMETRY", logo.measureText("VISOR") + t.dp(14f), base - t.dp(1f), sub)

        val now = Date()
        canvas.drawText(timeFormat.format(now), w, base, clock)
        var x = w - clock.measureText("00:00:00") - t.dp(18f)
        val date = dateFormat.format(now).uppercase(Locale.US)
        canvas.drawText(date, x, base - t.dp(1f), info)
        x -= info.measureText(date) + t.dp(22f)

        val (linkText, linkColor) = when (link) {
            Beszel.Link.LIVE -> "HUB LINK" to Theme.GREEN
            Beszel.Link.CONNECTING -> "HUB LINKING" to Theme.AMBER
            Beszel.Link.OFFLINE -> "HUB OFFLINE" to Theme.MAGENTA
            Beszel.Link.NO_CREDENTIALS -> "NO CREDENTIALS" to Theme.MAGENTA
        }
        canvas.drawText(linkText, x, base - t.dp(1f), info)
        dot.color = linkColor
        dot.setShadowLayer(t.dp(5f), 0f, 0f, linkColor)
        canvas.drawCircle(x - info.measureText(linkText) - t.dp(8f), base - t.dp(4.5f), t.dp(3f), dot)
        x -= info.measureText(linkText) + t.dp(30f)
        canvas.drawText("NODES %02d/%02d".format(online, total), x, base - t.dp(1f), info)

        // A ruler along the bottom edge.
        val y = h - t.dp(1f)
        canvas.drawLine(0f, y, w, y, rule)
        var tx = 0f
        var i = 0
        while (tx <= w) {
            val len = if (i % 10 == 0) t.dp(6f) else t.dp(3f)
            canvas.drawLine(tx, y, tx, y - len, tick)
            tx += t.dp(8f)
            i++
        }
    }
}
