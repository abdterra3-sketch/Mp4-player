package com.adjm.mp4player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class ThumbLoader(private val ctx: Context) {
    private val cache = LruCache<String, Bitmap>(80)
    private val pending = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun get(uri: Uri, ms: Long, onLoaded: () -> Unit): Bitmap? {
        val q = ms / 500 * 500
        val key = "$uri@$q"
        cache.get(key)?.let { return it }
        if (pending.add(key)) {
            pool.execute {
                var bmp: Bitmap? = null
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(ctx, uri)
                    val f = r.getFrameAtTime(q * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    r.release()
                    if (f != null) {
                        val h = 120
                        val w = max(1, f.width * h / f.height)
                        bmp = Bitmap.createScaledBitmap(f, w, h, true)
                    }
                } catch (_: Exception) {
                }
                main.post {
                    pending.remove(key)
                    if (bmp != null) { cache.put(key, bmp); onLoaded() }
                }
            }
        }
        return null
    }
}

class ThumbView(ctx: Context, private val clip: Clip, private val loader: ThumbLoader) : View(ctx) {
    var sel = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint().apply { color = 0xFF333333.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(c: Canvas) {
        val w = width; val h = height
        if (w == 0 || h == 0) return
        val n = max(1, ceil(w.toFloat() / h).toInt())
        val tw = w.toFloat() / n
        for (i in 0 until n) {
            val t = clip.inMs + (clip.durMs * (i + 0.5f) / n).toLong()
            val dst = RectF(i * tw, 0f, (i + 1) * tw, h.toFloat())
            val b = loader.get(clip.uri, t) { invalidate() }
            if (b == null) c.drawRect(dst, bg) else {
                val da = dst.width() / dst.height()
                val sa = b.width.toFloat() / b.height
                val src = if (sa > da) {
                    val nw = (b.height * da).toInt(); val x = (b.width - nw) / 2
                    Rect(x, 0, x + nw, b.height)
                } else {
                    val nh = (b.width / da).toInt(); val y = (b.height - nh) / 2
                    Rect(0, y, b.width, y + nh)
                }
                c.drawBitmap(b, src, dst, p)
            }
        }
        if (sel) {
            val sw = context.dp(3f).toFloat()
            line.color = Color.WHITE; line.strokeWidth = sw
            c.drawRect(sw / 2, sw / 2, w - sw / 2, h - sw / 2, line)
            val hw = context.dp(8f).toFloat()
            c.drawRoundRect(0f, 0f, hw, h.toFloat(), 6f, 6f, fill)
            c.drawRoundRect(w - hw, 0f, w.toFloat(), h.toFloat(), 6f, 6f, fill)
        } else {
            line.color = Color.BLACK; line.strokeWidth = 2f
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), line)
        }
    }
}

class TimelineView(ctx: Context, private val proj: Project, private val thumbs: ThumbLoader) : FrameLayout(ctx) {
    interface Listener {
        fun onSeek(ms: Long)
        fun onSelect(kind: Int, idx: Int)
        fun onBeginEdit()
        fun onEdited()
        fun onAddVideo()
    }

    companion object {
        const val NONE = 0; const val VIDEO = 1; const val TEXT = 2; const val AUDIO = 3; const val OVERLAY = 4
        const val MIN = 300L
    }

    var listener: Listener? = null
    var selKind = NONE
    var selIdx = -1
    private val pps = context.dp(64f).toFloat()   // piksel per detik
    private var programmatic = false
    private var half = 0
    private val content = FrameLayout(ctx)
    private val blocks = HashMap<Int, View>()

