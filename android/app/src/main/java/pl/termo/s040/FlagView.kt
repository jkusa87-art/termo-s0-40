package pl.termo.s040

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Mała flaga do przełączania języka (rysowana, bez plików graficznych). */
class FlagView(context: Context, val lang: I18n.Lang) : View(context) {
    var selected2 = false
        set(v) { field = v; alpha = if (v) 1f else 0.45f; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density

    init { contentDescription = lang.code; alpha = 0.45f }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.save()
        c.clipRect(0f, 0f, w, h)
        when (lang) {
            I18n.Lang.PL -> {
                p.color = Color.WHITE; c.drawRect(0f, 0f, w, h / 2, p)
                p.color = Color.rgb(220, 20, 60); c.drawRect(0f, h / 2, w, h, p)
            }
            I18n.Lang.ZH -> {
                p.color = Color.rgb(238, 28, 37); c.drawRect(0f, 0f, w, h, p)
                p.color = Color.rgb(255, 222, 0)
                star(c, w * 5 / 30, h * 5 / 20, h * 3 / 20)
                for ((x, y) in listOf(10f to 2f, 12f to 4f, 12f to 7f, 10f to 9f)) star(c, w * x / 30, h * y / 20, h * 1 / 20)
            }
            I18n.Lang.EN -> {   // uproszczona flaga Wielkiej Brytanii
                p.color = Color.rgb(1, 33, 105); c.drawRect(0f, 0f, w, h, p)
                p.strokeWidth = h * 0.2f; p.color = Color.WHITE
                c.drawLine(0f, 0f, w, h, p); c.drawLine(0f, h, w, 0f, p)
                p.strokeWidth = h * 0.08f; p.color = Color.rgb(200, 16, 46)
                c.drawLine(0f, 0f, w, h, p); c.drawLine(0f, h, w, 0f, p)
                p.strokeWidth = h * 0.33f; p.color = Color.WHITE
                c.drawLine(w / 2, 0f, w / 2, h, p); c.drawLine(0f, h / 2, w, h / 2, p)
                p.strokeWidth = h * 0.2f; p.color = Color.rgb(200, 16, 46)
                c.drawLine(w / 2, 0f, w / 2, h, p); c.drawLine(0f, h / 2, w, h / 2, p)
            }
        }
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = if (selected2) 2 * dp else 1 * dp
        p.color = if (selected2) Color.WHITE else Color.GRAY
        c.drawRect(0f, 0f, w, h, p)
        p.style = Paint.Style.FILL
    }

    private fun star(c: Canvas, cx: Float, cy: Float, r: Float) {
        val path = Path()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.4f
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = cx + rr * cos(a).toFloat(); val y = cy + rr * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); c.drawPath(path, p)
    }
}
