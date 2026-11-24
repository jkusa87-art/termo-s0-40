package pl.termo.s040

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Obraz termiczny + markery min/max/srodek/dotyk + obszar pomiaru + pasek kolorow.
 * Wspolrzedne "sensor" = piksele matrycy 256x192 (jak w klatce); "display" = po obrocie.
 */
class ThermalView(context: Context) : View(context) {
    @Volatile private var frame: ThermalFrame? = null
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private var map = IntArray(0)          // indeks sensora -> indeks w obroconej bitmapie
    private var mapKey = -1

    var palette = 0
        set(v) { field = v; rerender() }
    /** Obrot zgodnie z ruchem wskazowek: 0, 1 (90°), 2 (180°), 3 (270°). */
    var rotation90 = 0
        set(v) { field = ((v % 4) + 4) % 4; rerender() }
    var areaMode = false
        set(v) { field = v; if (!v) area = null; invalidate() }
    var statusText = ""

    private var touch: Pair<Int, Int>? = null
    private var area: IntArray? = null      // x0,y0,x1,y1 w sensorze (wlacznie)
    private var dragStart: Pair<Int, Int>? = null

    /** Wywolywane po kazdym narysowaniu klatki (watek UI) - dla nagrywania. */
    var onFrameDrawn: (() -> Unit)? = null