    private val hsv = object : HorizontalScrollView(ctx) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            if (!programmatic) listener?.onSeek(msAt(l))
        }
    }

    init {
        hsv.isHorizontalScrollBarEnabled = false
        addView(hsv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        hsv.addView(content, LayoutParams(10, LayoutParams.MATCH_PARENT))
        val head = View(ctx).apply { setBackgroundColor(Color.WHITE) }
        addView(head, LayoutParams(context.dp(2f), LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
    }

    private fun msAt(x: Int): Long = (x * 1000f / pps).toLong()
    private fun xOf(ms: Long): Int = half + (ms * pps / 1000f).toInt()
    private fun cl(v: Long, lo: Long, hi: Long) = if (hi < lo) lo else v.coerceIn(lo, hi)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        refresh()
    }

    fun scrollToMs(ms: Long) {
        programmatic = true
        hsv.scrollTo((ms * pps / 1000f).toInt(), 0)
        programmatic = false
    }

    fun clearSelection() { selKind = NONE; selIdx = -1; restyle() }

    fun select(kind: Int, idx: Int, notify: Boolean = true) {
        selKind = kind; selIdx = idx
        restyle()
        if (notify) listener?.onSelect(kind, idx)
    }

    private fun top(kind: Int) = context.dp(when (kind) { VIDEO -> 6f; TEXT -> 64f; AUDIO -> 102f; else -> 140f }.toFloat())
    private fun hgt(kind: Int) = context.dp(if (kind == VIDEO) 52f else 32f)

    private fun place(v: View, s: Long, e: Long, kind: Int) {
        val lp = LayoutParams(max(context.dp(12f), ((e - s) * pps / 1000f).toInt()), hgt(kind))
        lp.leftMargin = xOf(s); lp.topMargin = top(kind)
        v.layoutParams = lp
    }

    private fun colorOf(kind: Int) = when (kind) {
        TEXT -> 0xFFE67300.toInt(); AUDIO -> 0xFF2E9E8F.toInt(); else -> 0xFF8E44AD.toInt()
    }

    private fun restyle() {
        for ((key, v) in blocks) {
            val kind = key / 1000
            val selected = selKind == kind && selIdx == key % 1000
            if (v is ThumbView) { v.sel = selected; v.invalidate() } else {
                v.background = GradientDrawable().apply {
                    setColor(colorOf(kind)); cornerRadius = context.dp(6f).toFloat()
                    if (selected) setStroke(context.dp(2.5f), Color.WHITE)
                }
            }
        }
    }

    private fun label(text: String, kind: Int, idx: Int): TextView {
        val t = TextView(context)
        t.text = text; t.setTextColor(Color.WHITE); t.textSize = 11f
        t.setSingleLine(true); t.gravity = Gravity.CENTER_VERTICAL
        t.setPadding(context.dp(10f), 0, context.dp(10f), 0)
        t.setOnClickListener { select(kind, idx) }
        return t
    }

    fun refresh() {
        if (width == 0) return
        half = width / 2
        val total = proj.totalMs()
        content.layoutParams = LayoutParams((half * 2 + total * pps / 1000f + context.dp(72f)).toInt(), LayoutParams.MATCH_PARENT)
        content.removeAllViews(); blocks.clear()

        var x = 0L
        proj.clips.forEachIndexed { i, c ->
            val v = ThumbView(context, c, thumbs)
            place(v, x, x + c.durMs, VIDEO)
            v.setOnClickListener { select(VIDEO, i) }
            attach(v, VIDEO, i)
            content.addView(v); blocks[VIDEO * 1000 + i] = v
            x += c.durMs
        }
        val plus = TextView(context).apply {
            text = "+"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = context.dp(6f).toFloat() }
            setOnClickListener { listener?.onAddVideo() }
        }
        val plp = LayoutParams(context.dp(40f), context.dp(40f))
        plp.leftMargin = xOf(total) + context.dp(10f); plp.topMargin = top(VIDEO) + context.dp(6f)
        content.addView(plus, plp)

        proj.texts.forEachIndexed { i, t ->
            val v = label(t.text, TEXT, i); place(v, t.startMs, t.endMs, TEXT); attach(v, TEXT, i)
            content.addView(v); blocks[TEXT * 1000 + i] = v
        }
        proj.audios.forEachIndexed { i, a ->
            val v = label("♪ " + a.name, AUDIO, i); place(v, a.startMs, a.endMs, AUDIO); attach(v, AUDIO, i)
            content.addView(v); blocks[AUDIO * 1000 + i] = v
        }
        proj.overlays.forEachIndexed { i, o ->
            val v = label("▣ Overlay", OVERLAY, i); place(v, o.startMs, o.endMs, OVERLAY); attach(v, OVERLAY, i)
            content.addView(v); blocks[OVERLAY * 1000 + i] = v
        }
        val ok = when (selKind) {
            VIDEO -> selIdx in proj.clips.indices; TEXT -> selIdx in proj.texts.indices
            AUDIO -> selIdx in proj.audios.indices; OVERLAY -> selIdx in proj.overlays.indices
            else -> true
        }
        if (!ok) { selKind = NONE; selIdx = -1 }
        restyle()
    }

    private fun gs(k: Int, i: Int): Long = when (k) { TEXT -> proj.texts[i].startMs; OVERLAY -> proj.overlays[i].startMs; AUDIO -> proj.audios[i].startMs; else -> 0L }
    private fun ge(k: Int, i: Int): Long = when (k) { TEXT -> proj.texts[i].endMs; OVERLAY -> proj.overlays[i].endMs; AUDIO -> proj.audios[i].endMs; else -> 0L }
    private fun ss(k: Int, i: Int, v: Long) { when (k) { TEXT -> proj.texts[i].startMs = v; OVERLAY -> proj.overlays[i].startMs = v } }
    private fun se(k: Int, i: Int, v: Long) { when (k) { TEXT -> proj.texts[i].endMs = v; OVERLAY -> proj.overlays[i].endMs = v } }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(v: View, kind: Int, idx: Int) {
        v.setOnTouchListener(object : View.OnTouchListener {
            var mode = 0; var downX = 0f; var started = false
            val orig = LongArray(4)
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val zone = context.dp(22f)
                        mode = if (e.x < zone) 1 else if (e.x > view.width - zone) 2 else if (kind == VIDEO) 0 else 3
                        if (mode == 0) return false
                        downX = e.rawX; started = false
                        when (kind) {
                            VIDEO -> { val c = proj.clips[idx]; orig[0] = 0; orig[1] = 0; orig[2] = c.inMs; orig[3] = c.outMs }
                            AUDIO -> { val a = proj.audios[idx]; orig[0] = a.startMs; orig[1] = a.endMs; orig[2] = a.inMs; orig[3] = a.outMs }
                            else -> { orig[0] = gs(kind, idx); orig[1] = ge(kind, idx) }
                        }
                        select(kind, idx)
                        parent.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (mode == 0) return false
                        if (!started) {
                            if (abs(e.rawX - downX) < context.dp(4f)) return true
                            started = true; listener?.onBeginEdit()
                        }
                        val d = ((e.rawX - downX) * 1000f / pps).toLong()
                        drag(view, kind, idx, mode, d, orig)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        parent.requestDisallowInterceptTouchEvent(false)
                        val handled = mode != 0
                        mode = 0
                        if (handled && started) listener?.onEdited()
                        return handled
                    }
                }
                return mode != 0
            }
        })
    }

    private fun drag(v: View, kind: Int, idx: Int, mode: Int, d: Long, o: LongArray) {
        val total = proj.totalMs()
        when (kind) {
            VIDEO -> {
                val c = proj.clips[idx]
                if (mode == 1) c.inMs = cl(o[2] + d, 0, c.outMs - MIN)
                else c.outMs = cl(o[3] + d, c.inMs + MIN, c.srcMs)
                val lp = v.layoutParams
                lp.width = max(context.dp(12f), (c.durMs * pps / 1000f).toInt())
                v.layoutParams = lp
            }
            AUDIO -> {
                val a = proj.audios[idx]
                when (mode) {
                    3 -> { val len = o[1] - o[0]; a.startMs = cl(o[0] + d, 0, max(0, total - len)) }
                    1 -> { val dd = cl(d, max(-o[2], -o[0]), o[3] - o[2] - MIN); a.inMs = o[2] + dd; a.startMs = o[0] + dd }
                    else -> a.outMs = cl(o[3] + d, a.inMs + MIN, min(a.srcMs, a.inMs + (total - a.startMs)))
                }
                place(v, a.startMs, a.endMs, AUDIO)
            }
            else -> {
                when (mode) {
                    3 -> { val len = o[1] - o[0]; val ns = cl(o[0] + d, 0, max(0, total - len)); ss(kind, idx, ns); se(kind, idx, ns + len) }
                    1 -> ss(kind, idx, cl(o[0] + d, 0, ge(kind, idx) - MIN))
                    else -> se(kind, idx, cl(o[1] + d, gs(kind, idx) + MIN, max(total, gs(kind, idx) + MIN)))
                }
                place(v, gs(kind, idx), ge(kind, idx), kind)
            }
        }
    }
}
