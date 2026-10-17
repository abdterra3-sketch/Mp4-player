package com.adjm.mp4player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class ThumbLoader(private val ctx: Context) {
    private class Job(val key: String, val uri: Uri, val ms: Long, val cb: () -> Unit)

    private val cache = LruCache<String, Bitmap>(400)
    private val pending = HashSet<String>()                 // hanya dipakai di thread utama
    private val queue = java.util.concurrent.LinkedBlockingDeque<Job>()
    private val main = Handler(Looper.getMainLooper())

    init {
        repeat(2) {
            val worker = Thread {
                while (true) {
                    val job = try { queue.takeLast() } catch (_: InterruptedException) { break }   // terbaru dikerjakan dulu
                    var bmp: Bitmap? = cache.get(job.key)
                    if (bmp == null) {
                        // cara utama: decoder langsung (frame tepat di waktunya)
                        bmp = FrameGrabber.grab(ctx, job.uri, job.ms * 1000, 120)
                        if (bmp == null) {
                            // cadangan bila decoder gagal
                            val r = MediaMetadataRetriever()
                            try {
                                r.setDataSource(ctx, job.uri)
                                val f = r.getFrameAtTime(job.ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                                if (f != null) {
                                    val h = 120
                                    val w = max(1, f.width * h / f.height)
                                    bmp = Bitmap.createScaledBitmap(f, w, h, true)
                                }
                            } catch (_: Exception) {
                            } finally {
                                try { r.release() } catch (_: Exception) {}
                            }
                        }
                    }
                    val result = bmp
                    main.post {
                        pending.remove(job.key)
                        if (result != null) { cache.put(job.key, result); job.cb() }
                    }
                }
            }
            worker.isDaemon = true
            worker.start()
        }
    }

    fun get(uri: Uri, ms: Long, onLoaded: () -> Unit): Bitmap? {
        val key = "$uri@$ms"
        cache.get(key)?.let { return it }
        if (pending.add(key)) queue.putLast(Job(key, uri, ms, onLoaded))
        return null
    }
}

/** Satu kotak thumbnail = 1 detik video, jadi posisinya sama persis dengan garis waktu. */
class ThumbView(
    ctx: Context, private val clip: Clip, private val loader: ThumbLoader, private val tileW: Float
) : View(ctx) {
    var sel = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint().apply { color = 0xFF2B2B2B.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(c: Canvas) {
        val w = width; val h = height
        if (w == 0 || h == 0) return
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val vis = c.clipBounds
        val first = max(0, (vis.left / tileW).toInt() - 1)
        val last = min(((w - 1) / tileW).toInt(), (vis.right / tileW).toInt() + 1)
        for (i in first..last) {
            val dst = RectF(i * tileW, 0f, min((i + 1) * tileW, w.toFloat()), h.toFloat())
            if (dst.width() <= 0f) continue
            val t = min(clip.inMs + i * 1000L, clip.outMs - 1)
            val b = loader.get(clip.uri, t) { invalidate() } ?: continue
            val full = RectF(i * tileW, 0f, (i + 1) * tileW, h.toFloat())
            val da = full.width() / full.height()
            val sa = b.width.toFloat() / b.height
            val src = if (sa > da) {
                val nw = (b.height * da).toInt(); val x = (b.width - nw) / 2
                Rect(x, 0, x + nw, b.height)
            } else {
                val nh = (b.width / da).toInt(); val y = (b.height - nh) / 2
                Rect(0, y, b.width, y + nh)
            }
            // potong sumber sesuai lebar kotak terakhir yang tidak penuh
            val frac = dst.width() / full.width()
            val srcCut = Rect(src.left, src.top, src.left + (src.width() * frac).toInt().coerceAtLeast(1), src.bottom)
            c.drawBitmap(b, srcCut, dst, p)
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

/** Blok teks / audio / overlay dengan pegangan tarik di kiri dan kanan. */
class BlockView(ctx: Context, private val text: String, private val bg: Int) : View(ctx) {
    var sel = false
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val textP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ctx.dp(12f).toFloat() }
    private val gripP = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun bar(c: Canvas, cx: Float, h: Float) {
        val bw = context.dp(2.5f).toFloat()
        c.drawRoundRect(cx - bw / 2, h * 0.28f, cx + bw / 2, h * 0.72f, bw, bw, gripP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = context.dp(6f).toFloat()
        val gw = context.dp(16f).toFloat()
        c.drawRoundRect(0f, 0f, w, h, r, r, fillP)
        val avail = max(0f, w - 2 * gw - context.dp(4f))
        val t = TextUtils.ellipsize(text, textP, avail, TextUtils.TruncateAt.END).toString()
        val fm = textP.fontMetrics
        c.drawText(t, gw + context.dp(2f), h / 2 - (fm.ascent + fm.descent) / 2, textP)
        if (sel) {
            val sw = context.dp(2.5f).toFloat()
            strokeP.strokeWidth = sw
            c.drawRoundRect(sw / 2, sw / 2, w - sw / 2, h - sw / 2, r, r, strokeP)
            gripP.color = Color.WHITE
            c.drawRoundRect(0f, 0f, gw, h, r, r, gripP)
            c.drawRoundRect(w - gw, 0f, w, h, r, r, gripP)
            gripP.color = 0xFF777777.toInt()
        } else {
            gripP.color = Color.argb(170, 255, 255, 255)
        }
        bar(c, gw / 2, h); bar(c, w - gw / 2, h)
    }
}

class TimelineView(ctx: Context, private val proj: Project, private val thumbs: ThumbLoader) : FrameLayout(ctx) {
    interface Listener {
        fun onSeek(ms: Long)
        fun onSelect(kind: Int, idx: Int)
        fun onBeginEdit()
        fun onEdited()
        fun onPreviewAt(ms: Long)
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
    private val vsv = ScrollView(ctx)
    private var coverView: View? = null
    private val laneOf = HashMap<Int, Int>()
    private val sectionTop = IntArray(5)
    private val blocks = HashMap<Int, View>()

    private val hsv = object : HorizontalScrollView(ctx) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            if (!programmatic) listener?.onSeek(msAt(l))
        }
    }

    init {
        hsv.isHorizontalScrollBarEnabled = false
        vsv.isVerticalScrollBarEnabled = false
        vsv.isFillViewport = true
        addView(vsv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        vsv.addView(hsv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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

    private fun top(kind: Int) = context.dp(6f)
    private fun laneH() = context.dp(32f)
    private fun laneGap() = context.dp(4f)
    private fun topOf(kind: Int, idx: Int): Int =
        if (kind == VIDEO) top(VIDEO)
        else sectionTop[kind] + (laneOf[kind * 1000 + idx] ?: 0) * (laneH() + laneGap())

    private class Lanes(val lane: IntArray, val count: Int)

    /** Blok yang waktunya bertumpuk dipindah ke baris di bawahnya. */
    private fun lanesOf(spans: List<Pair<Long, Long>>): Lanes {
        val order = spans.indices.sortedBy { spans[it].first }
        val laneEnd = ArrayList<Long>()
        val res = IntArray(spans.size)
        for (i in order) {
            val (st, en) = spans[i]
            var l = laneEnd.indexOfFirst { it <= st }
            if (l < 0) { laneEnd.add(en); l = laneEnd.size - 1 } else laneEnd[l] = en
            res[i] = l
        }
        return Lanes(res, laneEnd.size)
    }

    fun setCover(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
        coverView = v
        refresh()
    }
    private fun hgt(kind: Int) = context.dp(if (kind == VIDEO) 52f else 32f)

    private fun place(v: View, s: Long, e: Long, kind: Int, idx: Int = 0) {
        val minW = if (kind == VIDEO) context.dp(12f) else context.dp(40f)
        val lp = LayoutParams(max(minW, ((e - s) * pps / 1000f).toInt()), hgt(kind))
        lp.leftMargin = xOf(s); lp.topMargin = topOf(kind, idx)
        v.layoutParams = lp
    }

    private fun colorOf(kind: Int) = when (kind) {
        TEXT -> 0xFFE67300.toInt(); AUDIO -> 0xFF2E9E8F.toInt(); else -> 0xFF8E44AD.toInt()
    }

    private fun restyle() {
        for ((key, v) in blocks) {
            val kind = key / 1000
            val selected = selKind == kind && selIdx == key % 1000
            if (v is ThumbView) { v.sel = selected; v.invalidate() }
            else if (v is BlockView) { v.sel = selected; v.invalidate() }
        }
    }

    private fun label(text: String, kind: Int, idx: Int): BlockView {
        val t = BlockView(context, text, colorOf(kind))
        t.setOnClickListener { select(kind, idx) }
        return t
    }

    fun refresh() {
        if (width == 0) return
        half = width / 2
        val total = proj.totalMs()
        // susun baris: teks, audio, overlay ditumpuk ke bawah bila waktunya bertabrakan
        laneOf.clear()
        var y = context.dp(64f)
        val groups = listOf(
            TEXT to proj.texts.map { Pair(it.startMs, it.endMs) },
            AUDIO to proj.audios.map { Pair(it.startMs, it.endMs) },
            OVERLAY to proj.overlays.map { Pair(it.startMs, it.endMs) }
        )
        for ((kind, spans) in groups) {
            sectionTop[kind] = y
            if (spans.isEmpty()) continue
            val ln = lanesOf(spans)
            ln.lane.forEachIndexed { i, l -> laneOf[kind * 1000 + i] = l }
            y += ln.count * (laneH() + laneGap())
        }
        val contentH = y + context.dp(16f)
        content.layoutParams = LayoutParams((width + total * pps / 1000f).toInt(), contentH)
        content.removeAllViews(); blocks.clear()

        coverView?.let { cv ->
            (cv.parent as? ViewGroup)?.removeView(cv)
            val clp = LayoutParams(context.dp(64f), context.dp(52f))
            clp.leftMargin = max(0, half - context.dp(64f) - context.dp(8f))
            clp.topMargin = top(VIDEO)
            content.addView(cv, clp)
        }

        var x = 0L
        proj.clips.forEachIndexed { i, c ->
            val v = ThumbView(context, c, thumbs, pps)
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
            val v = label(t.text, TEXT, i); place(v, t.startMs, t.endMs, TEXT, i); attach(v, TEXT, i)
            content.addView(v); blocks[TEXT * 1000 + i] = v
        }
        proj.audios.forEachIndexed { i, a ->
            val v = label("♪ " + a.name, AUDIO, i); place(v, a.startMs, a.endMs, AUDIO, i); attach(v, AUDIO, i)
            content.addView(v); blocks[AUDIO * 1000 + i] = v
        }
        proj.overlays.forEachIndexed { i, o ->
            val v = label("▣ Overlay", OVERLAY, i); place(v, o.startMs, o.endMs, OVERLAY, i); attach(v, OVERLAY, i)
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
                        if (kind != VIDEO) {
                            val t = if (mode == 2) ge(kind, idx) - 1 else gs(kind, idx)
                            listener?.onPreviewAt(t)
                        }
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
                place(v, a.startMs, a.endMs, AUDIO, idx)
            }
            else -> {
                when (mode) {
                    3 -> { val len = o[1] - o[0]; val ns = cl(o[0] + d, 0, max(0, total - len)); ss(kind, idx, ns); se(kind, idx, ns + len) }
                    1 -> ss(kind, idx, cl(o[0] + d, 0, ge(kind, idx) - MIN))
                    else -> se(kind, idx, cl(o[1] + d, gs(kind, idx) + MIN, max(total, gs(kind, idx) + MIN)))
                }
                place(v, gs(kind, idx), ge(kind, idx), kind, idx)
            }
        }
    }
}
