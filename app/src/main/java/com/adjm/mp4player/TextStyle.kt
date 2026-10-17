package com.adjm.mp4player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.TextView

/**
 * Gaya font teks. Font bawaan HP dipakai secara default.
 * Untuk font grafiti / kaligrafi asli, letakkan file font di
 * app/src/main/assets/fonts/ dengan nama grafiti.ttf dan kaligrafi.ttf.
 */
object TextFonts {
    val names = listOf("Sans Serif", "Grafiti", "Kaligrafi", "Serif", "Mesin Tik")
    private val cache = HashMap<Int, Typeface>()

    private fun asset(ctx: Context, file: String): Typeface? = try {
        Typeface.createFromAsset(ctx.assets, "fonts/$file")
    } catch (_: Exception) { null }

    fun typeface(ctx: Context, idx: Int): Typeface = cache.getOrPut(idx) {
        when (idx) {
            1 -> asset(ctx, "grafiti.ttf") ?: Typeface.create("casual", Typeface.BOLD)
            2 -> asset(ctx, "kaligrafi.ttf") ?: Typeface.create("cursive", Typeface.BOLD)
            3 -> Typeface.create("serif", Typeface.BOLD)
            4 -> Typeface.create("monospace", Typeface.BOLD)
            else -> Typeface.create("sans-serif", Typeface.BOLD)
        }
    }

    /** Grafiti: huruf putih dengan garis tepi hitam tebal. */
    fun outline(idx: Int): Boolean = idx == 1

    /** Grafiti: sedikit miring. */
    fun skew(idx: Int): Float = if (idx == 1) -0.2f else 0f
}

/** TextView dengan opsi garis tepi (untuk gaya grafiti). */
class OutlineTextView(ctx: Context) : TextView(ctx) {
    var outline = false
    private var drawing = false

    override fun invalidate() {
        if (!drawing) super.invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (outline) {
            val fillColor = currentTextColor
            val p = paint
            val oldStyle = p.style
            val oldWidth = p.strokeWidth
            drawing = true
            p.style = Paint.Style.STROKE
            p.strokeWidth = textSize * 0.16f
            p.strokeJoin = Paint.Join.ROUND
            setTextColor(Color.BLACK)
            super.onDraw(c)
            p.style = oldStyle
            p.strokeWidth = oldWidth
            setTextColor(fillColor)
            drawing = false
        }
        super.onDraw(c)
    }
}