    private val dst = RectF()          // obraz na ekranie (do dotyku)
    private val dd = RectF()           // obraz na aktualnie rysowanym plotnie
    private val barPaint = Paint()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dp = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true; style = Paint.Style.STROKE; color = Color.BLACK }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init { setBackgroundColor(Color.BLACK) }

    fun current(): ThermalFrame? = frame

    fun setFrame(f: ThermalFrame) {
        render(f); frame = f; postInvalidate()
    }

    private fun rerender() { frame?.let { render(it) }; postInvalidate() }

    // ---------- obrot ----------
    private fun dispW(f: ThermalFrame) = if (rotation90 % 2 == 0) f.width else f.height
    private fun dispH(f: ThermalFrame) = if (rotation90 % 2 == 0) f.height else f.width

    private fun toDisplay(f: ThermalFrame, x: Int, y: Int): Pair<Int, Int> {
        val w = f.width; val h = f.height
        return when (rotation90) {
            1 -> (h - 1 - y) to x
            2 -> (w - 1 - x) to (h - 1 - y)
            3 -> y to (w - 1 - x)
            else -> x to y
        }
    }

    private fun toSensor(f: ThermalFrame, dx: Int, dy: Int): Pair<Int, Int> {
        val w = f.width; val h = f.height
        return when (rotation90) {
            1 -> dy to (h - 1 - dx)
            2 -> (w - 1 - dx) to (h - 1 - dy)
            3 -> (w - 1 - dy) to dx
            else -> dx to dy
        }
    }

    @Synchronized private fun render(f: ThermalFrame) {
        val w = f.width; val h = f.height
        val dw = dispW(f); val dh = dispH(f)
        val key = rotation90 * 100000 + w * 300 + h
        if (key != mapKey) {
            map = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val (dx, dy) = toDisplay(f, x, y); map[y * w + x] = dy * dw + dx
            }
            mapKey = key
        }
        if (bitmap?.width != dw || bitmap?.height != dh) {
            bitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888); pixels = IntArray(dw * dh)
        }
        val pal = Palettes.tables[palette]
        val lo = f.rawMin; val span = (f.rawMax - f.rawMin).coerceAtLeast(1)
        for (i in 0 until w * h) pixels[map[i]] = pal[((f.raw[i] - lo) * 255) / span]
        bitmap!!.setPixels(pixels, 0, dw, 0, 0, dw, dh)
    }

    // ---------- dotyk ----------
    private fun screenToSensor(f: ThermalFrame, sx: Float, sy: Float, clamp: Boolean): Pair<Int, Int>? {
        if (dst.width() <= 0) return null
        val dw = dispW(f); val dh = dispH(f)
        var dx = ((sx - dst.left) / dst.width() * dw).toInt()
        var dy = ((sy - dst.top) / dst.height() * dh).toInt()
        if (clamp) { dx = dx.coerceIn(0, dw - 1); dy = dy.coerceIn(0, dh - 1) }
        else if (dx !in 0 until dw || dy !in 0 until dh) return null
        return toSensor(f, dx, dy)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val f = frame ?: return true
        if (areaMode) {
            val p = screenToSensor(f, e.x, e.y, clamp = true) ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragStart = p; area = null }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> dragStart?.let { s ->
                    area = intArrayOf(min(s.first, p.first), min(s.second, p.second), max(s.first, p.first), max(s.second, p.second))
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        dragStart = null
                        val a = area!!
                        if (abs(a[2] - a[0]) < 2 && abs(a[3] - a[1]) < 2) area = null
                    }
                }
            }
        } else {
            touch = screenToSensor(f, e.x, e.y, clamp = false)
        }
        invalidate()
        return true
    }

    // ---------- rysowanie ----------
    @Synchronized override fun onDraw(c: Canvas) {
        drawContent(c, width, height, 1f, showStatus = false)
        dst.set(dd)
        onFrameDrawn?.invoke()
    }

    /** Rysuje caly obraz na dowolnym plotnie (ekran, film, zrzut). [k] skaluje napisy. */
    @Synchronized fun drawContent(c: Canvas, w: Int, h: Int, k: Float, showStatus: Boolean) {
        val f = frame ?: return
        val bmp = bitmap ?: return
        val statusH = if (showStatus) 26 * dp * k else 0f
        val barW = 56 * dp * k
        val availW = w - barW
        val availH = h - statusH
        val scale = min(availW / bmp.width, availH / bmp.height)
        val bw = bmp.width * scale; val bh = bmp.height * scale
        dd.set((availW - bw) / 2, (availH - bh) / 2, (availW - bw) / 2 + bw, (availH - bh) / 2 + bh)
        c.drawBitmap(bmp, null, dd, bmpPaint)

        fun pt(idx: Int) = (idx % f.width) to (idx / f.width)
        area?.let { drawArea(c, f, it, k) }
        marker(c, f, pt(f.idxMax), Color.RED, f.tMax, k)
        marker(c, f, pt(f.idxMin), Color.rgb(0, 160, 255), f.tMin, k)
        marker(c, f, f.width / 2 to f.height / 2, Color.WHITE, f.tCenter, k)
        touch?.let { if (!areaMode) marker(c, f, it, Color.GREEN, f.tempAt(it.first, it.second), k) }

        // pasek kolorow
        val pal = Palettes.tables[palette]
        val bx = w - barW + 6 * dp * k; val top = dd.top; val bot = dd.bottom
        val steps = 128
        for (i in 0 until steps) {
            barPaint.color = pal[255 - i * 255 / (steps - 1)]
            val y0 = top + (bot - top) * i / steps; val y1 = top + (bot - top) * (i + 1) / steps + 1
            c.drawRect(bx, y0, bx + 14 * dp * k, y1, barPaint)
        }
        val small = 11 * dp * k
        text.textSize = small; text.color = Color.WHITE
        for (j in 0..4) {
            val frac = j / 4f
            val t = f.tMax - frac * (f.tMax - f.tMin)
            val y = (top + (bot - top) * frac).coerceIn(top + small, bot)
            c.drawText("%.1f".format(t), bx + 16 * dp * k, y, text)
        }
        if (showStatus && statusText.isNotEmpty()) {
            text.textSize = 13 * dp * k; text.color = Color.WHITE
            c.drawText(statusText, 8 * dp * k, h - 8 * dp * k, text)
        }
    }

    private fun sensorToScreen(f: ThermalFrame, x: Int, y: Int, center: Boolean = true): Pair<Float, Float> {
        val (dx, dy) = toDisplay(f, x, y)
        val o = if (center) 0.5f else 0f
        return (dd.left + (dx + o) * dd.width() / dispW(f)) to (dd.top + (dy + o) * dd.height() / dispH(f))
    }

    private fun label(c: Canvas, s: String, x0: Float, y0: Float, color: Int, k: Float, r: Float) {
        text.textSize = 15 * dp * k; outline.textSize = text.textSize; outline.strokeWidth = 4 * dp * k
        val tw = text.measureText(s)
        var tx = x0 + r; var ty = y0 - r
        if (tx + tw > dd.right) tx = x0 - r - tw
        if (ty - text.textSize < dd.top) ty = y0 + r + text.textSize
        text.color = color
        c.drawText(s, tx, ty, outline); c.drawText(s, tx, ty, text)
    }

    private fun marker(c: Canvas, f: ThermalFrame, p: Pair<Int, Int>, color: Int, t: Float, k: Float) {
        val (sx, sy) = sensorToScreen(f, p.first, p.second)
        val r = 10 * dp * k
        line.color = Color.BLACK; line.strokeWidth = 5 * dp * k
        c.drawLine(sx - r, sy, sx + r, sy, line); c.drawLine(sx, sy - r, sx, sy + r, line)
        line.color = color; line.strokeWidth = 2.5f * dp * k
        c.drawLine(sx - r, sy, sx + r, sy, line); c.drawLine(sx, sy - r, sx, sy + r, line)
        label(c, "%.1f°C".format(t), sx, sy, color, k, r)
    }

    private fun drawArea(c: Canvas, f: ThermalFrame, a: IntArray, k: Float) {
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var sum = 0.0; var n = 0
        for (y in a[1]..a[3]) for (x in a[0]..a[2]) {
            val t = f.tempAt(x, y); if (t < mn) mn = t; if (t > mx) mx = t; sum += t; n++
        }
        val (ax, ay) = sensorToScreen(f, a[0], a[1], center = false)
        val (bx, by) = sensorToScreen(f, a[2] + 1, a[3] + 1, center = false)
        // po obrocie rogi moga byc zamienione
        val l = min(ax, bx); val r = max(ax, bx); val t = min(ay, by); val b = max(ay, by)
        rectPaint.color = Color.BLACK; rectPaint.strokeWidth = 4 * dp * k; c.drawRect(l, t, r, b, rectPaint)
        rectPaint.color = Color.GREEN; rectPaint.strokeWidth = 2 * dp * k; c.drawRect(l, t, r, b, rectPaint)
        text.textSize = 14 * dp * k; outline.textSize = text.textSize; outline.strokeWidth = 4 * dp * k
        text.color = Color.GREEN
        val s = "%s %.1f  %s %.1f  %s %.1f°C".format(I18n.t("max"), mx, I18n.t("min"), mn, I18n.t("avg"), sum / n)
        val tw = text.measureText(s)
        val tx = l.coerceAtMost(dd.right - tw).coerceAtLeast(dd.left)
        val ty = if (t - 6 * dp * k - text.textSize > dd.top) t - 6 * dp * k else b + text.textSize + 4 * dp * k
        c.drawText(s, tx, ty, outline); c.drawText(s, tx, ty, text)
    }
}
