package com.adjm.mp4player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

object TextAnims {
    val names = listOf("Tanpa animasi", "Mesin tik (huruf satu per satu)", "Kata satu per satu", "Muncul perlahan", "Naik dari bawah", "Zoom")
}

/**
 * Menggambar teks (dengan font, garis tepi, dan animasi).
 * Dipakai bersama oleh pratinjau di layar dan oleh hasil Save, supaya tampilannya sama.
 */
object TextRender {
    class Built(val sl: StaticLayout, val tp: TextPaint, val width: Int)

    private val wordRegex = Regex("\\S+")

    fun build(ctx: Context, t: TextItem, frameW: Int): Built {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color
            textSize = t.size * frameW
            typeface = TextFonts.typeface(ctx, t.font)
            textSkewX = TextFonts.skew(t.font)
            setShadowLayer(t.size * frameW / 10f, 0f, 2f, 0xCC000000.toInt())
        }
        val maxW = (frameW * 0.9f).toInt()
        val desired = ceil(StaticLayout.getDesiredWidth(t.text, tp)).toInt().coerceIn(1, max(1, maxW))
        val sl = StaticLayout.Builder.obtain(t.text, 0, t.text.length, tp, desired)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        return Built(sl, tp, desired)
    }

    /** Lama animasi masuk (milidetik). */
    private fun introMs(t: TextItem): Long {
        val dur = max(1L, t.endMs - t.startMs)
        val want = when (t.anim) {
            1 -> t.text.length * 70L
            2 -> wordRegex.findAll(t.text).count() * 350L
            3 -> 600L
            4 -> 600L
            5 -> 500L
            else -> 0L
        }
        val cap = max(200L, (dur * 0.8f).toLong())
        return want.coerceIn(200L, cap)
    }

    fun progress(t: TextItem, ms: Long): Float {
        if (t.anim == 0) return 1f
        return ((ms - t.startMs).toFloat() / introMs(t)).coerceIn(0f, 1f)
    }

    /** Jumlah karakter yang sudah tampil; -1 bila semua tampil. */
    fun visibleChars(t: TextItem, ms: Long): Int {
        if (t.anim != 1 && t.anim != 2) return -1
        val p = progress(t, ms)
        if (p >= 1f) return -1
        val text = t.text
        if (t.anim == 1) {
            var n = (p * text.length).toInt()
            while (n > 0 && n < text.length && Character.isLowSurrogate(text[n])) n++
            return n
        }
        val words = wordRegex.findAll(text).toList()
        if (words.isEmpty()) return -1
        val k = ceil((p * words.size).toDouble()).toInt().coerceIn(0, words.size)
        return if (k == 0) 0 else words[k - 1].range.last + 1
    }

    /** Penanda keadaan animasi: berubah hanya saat tampilan teks berubah. */
    fun stateKey(t: TextItem, ms: Long): String = when (t.anim) {
        1, 2 -> "n" + visibleChars(t, ms)
        3, 4, 5 -> "a" + (progress(t, ms) * 12).toInt()
        else -> "s"
    }

    /** Tebal outline efektif: pilihan pengguna, atau otomatis untuk font grafiti. */
    fun outlineWidth(t: TextItem): Float =
        if (t.outlineW >= 0f) t.outlineW else if (TextFonts.outline(t.font)) 0.16f else 0f

    private fun passes(t: TextItem, tp: TextPaint, body: () -> Unit) {
        val ow = outlineWidth(t)
        if (ow > 0f) {
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = tp.textSize * ow
            tp.strokeJoin = Paint.Join.ROUND
            tp.color = t.outlineColor
            tp.clearShadowLayer()
            body()
            tp.style = Paint.Style.FILL
            tp.color = t.color
        }
        body()
    }

    /** Menggambar dengan sudut kiri-atas teks di titik (0,0) kanvas. */
    @Suppress("DEPRECATION")
    fun drawBuilt(c: Canvas, t: TextItem, b: Built, frameH: Int, ms: Long) {
        val sl = b.sl
        val p = progress(t, ms)
        val pad = b.tp.textSize * 0.4f
        c.save()
        when (t.anim) {
            4 -> c.translate(0f, (1f - p) * frameH * 0.05f)
            5 -> { val s = 0.6f + 0.4f * p; c.scale(s, s, b.width / 2f, sl.height / 2f) }
        }
        val alpha = if (t.anim in 3..5) (p * 255).toInt().coerceIn(0, 255) else 255
        val layer = if (alpha < 255) c.saveLayerAlpha(-pad, -pad, b.width + pad, sl.height + pad, alpha) else -1

        if (t.bgColor != 0) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.bgColor }
            val px = b.tp.textSize * 0.45f
            val py = b.tp.textSize * 0.25f
            val r = b.tp.textSize * 0.3f
            c.drawRoundRect(-px, -py, b.width + px, sl.height + py, r, r, bp)
        }

        val n = visibleChars(t, ms)
        if (n < 0) {
            passes(t, b.tp) { sl.draw(c) }
        } else if (n > 0) {
            passes(t, b.tp) {
                for (i in 0 until sl.lineCount) {
                    val ls = sl.getLineStart(i)
                    val le = sl.getLineEnd(i)
                    val vis = (n - ls).coerceIn(0, le - ls)
                    if (vis <= 0) continue
                    val seg = t.text.substring(ls, ls + vis).trimEnd('\n')
                    c.drawText(seg, sl.getLineLeft(i), sl.getLineBaseline(i).toFloat(), b.tp)
                }
            }
        }
        if (layer >= 0) c.restoreToCount(layer)
        c.restore()
    }

    /** Menggambar teks di tengah titik (nx, ny) bingkai video; dipakai oleh Save. */
    fun drawCentered(ctx: Context, c: Canvas, t: TextItem, frameW: Int, frameH: Int, ms: Long) {
        val b = build(ctx, t, frameW)
        c.save()
        c.translate(t.nx * frameW - b.width / 2f, t.ny * frameH - b.sl.height / 2f)
        drawBuilt(c, t, b, frameH, ms)
        c.restore()
    }
}

/** Tampilan teks di layar utama (pratinjau), sama dengan hasil Save. */
class AnimTextView(ctx: Context) : View(ctx) {
    private var item: TextItem? = null
    private var built: TextRender.Built? = null
    private var frameH = 1
    private var timeMs = 0L
    private var selected = false
    private val pad = ctx.dp(36f)   // ruang untuk animasi, bayangan, dan garis tepi
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = ctx.dp(1.5f).toFloat()
    }

    /** Mengembalikan ukuran tampilan (lebar, tinggi) termasuk ruang tambahan. */
    fun bind(t: TextItem, fw: Int, fh: Int, ms: Long, sel: Boolean): Pair<Int, Int> {
        val b = TextRender.build(context, t, fw)
        item = t; built = b; frameH = fh; timeMs = ms; selected = sel
        val newW = b.width + 2 * pad
        val newH = b.sl.height + 2 * pad
        val lp = layoutParams
        if (lp != null && (lp.width != newW || lp.height != newH)) {
            lp.width = newW; lp.height = newH; layoutParams = lp
        }
        invalidate()
        return Pair(newW, newH)
    }

    override fun onDraw(c: Canvas) {
        val t = item ?: return
        val b = built ?: return
        c.save()
        c.translate(pad.toFloat(), pad.toFloat())
        TextRender.drawBuilt(c, t, b, frameH, timeMs)
        if (selected) {
            val m = context.dp(4f).toFloat()
            c.drawRect(-m, -m / 2, b.width + m, b.sl.height + m / 2, selPaint)
        }
        c.restore()
    }
}
