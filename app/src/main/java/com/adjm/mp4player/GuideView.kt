package com.adjm.mp4player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Garis bantu transparan di tengah layar (muncul saat teks/overlay disentuh). */
class GuideView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var on = false
    var snapX = false
    var snapY = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.dp(1.5f).toFloat() }

    override fun onDraw(c: Canvas) {
        if (!on) return
        val w = width.toFloat(); val h = height.toFloat()
        p.color = if (snapX) Color.argb(230, 255, 214, 0) else Color.argb(130, 255, 255, 255)
        c.drawLine(w / 2, 0f, w / 2, h, p)
        p.color = if (snapY) Color.argb(230, 255, 214, 0) else Color.argb(130, 255, 255, 255)
        c.drawLine(0f, h / 2, w, h / 2, p)
    }
}
