package com.adjm.mp4player

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Transformer
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.Util
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity(), TimelineView.Listener {

    companion object { const val MIN = 300L }

    private val proj = Project()
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var holder: FrameLayout
    private lateinit var box: FrameLayout
    private lateinit var layer: FrameLayout
    private lateinit var guide: GuideView
    private lateinit var tvTime: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnRatio: TextView
    private lateinit var btnRes: TextView
    private lateinit var panelScroll: HorizontalScrollView
    private lateinit var panelRow: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var editArea: LinearLayout
    private lateinit var coverImg: ImageView
    private lateinit var timeline: TimelineView

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var curMs = 0L
    private var portrait = true
    private var res720 = true
    private var effectIdx = 0
    private var coverUri: Uri? = null
    private var mode = 0
    private var fullscreen = false
    private val undoStack = ArrayList<Snapshot>()
    private val redoStack = ArrayList<Snapshot>()
    private val textViews = HashMap<Long, TextView>()
    private val ovViews = HashMap<Long, ImageView>()
    private val audioPlayers = HashMap<Long, MediaPlayer>()
    private var exporter: Exporter? = null

    private val effectNames = listOf("Normal", "Hitam-Putih", "Sepia", "Terang", "Kontras", "Negatif")
    private val SEPIA = floatArrayOf(
        0.393f, 0.349f, 0.272f, 0f, 0.769f, 0.686f, 0.534f, 0f,
        0.189f, 0.168f, 0.131f, 0f, 0f, 0f, 0f, 1f
    )

    // ---------- Pemilih file ----------
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> addVideo(u) } }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> addAudio(u) } }
    private val pickOverlay = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> addOverlay(u) } }
    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { coverUri = u; coverImg.setImageBitmap(decodeScaled(this, u, 200)) }
    }
    private val permWrite = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startExport() else toast("Izin penyimpanan diperlukan untuk menyimpan ke galeri")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- Siklus hidup ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.playerView)
        holder = findViewById(R.id.previewHolder)
        box = findViewById(R.id.previewBox)
        layer = findViewById(R.id.layer)
        guide = findViewById(R.id.guide)
        tvTime = findViewById(R.id.tvTime)
        btnPlay = findViewById(R.id.btnPlay)
        btnRatio = findViewById(R.id.btnRatio)
        btnRes = findViewById(R.id.btnRes)
        panelScroll = findViewById(R.id.panelScroll)
        panelRow = findViewById(R.id.panelRow)
        bottomBar = findViewById(R.id.bottomBar)
        editArea = findViewById(R.id.editArea)
        coverImg = findViewById(R.id.coverImg)

        player = ExoPlayer.Builder(this).build()
        playerView.useController = false
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { btnPlay.text = if (isPlaying) "❚❚" else "▶" }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { player.pause(); curMs = proj.totalMs(); timeline.scrollToMs(curMs); updateAll() }
            }
        })

        timeline = TimelineView(this, proj, ThumbLoader(this))
        timeline.listener = this
        findViewById<FrameLayout>(R.id.timelineHolder).addView(timeline, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeBox() }

        btnPlay.setOnClickListener { togglePlay() }
        btnRatio.setOnClickListener { portrait = !portrait; btnRatio.text = if (portrait) "9:16" else "16:9"; resizeBox() }
        btnRes.setOnClickListener { res720 = !res720; btnRes.text = if (res720) "720p" else "1080p" }
        findViewById<View>(R.id.btnUndo).setOnClickListener { undo() }
        findViewById<View>(R.id.btnRedo).setOnClickListener { redo() }
        findViewById<View>(R.id.btnFull).setOnClickListener {
            fullscreen = !fullscreen
            editArea.visibility = if (fullscreen) View.GONE else View.VISIBLE
            findViewById<View>(R.id.header).visibility = if (fullscreen) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.coverBox).setOnClickListener { pickCover.launch("image/*") }
        findViewById<View>(R.id.bEdit).setOnClickListener { showMode(1) }
        findViewById<View>(R.id.bAudio).setOnClickListener { showMode(2) }
        findViewById<View>(R.id.bText).setOnClickListener { showMode(3) }
        findViewById<View>(R.id.bFx).setOnClickListener { showMode(4) }
        findViewById<View>(R.id.bOverlay).setOnClickListener { showMode(5) }
        findViewById<View>(R.id.bSave).setOnClickListener { startExport() }

        setupLayerGestures()
        handler.post(tick)
    }

    override fun onPause() { super.onPause(); player.pause() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        audioPlayers.values.forEach { try { it.release() } catch (_: Exception) {} }
        player.release()
        super.onDestroy()
    }

    // ---------- Ukuran preview ----------
    private fun resizeBox() {
        val hw = holder.width.toFloat(); val hh = holder.height.toFloat()
        if (hw == 0f || hh == 0f) return
        val ratio = if (portrait) 9f / 16f else 16f / 9f
        var bw = hw; var bh = bw / ratio
        if (bh > hh) { bh = hh; bw = bh * ratio }
        val lp = box.layoutParams as FrameLayout.LayoutParams
        if (lp.width != bw.toInt() || lp.height != bh.toInt()) {
            lp.width = bw.toInt(); lp.height = bh.toInt(); box.layoutParams = lp
        }
        box.post { updateAll() }
    }

    private fun outSize(): Pair<Int, Int> {
        val s = if (res720) 720 else 1080
        val l = s * 16 / 9
        return if (portrait) Pair(s, l) else Pair(l, s)
    }

    // ---------- Putar ----------
    private fun togglePlay() {
        if (proj.clips.isEmpty()) { toast("Tambahkan video dulu dengan tombol +"); return }
        if (player.isPlaying) player.pause() else {
            if (curMs >= proj.totalMs() - 50) { curMs = 0; seekToTimeline(0); timeline.scrollToMs(0) }
            player.play()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (player.isPlaying) {
                curMs = timelinePos()
                timeline.scrollToMs(curMs)
                updateTime(); syncOverlays(); applyClipTransform()
            }
            syncAudio()
            handler.postDelayed(this, 40)
        }
    }

    private fun timelinePos(): Long {
        if (proj.clips.isEmpty()) return 0
        val idx = player.currentMediaItemIndex.coerceIn(0, proj.clips.size - 1)
        var base = 0L
        for (i in 0 until idx) base += proj.clips[i].durMs
        return base + max(0L, player.currentPosition)
    }

    private fun seekToTimeline(ms: Long) {
        if (proj.clips.isEmpty()) return
        var base = 0L
        for ((i, c) in proj.clips.withIndex()) {
            if (ms < base + c.durMs || i == proj.clips.size - 1) {
                player.seekTo(i, (ms - base).coerceIn(0, c.durMs)); return
            }
            base += c.durMs
        }
    }

    private fun rebuildPlayer() {
        if (proj.clips.isEmpty()) { player.clearMediaItems(); return }
        val items = proj.clips.map {
            MediaItem.Builder().setUri(it.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(it.inMs).setEndPositionMs(it.outMs).build()
            ).build()
        }
        player.setMediaItems(items)
        player.prepare()
        seekToTimeline(curMs)
        applyPreviewEffects()
    }

    private fun applyPreviewEffects() {
        try { player.setVideoEffects(buildEffects(effectIdx)) } catch (_: Exception) {}
    }

    private fun buildEffects(i: Int): List<Effect> = when (i) {
        1 -> listOf(RgbFilter.createGrayscaleFilter())
        2 -> listOf(RgbMatrix { _, _ -> SEPIA })
        3 -> listOf(Brightness(0.2f))
        4 -> listOf(Contrast(0.4f))
        5 -> listOf(RgbFilter.createInvertedFilter())
        else -> emptyList()
    }

    private fun clipAt(ms: Long): Clip? {
        var base = 0L
        for (c in proj.clips) { if (ms < base + c.durMs) return c; base += c.durMs }
        return proj.clips.lastOrNull()
    }

    private fun applyClipTransform() {
        val c = clipAt(curMs)
        val s = c?.scale ?: 1f
        playerView.scaleX = s; playerView.scaleY = s
        playerView.translationX = (c?.tx ?: 0f) * box.width
        playerView.translationY = (c?.ty ?: 0f) * box.height
    }

    private fun updateTime() { tvTime.text = "${fmt(curMs)} / ${fmt(proj.totalMs())}" }
    private fun updateAll() { updateTime(); syncOverlays(); applyClipTransform() }

    private fun afterChange() {
        curMs = curMs.coerceIn(0, proj.totalMs())
        rebuildPlayer(); timeline.refresh(); timeline.scrollToMs(curMs); updateAll()
    }

    // ---------- Audio preview ----------
    private fun syncAudio() {
        val ids = proj.audios.map { it.id }.toSet()
        audioPlayers.keys.filter { it !in ids }.forEach { k ->
            audioPlayers.remove(k)?.let { try { it.release() } catch (_: Exception) {} }
        }
        val playing = player.isPlaying
        for (a in proj.audios) {
            val mp = audioPlayers.getOrPut(a.id) {
                try { MediaPlayer().apply { setDataSource(this@MainActivity, a.uri); prepare() } } catch (_: Exception) { return@getOrPut MediaPlayer() }
            }
            try {
                val inRange = curMs >= a.startMs && curMs < a.endMs
                if (playing && inRange) {
                    if (!mp.isPlaying) { mp.seekTo((a.inMs + curMs - a.startMs).toInt()); mp.start() }
                } else if (mp.isPlaying) mp.pause()
            } catch (_: Exception) {}
        }
    }

    // ---------- Timeline listener ----------
    override fun onSeek(ms: Long) {
        if (player.isPlaying) player.pause()
        curMs = ms.coerceIn(0, proj.totalMs())
        seekToTimeline(curMs)
        updateAll()
    }

    override fun onSelect(kind: Int, idx: Int) {
        val range: Pair<Long, Long>? = when (kind) {
            TimelineView.TEXT -> proj.texts.getOrNull(idx)?.let { Pair(it.startMs, it.endMs) }
            TimelineView.OVERLAY -> proj.overlays.getOrNull(idx)?.let { Pair(it.startMs, it.endMs) }
            else -> null
        }
        if (range != null && (curMs < range.first || curMs >= range.second)) {
            curMs = range.first; timeline.scrollToMs(curMs); seekToTimeline(curMs)
        }
        updateAll()
    }

    override fun onBeginEdit() = pushUndo()
    override fun onEdited() = afterChange()
    override fun onAddVideo() { pickVideo.launch("video/*") }

    // ---------- Undo / redo ----------
    private fun pushUndo() { undoStack.add(proj.snap()); if (undoStack.size > 30) undoStack.removeAt(0); redoStack.clear() }
    private fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(proj.snap()); proj.restore(undoStack.removeAt(undoStack.size - 1)); timeline.clearSelection(); afterChange()
    }
    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(proj.snap()); proj.restore(redoStack.removeAt(redoStack.size - 1)); timeline.clearSelection(); afterChange()
    }

    // ---------- Tambah media ----------
    private fun durationOf(uri: Uri): Long = try {
        val r = MediaMetadataRetriever(); r.setDataSource(this, uri)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release(); d
    } catch (_: Exception) { 0L }

    private fun nameOf(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else "audio"
        } ?: "audio"
    } catch (_: Exception) { "audio" }

    private fun addVideo(uri: Uri) {
        val d = durationOf(uri)
        if (d <= 0) { toast("Video tidak bisa dibaca"); return }
        pushUndo()
        proj.clips.add(Clip(uri = uri, srcMs = d))
        afterChange()
    }

    private fun spanEnd(): Long {
        val total = proj.totalMs()
        return if (total - curMs >= MIN) min(curMs + 3000, total) else total
    }

    private fun addAudio(uri: Uri) {
        val total = proj.totalMs()
        if (total - curMs < MIN) { toast("Geser garis putih ke posisi sebelum akhir video"); return }
        val d = durationOf(uri)
        if (d <= 0) { toast("Audio tidak bisa dibaca"); return }
        pushUndo()
        val a = AudioItem(uri = uri, name = nameOf(uri), srcMs = d, startMs = curMs)
        a.outMs = min(d, total - curMs)
        proj.audios.add(a)
        timeline.refresh(); timeline.select(TimelineView.AUDIO, proj.audios.size - 1, false)
    }

    private fun addOverlay(uri: Uri) {
        if (proj.totalMs() - curMs < MIN) { toast("Geser garis putih ke posisi sebelum akhir video"); return }
        pushUndo()
        proj.overlays.add(OverlayItem(uri = uri, startMs = curMs, endMs = spanEnd()))
        timeline.refresh(); timeline.select(TimelineView.OVERLAY, proj.overlays.size - 1, false)
        updateAll()
    }

    private fun addText() {
        if (proj.totalMs() - curMs < MIN) { toast("Tambahkan video dulu / geser garis putih ke dalam video"); return }
        val et = EditText(this)
        et.setText("MASUKKAN TEKS"); et.selectAll()
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        AlertDialog.Builder(this).setTitle("Teks").setView(et)
            .setPositiveButton("OK") { _, _ ->
                val s = et.text.toString().ifBlank { return@setPositiveButton }
                pushUndo()
                proj.texts.add(TextItem(text = s, startMs = curMs, endMs = spanEnd()))
                timeline.refresh(); timeline.select(TimelineView.TEXT, proj.texts.size - 1, false); updateAll()
            }.setNegativeButton("Batal", null).show()
    }

    private fun editText() {
        val t = proj.texts.getOrNull(if (timeline.selKind == TimelineView.TEXT) timeline.selIdx else -1)
        if (t == null) { toast("Pilih blok teks di timeline atau sentuh teks di layar"); return }
        val et = EditText(this); et.setText(t.text)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        AlertDialog.Builder(this).setTitle("Edit teks").setView(et)
            .setPositiveButton("OK") { _, _ ->
                pushUndo(); t.text = et.text.toString().ifBlank { t.text }
                timeline.refresh(); updateAll()
            }.setNegativeButton("Batal", null).show()
    }

    // ---------- Edit: potong / hapus ----------
    private fun splitAtPlayhead() {
        when (timeline.selKind) {
            TimelineView.TEXT -> {
                val t = proj.texts.getOrNull(timeline.selIdx) ?: return
                if (curMs - t.startMs < MIN || t.endMs - curMs < MIN) { toast("Posisi potong terlalu dekat tepi"); return }
                pushUndo(); val n = t.copy(id = Ids.next(), startMs = curMs); t.endMs = curMs
                proj.texts.add(timeline.selIdx + 1, n)
            }
            TimelineView.OVERLAY -> {
                val o = proj.overlays.getOrNull(timeline.selIdx) ?: return
                if (curMs - o.startMs < MIN || o.endMs - curMs < MIN) { toast("Posisi potong terlalu dekat tepi"); return }
                pushUndo(); val n = o.copy(id = Ids.next(), startMs = curMs); o.endMs = curMs
                proj.overlays.add(timeline.selIdx + 1, n)
            }
            TimelineView.AUDIO -> {
                val a = proj.audios.getOrNull(timeline.selIdx) ?: return
                if (curMs - a.startMs < MIN || a.endMs - curMs < MIN) { toast("Posisi potong terlalu dekat tepi"); return }
                pushUndo()
                val cutIn = a.inMs + (curMs - a.startMs)
                val n = a.copy(id = Ids.next(), startMs = curMs, inMs = cutIn)
                a.outMs = cutIn
                proj.audios.add(timeline.selIdx + 1, n)
            }
            else -> {
                var base = 0L
                for ((i, c) in proj.clips.withIndex()) {
                    if (curMs < base + c.durMs) {
                        val local = curMs - base
                        if (local < MIN || c.durMs - local < MIN) { toast("Posisi potong terlalu dekat tepi klip"); return }
                        pushUndo()
                        val cut = c.inMs + local
                        val n = c.copy(id = Ids.next(), inMs = cut)
                        c.outMs = cut
                        proj.clips.add(i + 1, n)
                        timeline.select(TimelineView.VIDEO, i + 1, false)
                        break
                    }
                    base += c.durMs
                }
            }
        }
        afterChange()
    }

    private fun deleteSelected() {
        val i = timeline.selIdx
        val kind = timeline.selKind
        val valid = when (kind) {
            TimelineView.VIDEO -> i in proj.clips.indices
            TimelineView.TEXT -> i in proj.texts.indices
            TimelineView.AUDIO -> i in proj.audios.indices
            TimelineView.OVERLAY -> i in proj.overlays.indices
            else -> false
        }
        if (!valid) {
            toast("Pilih blok di timeline dulu")
            return
        }
        pushUndo()
        when (kind) {
            TimelineView.VIDEO -
