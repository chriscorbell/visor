package com.chriscorbell.visor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/** Colors, type and the drawing pieces every panel shares. */
class Theme(context: Context) {
    val density = context.resources.displayMetrics.density
    fun dp(v: Float) = v * density

    val regular: Typeface = context.resources.getFont(R.font.jetbrains_mono_regular)
    val bold: Typeface = context.resources.getFont(R.font.jetbrains_mono_bold)

    fun text(size: Float, color: Int = TEXT, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = if (bold) this@Theme.bold else regular
            textSize = dp(size)
            this.color = color
            textAlign = align
        }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = dp(width)
    }

    /** Faint horizontal scanlines, tiled across a panel's background. */
    val scanlines: Paint = Paint().apply {
        val step = max(3, dp(3f).toInt())
        val bitmap = Bitmap.createBitmap(1, step, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.argb(10, 0, 229, 255))
        shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private val panelFill = fill(PANEL)
    private val panelEdge = stroke(withAlpha(CYAN, 48), 1f)
    private val bracket = stroke(CYAN, 1.5f).apply { strokeCap = Paint.Cap.SQUARE }
    private val panelPath = Path()

    /**
     * A panel: a dark plate with its top-right corner cut off, a hairline
     * edge, and bright brackets on two corners.
     */
    fun drawPanel(canvas: Canvas, r: RectF, accent: Int = CYAN) {
        val cut = dp(14f)
        panelPath.reset()
        panelPath.moveTo(r.left, r.top)
        panelPath.lineTo(r.right - cut, r.top)
        panelPath.lineTo(r.right, r.top + cut)
        panelPath.lineTo(r.right, r.bottom)
        panelPath.lineTo(r.left, r.bottom)
        panelPath.close()
        canvas.drawPath(panelPath, panelFill)
        canvas.drawPath(panelPath, scanlines)
        canvas.drawPath(panelPath, panelEdge)
        val arm = dp(12f)
        bracket.color = accent
        canvas.drawLine(r.left, r.top, r.left + arm, r.top, bracket)
        canvas.drawLine(r.left, r.top, r.left, r.top + arm, bracket)
        canvas.drawLine(r.right - cut, r.top, r.right, r.top + cut, bracket)
        canvas.drawLine(r.right, r.bottom, r.right - arm, r.bottom, bracket)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - arm, bracket)
    }

    private val segment = fill(CYAN)

    /**
     * A bar of [segments] lit blocks for [pct] percent, the last one lit in
     * proportion. Lit blocks take the color of the load's level.
     */
    fun drawBar(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, pct: Float, segments: Int = 24) {
        val gap = dp(2f)
        val w = (width - gap * (segments - 1)) / segments
        val lit = pct.coerceIn(0f, 100f) / 100f * segments
        val color = level(pct)
        for (i in 0 until segments) {
            val x = left + i * (w + gap)
            segment.color = withAlpha(color, 30)
            canvas.drawRect(x, top, x + w, top + height, segment)
            val amount = (lit - i).coerceIn(0f, 1f)
            if (amount > 0f) {
                segment.color = withAlpha(color, (90 + 165 * amount).toInt())
                canvas.drawRect(x, top, x + w, top + height, segment)
            }
        }
    }

    private val sparkLine = stroke(CYAN, 1.2f).apply { strokeJoin = Paint.Join.ROUND }
    private val sparkFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPath = Path()
    private val sparkArea = Path()
    private var sparkTop = Float.NaN
    private var sparkBottom = Float.NaN
    private var sparkColor = 0

    /** The last minute of [ring], scaled 0–[scale], with a fading fill under it. */
    fun drawSparkline(canvas: Canvas, r: RectF, ring: Ring, color: Int = CYAN, scale: Float = 100f) {
        if (ring.size < 2) return
        val step = r.width() / (ring.capacity - 1)
        val start = r.right - step * (ring.size - 1)
        sparkPath.reset()
        sparkArea.reset()
        for (i in 0 until ring.size) {
            val x = start + i * step
            val y = r.bottom - ring[i].coerceIn(0f, scale) / scale * r.height()
            if (i == 0) {
                sparkPath.moveTo(x, y)
                sparkArea.moveTo(x, r.bottom)
            }
            if (i > 0) sparkPath.lineTo(x, y)
            sparkArea.lineTo(x, y)
        }
        sparkArea.lineTo(r.right, r.bottom)
        sparkArea.close()
        if (sparkTop != r.top || sparkBottom != r.bottom || sparkColor != color) {
            sparkTop = r.top
            sparkBottom = r.bottom
            sparkColor = color
            sparkFill.shader = LinearGradient(0f, r.top, 0f, r.bottom, withAlpha(color, 70), withAlpha(color, 0), Shader.TileMode.CLAMP)
        }
        canvas.drawPath(sparkArea, sparkFill)
        sparkLine.color = color
        canvas.drawPath(sparkPath, sparkLine)
    }

    /** Text with a soft neon glow. */
    fun glow(paint: Paint, radius: Float = 6f, color: Int = paint.color) {
        paint.setShadowLayer(dp(radius), 0f, 0f, withAlpha(color, 140))
    }

    companion object {
        val BG = Color.rgb(3, 6, 10)
        val PANEL = Color.rgb(6, 12, 18)
        val CYAN = Color.rgb(0, 229, 255)
        val TEXT = Color.rgb(205, 236, 242)
        val MUTED = Color.rgb(84, 122, 134)
        val GREEN = Color.rgb(57, 255, 154)
        val AMBER = Color.rgb(255, 176, 32)
        val MAGENTA = Color.rgb(255, 46, 136)

        fun level(pct: Float) = when {
            pct >= 90f -> MAGENTA
            pct >= 75f -> AMBER
            else -> CYAN
        }

        fun tone(tone: Tone) = when (tone) {
            Tone.GOOD -> GREEN
            Tone.WARN -> AMBER
            Tone.ALERT -> MAGENTA
            Tone.INFO -> CYAN
        }

        fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (min(255, max(0, alpha)) shl 24)
    }
}

/** 1.2M, 340K, 12B: a byte rate in four characters or so. */
fun rate(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_000_000_000 -> "%.1fG".format(bytesPerSec / 1e9)
    bytesPerSec >= 10_000_000 -> "%.0fM".format(bytesPerSec / 1e6)
    bytesPerSec >= 1_000_000 -> "%.1fM".format(bytesPerSec / 1e6)
    bytesPerSec >= 1_000 -> "%.0fK".format(bytesPerSec / 1e3)
    else -> "${bytesPerSec}B"
}

/** 35D 04H, 4H 12M, 12M. */
fun uptime(seconds: Long): String {
    val d = seconds / 86_400
    val h = seconds % 86_400 / 3_600
    val m = seconds % 3_600 / 60
    return when {
        d > 0 -> "%dD %02dH".format(d, h)
        h > 0 -> "%dH %02dM".format(h, m)
        else -> "%dM".format(m)
    }
}

/** 1.2T, 512G: sizes in gigabytes. */
fun size(gb: Float): String = if (gb >= 1000f) "%.1fT".format(gb / 1024f) else "%.0fG".format(gb)
