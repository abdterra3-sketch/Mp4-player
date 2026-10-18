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
    private lateinit var panelScroll: HorizontalScrollView
    private lateinit var panelRow: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var editArea: LinearLayout
    private lateinit var coverImg: ImageView
    private lateinit var timeline: TimelineView

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var curMs = 0L
    private var viewOverride: Long? = null
    private val viewMs: Long get() = viewOverride ?: curMs
    private var portrait = true
    private var res720 = true
    private var effectIdx = 0
    private var coverUri: Uri? = null
    private var coverSec = 2
    private var draftPending = false
    private var mode = 0
    private var fullscreen = false
    private val undoStack = ArrayList<Snapshot>()
    private val redoStack = ArrayList<Snapshot>()
    private val textViews = HashMap<Long, AnimTextView>()
    private val ovViews = HashMap<Long, ImageView>()
    private val audioPlayers = HashMap<Long, MediaPlayer>()
    private var exporter: Exporter? = null

    private val effectNames = listOf("Normal", "Hitam-Putih", "Sepia", "Terang", "Kontras", "Negatif")
    private val SEPIA = floatArrayOf(
        0.393f, 0.349f, 0.272f, 0f, 0.769f, 0.686f, 0.534f, 0f,
        0.189f, 0.168f, 0.131f, 0f, 0f, 0f, 0f, 1f
    )

    // ---------- Pemilih file ----------
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> persist(u); addVideo(u) } }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> persist(u); addAudio(u) } }
    private val pickOverlay = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> persist(u); addOverlay(u) } }
    private val pickCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            persist(u)
            coverUri = u; coverImg.setImageBitmap(decodeScaled(this, u, 200))
            toast("Sampul akan tampil $coverSec detik di awal video hasil Save")
        }
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
        panelScroll = findViewById(R.id.panelScroll)
        panelRow = findViewById(R.id.panelRow)
        bottomBar = findViewById(R.id.bottomBar)
        editArea = findViewById(R.id.editArea)
        coverImg = findViewById(R.id.coverImg)

        player = ExoPlayer.Builder(this).build()
        playerView.useController = false
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                toast("Gagal memutar: ${error.errorCodeName}")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { btnPlay.text = if (isPlaying) "❚❚" else "▶" }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { player.pause(); curMs = proj.totalMs(); timeline.scrollToMs(curMs); updateAll() }
            }
        })

        timeline = TimelineView(this, proj, ThumbLoader(this))
        timeline.listener = this
        val coverBox = findViewById<View>(R.id.coverBox)
        timeline.setCover(coverBox)
        findViewById<FrameLayout>(R.id.timelineHolder).addView(timeline, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeBox() }

        btnPlay.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnUndo).setOnClickListener { undo() }
        findViewById<View>(R.id.btnRedo).setOnClickListener { redo() }
        findViewById<View>(R.id.btnFull).setOnClickListener {
            fullscreen = !fullscreen
            editArea.visibility = if (fullscreen) View.GONE else View.VISIBLE
            findViewById<View>(R.id.header).visibility = if (fullscreen) View.GONE else View.VISIBLE
        }
        coverBox.setOnClickListener { onCoverClick() }
        findViewById<View>(R.id.bEdit).setOnClickListener { showMode(1) }
        findViewById<View>(R.id.bAudio).setOnClickListener { showMode(2) }
        findViewById<View>(R.id.bText).setOnClickListener { showMode(3) }
        findViewById<View>(R.id.bFx).setOnClickListener { showMode(4) }
        findViewById<View>(R.id.bOverlay).setOnClickListener { showMode(5) }
        findViewById<View>(R.id.bFormat).setOnClickListener { showMode(6) }
        findViewById<View>(R.id.bSave).setOnClickListener { startExport() }

        setupLayerGestures()
        handler.post(tick)
        if (Draft.exists(this)) offerDraft()
    }

    override fun onPause() { super.onPause(); player.pause(); saveDraft() }

    override fun onStop() { saveDraft(); super.onStop() }

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
        if (viewOverride != null) { viewOverride = null; seekToTimeline(curMs) }
        if (player.isPlaying) player.pause() else {
            if (curMs >= proj.totalMs() - 50) { curMs = 0; seekToTimeline(0); timeline.scrollToMs(0) }
            player.play()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            applyVolume()
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

    private var fxApplied = false

    private fun applyPreviewEffects() {
        val list = buildEffects(effectIdx)
        if (list.isEmpty() && !fxApplied) return
        try {
            player.setVideoEffects(list)
            fxApplied = list.isNotEmpty()
        } catch (e: Exception) {
            toast("Efek pratinjau tidak didukung: ${e.message}")
        }
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
        val c = clipAt(viewMs)
        val s = c?.scale ?: 1f
        playerView.scaleX = s; playerView.scaleY = s
        playerView.translationX = (c?.tx ?: 0f) * box.width
        playerView.translationY = (c?.ty ?: 0f) * box.height
    }

    private fun updateTime() { tvTime.text = "${fmt(curMs)}/${fmt(proj.totalMs())}" }
    private fun updateAll() { updateTime(); syncOverlays(); applyClipTransform() }

    private fun afterChange() {
        viewOverride = null
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
                val vol = min(1f, a.volume)
                mp.setVolume(vol, vol)
                val inRange = curMs >= a.startMs && curMs < a.endMs
                if (playing && inRange) {
                    if (!mp.isPlaying) { mp.seekTo((a.inMs + curMs - a.startMs).toInt()); mp.start() }
                } else if (mp.isPlaying) mp.pause()
            } catch (_: Exception) {}
        }
    }

    // ---------- Timeline listener ----------
    override fun onSeek(ms: Long) {
        viewOverride = null
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
        if (range != null) {
            if (curMs < range.first || curMs >= range.second) onPreviewAt(range.first)
            else if (viewOverride != null) { viewOverride = null; seekToTimeline(curMs) }
        }
        updateAll()
    }

    /** Layar utama menampilkan waktu tertentu tanpa menggeser timeline. */
    override fun onPreviewAt(ms: Long) {
        if (player.isPlaying) player.pause()
        val t = ms.coerceIn(0L, max(0L, proj.totalMs() - 1))
        viewOverride = t
        seekToTimeline(t)
        syncOverlays(); applyClipTransform()
    }

    override fun onBeginEdit() = pushUndo()
    override fun onEdited() = afterChange()
    override fun onAddVideo() { pickVideo.launch(arrayOf("video/*")) }

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

    private fun spanEnd(): Long = curMs + 3000

    private fun addAudio(uri: Uri) {
        if (proj.totalMs() <= 0) { toast("Tambahkan video dulu"); return }
        val d = durationOf(uri)
        if (d <= 0) { toast("Audio tidak bisa dibaca"); return }
        pushUndo()
        val a = AudioItem(uri = uri, name = nameOf(uri), srcMs = d, startMs = curMs)
        proj.audios.add(a)
        timeline.refresh(); timeline.select(TimelineView.AUDIO, proj.audios.size - 1, false)
    }

    private fun addOverlay(uri: Uri) {
        if (proj.totalMs() <= 0) { toast("Tambahkan video dulu"); return }
        pushUndo()
        proj.overlays.add(OverlayItem(uri = uri, startMs = curMs, endMs = spanEnd()))
        timeline.refresh(); timeline.select(TimelineView.OVERLAY, proj.overlays.size - 1, false)
        updateAll()
    }

    /** Kotak teks dengan baris emoji dan tombol tempel (untuk emoji dari notepad). */
    private fun showTextDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        et.setText(initial)
        et.setSelection(initial.length)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

        fun insert(text: String) {
            val st = et.selectionStart.coerceAtLeast(0)
            et.text.insert(st, text)
        }

        val emojis = listOf("😀", "😂", "😍", "🥰", "😎", "🤔", "😢", "😡", "👍", "🙏", "❤️", "🔥", "✨", "🎉", "💯", "🌿", "🌳", "🦋", "🌧️", "☀️")
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (e in emojis) {
            val tv = TextView(this)
            tv.text = e; tv.textSize = 24f
            tv.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            tv.setOnClickListener { insert(e) }
            row.addView(tv)
        }
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(row)

        val paste = TextView(this)
        paste.text = "📋 Tempel dari notepad / clipboard"
        paste.textSize = 14f
        paste.setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
        paste.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (txt.isNullOrEmpty()) toast("Clipboard kosong. Salin teks atau emoji dulu.") else insert(txt)
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20f), dp(8f), dp(20f), 0)
        box.addView(et)
        box.addView(scroll)
        box.addView(paste)

        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ ->
                val s = et.text.toString()
                if (s.isNotBlank()) onOk(s)
            }.setNegativeButton("Batal", null).show()
    }

    private fun addText() {
        if (proj.totalMs() <= 0) { toast("Tambahkan video dulu"); return }
        showTextDialog("Teks", "MASUKKAN TEKS") { s ->
            pushUndo()
            proj.texts.add(TextItem(text = s, startMs = curMs, endMs = spanEnd()))
            timeline.refresh(); timeline.select(TimelineView.TEXT, proj.texts.size - 1, false); updateAll()
        }
    }

    private fun editText() {
        val t = proj.texts.getOrNull(if (timeline.selKind == TimelineView.TEXT) timeline.selIdx else -1)
        if (t == null) { toast("Pilih blok teks di timeline atau sentuh teks di layar"); return }
        showTextDialog("Edit teks", t.text) { s ->
            pushUndo(); t.text = s
            timeline.refresh(); updateAll()
        }
    }

    private fun chooseAnim() {
        val idx = if (timeline.selKind == TimelineView.TEXT) timeline.selIdx else -1
        val t = proj.texts.getOrNull(idx)
        if (t == null) { toast("Pilih blok teks dulu"); return }
        AlertDialog.Builder(this).setTitle("Animasi teks")
            .setSingleChoiceItems(TextAnims.names.toTypedArray(), t.anim) { d, which ->
                pushUndo(); t.anim = which; d.dismiss()
                curMs = t.startMs; viewOverride = null
                timeline.scrollToMs(curMs); seekToTimeline(curMs); updateAll()
                if (which != 0) toast("Tekan ▶ untuk melihat animasi")
            }.show()
    }

    private fun chooseFont() {
        val t = proj.texts.getOrNull(if (timeline.selKind == TimelineView.TEXT) timeline.selIdx else -1)
        if (t == null) { toast("Pilih blok teks dulu"); return }
        AlertDialog.Builder(this).setTitle("Gaya font")
            .setSingleChoiceItems(TextFonts.names.toTypedArray(), t.font) { d, which ->
                pushUndo(); t.font = which; syncOverlays(); d.dismiss()
            }.show()
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
            TimelineView.VIDEO -> proj.clips.removeAt(i)
            TimelineView.TEXT -> proj.texts.removeAt(i)
            TimelineView.AUDIO -> proj.audios.removeAt(i)
            else -> proj.overlays.removeAt(i)
        }
        timeline.clearSelection()
        afterChange()
    }

    // ---------- Draf proyek ----------
    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private fun saveDraft() {
        if (draftPending) return
        val empty = proj.clips.isEmpty() && proj.texts.isEmpty() && proj.audios.isEmpty() && proj.overlays.isEmpty() && coverUri == null
        if (empty) { Draft.clear(this); return }
        Draft.save(this, proj, DraftSettings(portrait, res720, effectIdx, coverUri?.toString(), coverSec, curMs))
    }

    private fun offerDraft() {
        val sum = Draft.summary(this) ?: return
        draftPending = true
        AlertDialog.Builder(this)
            .setTitle("Draf proyek ditemukan")
            .setMessage("Ada draf sebelumnya: ${sum.first} klip video, durasi ${fmt(sum.second)}.\n\nLanjutkan mengedit draf ini?")
            .setCancelable(false)
            .setPositiveButton("Lanjutkan") { _, _ -> draftPending = false; restoreDraft() }
            .setNegativeButton("Mulai baru") { _, _ -> Draft.clear(this); draftPending = false }
            .show()
    }

    private fun restoreDraft() {
        val r = Draft.load(this, proj)
        if (r == null) { toast("Draf tidak bisa dibuka"); return }
        val s = r.settings
        portrait = s.portrait; res720 = s.res720; effectIdx = s.fx; coverSec = s.coverSec
        val cu = s.cover?.let { Uri.parse(it) }
        val bmp = if (cu != null) decodeScaled(this, cu, 200) else null
        if (bmp != null) { coverUri = cu; coverImg.setImageBitmap(bmp) } else { coverUri = null; coverImg.setImageDrawable(null) }
        undoStack.clear(); redoStack.clear()
        timeline.clearSelection()
        curMs = s.curMs.coerceIn(0L, proj.totalMs())
        resizeBox()
        afterChange()
        if (r.dropped > 0) toast("${r.dropped} file tidak bisa dibuka lagi (dihapus atau dipindah) dan dilewati")
    }

    private fun newProject() {
        AlertDialog.Builder(this).setTitle("Proyek baru")
            .setMessage("Semua edit pada proyek ini akan dihapus dan draf dikosongkan. Lanjutkan?")
            .setPositiveButton("Ya, mulai baru") { _, _ ->
                player.pause()
                proj.clips.clear(); proj.texts.clear(); proj.audios.clear(); proj.overlays.clear()
                coverUri = null; coverImg.setImageDrawable(null)
                undoStack.clear(); redoStack.clear()
                timeline.clearSelection()
                curMs = 0L; viewOverride = null
                Draft.clear(this)
                afterChange()
                showMode(0)
            }.setNegativeButton("Batal", null).show()
    }

    // ---------- Warna teks & kotak latar ----------
    private val textColors = listOf(
        0xFFFFFFFFL, 0xFF000000L, 0xFFFF1744L, 0xFFFF9100L, 0xFFFFEA00L, 0xFF00E676L,
        0xFF00E5FFL, 0xFF2979FFL, 0xFFD500F9L, 0xFFFF4081L, 0xFFBDBDBDL, 0xFF8D6E63L
    ).map { it.toInt() }

    private val bgColors = listOf(
        0L, 0xB3000000L, 0xFF000000L, 0xCCFFFFFFL, 0xFFFFFFFFL, 0xFFE53935L,
        0xFFFB8C00L, 0xFFFDD835L, 0xFF43A047L, 0xFF1E88E5L, 0xFF8E24AAL, 0xFFD81B60L
    ).map { it.toInt() }

    private fun selectedText(): TextItem? =
        proj.texts.getOrNull(if (timeline.selKind == TimelineView.TEXT) timeline.selIdx else -1)

    /** Pastikan teks terlihat di layar utama setelah warnanya diubah. */
    private fun showTextNow(t: TextItem) {
        if (viewMs < t.startMs || viewMs >= t.endMs) onPreviewAt((t.startMs + t.endMs) / 2)
        else syncOverlays()
    }

    private fun showPalette(title: String, colors: List<Int>, onPick: (Int) -> Unit) {
        val holder = arrayOfNulls<AlertDialog>(1)
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(dp(10f), dp(6f), dp(10f), 0)
        var row: LinearLayout? = null
        colors.forEachIndexed { i, col ->
            if (i % 6 == 0) {
                row = LinearLayout(this)
                grid.addView(row)
            }
            val cell = TextView(this)
            cell.gravity = Gravity.CENTER
            cell.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (col == 0) Color.TRANSPARENT else col)
                setStroke(dp(2f), 0xFF888888.toInt())
            }
            if (col == 0) { cell.text = "✕"; cell.textSize = 18f }
            val lp = LinearLayout.LayoutParams(dp(44f), dp(44f))
            lp.setMargins(dp(6f), dp(6f), dp(6f), dp(6f))
            cell.setOnClickListener { onPick(col); holder[0]?.dismiss() }
            row!!.addView(cell, lp)
        }
        holder[0] = AlertDialog.Builder(this).setTitle(title).setView(grid).setNegativeButton("Tutup", null).show()
    }

    private fun chooseTextColor() {
        val t = selectedText()
        if (t == null) { toast("Pilih blok teks dulu"); return }
        showPalette("Warna teks", textColors) { col ->
            pushUndo(); t.color = col; showTextNow(t)
        }
    }

    private fun chooseOutline() {
        val t = selectedText()
        if (t == null) { toast("Pilih blok teks dulu"); return }
        pushUndo()
        showTextNow(t)
        val cur = TextRender.outlineWidth(t)
        val label = TextView(this)
        label.textSize = 16f
        label.setPadding(0, dp(6f), 0, dp(4f))
        fun setLabel(level: Int) { label.text = if (level == 0) "Tebal outline: tanpa" else "Tebal outline: $level" }
        val sb = SeekBar(this)
        sb.max = 10
        val lvl = Math.round(cur / 0.03f).coerceIn(0, 10)
        sb.progress = lvl
        setLabel(lvl)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                setLabel(p)
                t.outlineW = p * 0.03f
                syncOverlays()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val colorLabel = TextView(this)
        colorLabel.text = "Warna outline:"
        colorLabel.textSize = 16f
        colorLabel.setPadding(0, dp(12f), 0, dp(2f))
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        var row: LinearLayout? = null
        textColors.forEachIndexed { i, col ->
            if (i % 6 == 0) {
                row = LinearLayout(this)
                grid.addView(row)
            }
            val cell = View(this)
            cell.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(col)
                setStroke(dp(2f), 0xFF888888.toInt())
            }
            val lp = LinearLayout.LayoutParams(dp(40f), dp(40f))
            lp.setMargins(dp(5f), dp(5f), dp(5f), dp(5f))
            cell.setOnClickListener {
                t.outlineColor = col
                if (sb.progress == 0) sb.progress = 3 else syncOverlays()
            }
            row!!.addView(cell, lp)
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22f), dp(8f), dp(22f), 0)
        box.addView(label)
        box.addView(sb)
        box.addView(colorLabel)
        box.addView(grid)
        AlertDialog.Builder(this).setTitle("Outline teks").setView(box)
            .setPositiveButton("OK", null).show()
    }

    private fun chooseBgColor() {
        val t = selectedText()
        if (t == null) { toast("Pilih blok teks dulu"); return }
        showPalette("Kotak latar teks (✕ = tanpa latar)", bgColors) { col ->
            pushUndo(); t.bgColor = col; showTextNow(t)
        }
    }

    // ---------- Sampul ----------
    private fun onCoverClick() {
        if (coverUri == null) { pickCover.launch(arrayOf("image/*")); return }
        AlertDialog.Builder(this).setTitle("Sampul")
            .setItems(arrayOf("Ganti foto sampul", "Lama tampil: $coverSec detik (ubah)", "Hapus sampul")) { _, which ->
                when (which) {
                    0 -> pickCover.launch(arrayOf("image/*"))
                    1 -> AlertDialog.Builder(this).setTitle("Lama sampul tampil")
                        .setItems(arrayOf("1 detik", "2 detik", "3 detik", "5 detik")) { _, i ->
                            coverSec = intArrayOf(1, 2, 3, 5)[i]
                            toast("Sampul tampil $coverSec detik di awal video")
                        }.show()
                    else -> { coverUri = null; coverImg.setImageDrawable(null) }
                }
            }.show()
    }

    // ---------- Volume ----------
    private fun applyVolume() {
        val v = min(1f, clipAt(viewMs)?.volume ?: 1f)
        if (player.volume != v) player.volume = v
    }

    private fun showVolumeDialog(title: String, current: Float, onSet: (Float) -> Unit) {
        val label = TextView(this)
        label.text = "${(current * 100).toInt()}%"
        label.textSize = 22f
        label.gravity = Gravity.CENTER
        val sb = SeekBar(this)
        sb.max = 200
        sb.progress = (current * 100).toInt().coerceIn(0, 200)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = "$p%"
                onSet(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val note = TextView(this)
        note.text = "0% = bisu, 100% = normal. Di atas 100% suara hanya naik pada hasil Save, bukan saat pratinjau."
        note.textSize = 12f
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        for ((name, value) in listOf("🔇 Bisu" to 0, "30%" to 30, "50%" to 50, "100%" to 100, "150%" to 150)) {
            val b = TextView(this)
            b.text = name
            b.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            b.setOnClickListener { sb.progress = value }
            row.addView(b)
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22f), dp(10f), dp(22f), 0)
        box.addView(label)
        box.addView(sb)
        box.addView(row)
        box.addView(note)
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> timeline.refresh() }
            .setOnDismissListener { timeline.refresh() }
            .show()
    }

    private fun volumeForVideo() {
        val c = if (timeline.selKind == TimelineView.VIDEO) proj.clips.getOrNull(timeline.selIdx) else clipAt(viewMs)
        if (c == null) { toast("Tambahkan video dulu"); return }
        pushUndo()
        showVolumeDialog("Volume suara video (latar)", c.volume) { c.volume = it }
    }

    private fun volumeForAudio() {
        val a = if (timeline.selKind == TimelineView.AUDIO) proj.audios.getOrNull(timeline.selIdx) else null
        if (a == null) { toast("Pilih blok audio (warna hijau) di timeline dulu"); return }
        pushUndo()
        showVolumeDialog("Volume audio: ${a.name}", a.volume) { a.volume = it }
    }

    // ---------- Panel mode ----------
    private fun pbtn(label: String, onClick: () -> Unit) {
        val t = TextView(this)
        t.text = label; t.setTextColor(Color.WHITE); t.textSize = 13f; t.gravity = Gravity.CENTER
        t.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        t.background = GradientDrawable().apply { setColor(0xFF2E2E2E.toInt()); cornerRadius = dp(8f).toFloat() }
        t.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(4f), dp(6f), dp(4f), dp(6f))
        panelRow.addView(t, lp)
    }

    private fun showMode(m: Int) {
        mode = m
        if (m == 0) { panelScroll.visibility = View.GONE; bottomBar.visibility = View.VISIBLE; return }
        bottomBar.visibility = View.GONE; panelScroll.visibility = View.VISIBLE
        panelRow.removeAllViews()
        pbtn("⬅ Kembali") { showMode(0) }
        when (m) {
            1 -> {
                pbtn("✂ Potong") { splitAtPlayhead() }
                pbtn("🗑 Hapus") { deleteSelected() }
                pbtn("⟲ Reset zoom") { clipAt(viewMs)?.let { it.scale = 1f; it.tx = 0f; it.ty = 0f }; applyClipTransform() }
                pbtn("🔊 Volume") { volumeForVideo() }
                pbtn("+ Video") { pickVideo.launch(arrayOf("video/*")) }
            }
            2 -> {
                pbtn("+ Audio") { pickAudio.launch(arrayOf("audio/*")) }
                pbtn("🔊 Volume") { volumeForAudio() }
                pbtn("✂ Potong") { splitAtPlayhead() }
                pbtn("🗑 Hapus") { deleteSelected() }
            }
            3 -> {
                pbtn("+ Teks") { addText() }
                pbtn("✎ Edit") { editText() }
                pbtn("Aa Font") { chooseFont() }
                pbtn("🎬 Animasi") { chooseAnim() }
                pbtn("🎨 Warna") { chooseTextColor() }
                pbtn("▭ Latar") { chooseBgColor() }
                pbtn("◌ Outline") { chooseOutline() }
                pbtn("✂ Potong") { splitAtPlayhead() }
                pbtn("🗑 Hapus") { deleteSelected() }
                pbtn("A+") { resizeSelected(1.1f) }
                pbtn("A−") { resizeSelected(0.9f) }
                addNudge()
            }
            4 -> effectNames.forEachIndexed { i, n ->
                pbtn((if (i == effectIdx) "✔ " else "") + n) { effectIdx = i; applyPreviewEffects(); showMode(4) }
            }
            6 -> {
                pbtn((if (portrait) "✔ " else "") + "9:16") { portrait = true; resizeBox(); showMode(6) }
                pbtn((if (!portrait) "✔ " else "") + "16:9") { portrait = false; resizeBox(); showMode(6) }
                pbtn((if (res720) "✔ " else "") + "720p") { res720 = true; showMode(6) }
                pbtn((if (!res720) "✔ " else "") + "1080p") { res720 = false; showMode(6) }
                pbtn("🆕 Proyek baru") { newProject() }
            }
            5 -> {
                pbtn("+ Overlay") { pickOverlay.launch(arrayOf("image/*")) }
                pbtn("✂ Potong") { splitAtPlayhead() }
                pbtn("🗑 Hapus") { deleteSelected() }
                pbtn("+ Besar") { resizeSelected(1.15f) }
                pbtn("− Kecil") { resizeSelected(0.87f) }
                addNudge()
            }
        }
    }

    private fun addNudge() {
        pbtn("◀") { nudge(-0.01f, 0f) }
        pbtn("▶") { nudge(0.01f, 0f) }
        pbtn("▲") { nudge(0f, -0.01f) }
        pbtn("▼") { nudge(0f, 0.01f) }
        pbtn("↔ Tengah") { setCenter(true, false) }
        pbtn("↕ Tengah") { setCenter(false, true) }
        pbtn("⌖ Pusat") { setCenter(true, true) }
    }

    private fun nudge(dx: Float, dy: Float) {
        when (timeline.selKind) {
            TimelineView.TEXT -> proj.texts.getOrNull(timeline.selIdx)?.let { it.nx = (it.nx + dx).coerceIn(0f, 1f); it.ny = (it.ny + dy).coerceIn(0f, 1f) }
            TimelineView.OVERLAY -> proj.overlays.getOrNull(timeline.selIdx)?.let { it.nx = (it.nx + dx).coerceIn(0f, 1f); it.ny = (it.ny + dy).coerceIn(0f, 1f) }
            else -> toast("Pilih teks/overlay dulu")
        }
        syncOverlays()
    }

    private fun setCenter(x: Boolean, y: Boolean) {
        when (timeline.selKind) {
            TimelineView.TEXT -> proj.texts.getOrNull(timeline.selIdx)?.let { if (x) it.nx = 0.5f; if (y) it.ny = 0.5f }
            TimelineView.OVERLAY -> proj.overlays.getOrNull(timeline.selIdx)?.let { if (x) it.nx = 0.5f; if (y) it.ny = 0.5f }
            else -> toast("Pilih teks/overlay dulu")
        }
        syncOverlays()
    }

    private fun resizeSelected(f: Float) {
        when (timeline.selKind) {
            TimelineView.TEXT -> proj.texts.getOrNull(timeline.selIdx)?.let { it.size = (it.size * f).coerceIn(0.02f, 0.3f) }
            TimelineView.OVERLAY -> proj.overlays.getOrNull(timeline.selIdx)?.let { it.size = (it.size * f).coerceIn(0.05f, 1f) }
            else -> toast("Pilih teks/overlay dulu")
        }
        syncOverlays()
    }

    // ---------- Teks & overlay di layar ----------
    private fun selBg() = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1.5f), Color.WHITE) }

    private fun syncOverlays() {
        val w = box.width; val h = box.height
        if (w == 0 || h == 0) return

        val tids = proj.texts.map { it.id }.toSet()
        textViews.keys.filter { it !in tids }.forEach { k -> textViews.remove(k)?.let { layer.removeView(it) } }
        proj.texts.forEachIndexed { i, t ->
            val tv = textViews.getOrPut(t.id) { makeTextView(t.id).also { layer.addView(it) } }
            val vis = viewMs >= t.startMs && viewMs < t.endMs
            tv.visibility = if (vis) View.VISIBLE else View.GONE
            if (vis) {
                val sel = timeline.selKind == TimelineView.TEXT && timeline.selIdx == i
                val (tw, th) = tv.bind(t, w, h, viewMs, sel)
                tv.x = t.nx * w - tw / 2f
                tv.y = t.ny * h - th / 2f
            }
        }

        val oids = proj.overlays.map { it.id }.toSet()
        ovViews.keys.filter { it !in oids }.forEach { k -> ovViews.remove(k)?.let { layer.removeView(it) } }
        proj.overlays.forEachIndexed { i, o ->
            val iv = ovViews.getOrPut(o.id) {
                ImageView(this).apply {
                    adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(decodeScaled(this@MainActivity, o.uri, 720))
                    makeDraggable(this, TimelineView.OVERLAY, o.id)
                }.also { layer.addView(it, FrameLayout.LayoutParams(10, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            }
            val vis = viewMs >= o.startMs && viewMs < o.endMs
            iv.visibility = if (vis) View.VISIBLE else View.GONE
            if (vis) {
                val lp = iv.layoutParams as FrameLayout.LayoutParams
                val tw = max(10, (o.size * w).toInt())
                if (lp.width != tw) { lp.width = tw; iv.layoutParams = lp }
                val d = iv.drawable
                val th = if (d != null && d.intrinsicWidth > 0) tw * d.intrinsicHeight / d.intrinsicWidth else tw
                iv.x = o.nx * w - tw / 2f
                iv.y = o.ny * h - th / 2f
                iv.background = if (timeline.selKind == TimelineView.OVERLAY && timeline.selIdx == i) selBg() else null
            }
        }
    }

    private fun makeTextView(id: Long): AnimTextView {
        val tv = AnimTextView(this)
        tv.layoutParams = FrameLayout.LayoutParams(10, 10)
        makeDraggable(tv, TimelineView.TEXT, id)
        return tv
    }

    private fun indexOf(kind: Int, id: Long) =
        if (kind == TimelineView.TEXT) proj.texts.indexOfFirst { it.id == id } else proj.overlays.indexOfFirst { it.id == id }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(v: View, kind: Int, id: Long) {
        var ox = 0f; var oy = 0f
        v.setOnTouchListener { view, e ->
            val w = box.width.toFloat(); val h = box.height.toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pushUndo()
                    val idx = indexOf(kind, id)
                    if (idx >= 0) timeline.select(kind, idx, false)
                    ox = view.x - e.rawX; oy = view.y - e.rawY
                    guide.on = true; guide.snapX = false; guide.snapY = false; guide.invalidate()
                    syncOverlays()
                }
                MotionEvent.ACTION_MOVE -> {
                    var cx = e.rawX + ox + view.width / 2f
                    var cy = e.rawY + oy + view.height / 2f
                    val th = dp(10f)
                    guide.snapX = abs(cx - w / 2) < th
                    guide.snapY = abs(cy - h / 2) < th
                    if (guide.snapX) cx = w / 2
                    if (guide.snapY) cy = h / 2
                    cx = cx.coerceIn(0f, w); cy = cy.coerceIn(0f, h)
                    view.x = cx - view.width / 2f; view.y = cy - view.height / 2f
                    val idx = indexOf(kind, id)
                    if (idx >= 0) {
                        if (kind == TimelineView.TEXT) { proj.texts[idx].nx = cx / w; proj.texts[idx].ny = cy / h }
                        else { proj.overlays[idx].nx = cx / w; proj.overlays[idx].ny = cy / h }
                    }
                    guide.invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { guide.on = false; guide.invalidate() }
            }
            true
        }
    }

    // ---------- Zoom & geser video ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun setupLayerGestures() {
        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                clipAt(viewMs)?.let { it.scale = (it.scale * d.scaleFactor).coerceIn(1f, 4f); clampPan(it) }
                applyClipTransform(); return true
            }
        })
        var lx = 0f; var ly = 0f; var moved = false
        layer.setOnTouchListener { _, e ->
            sgd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; moved = false }
                MotionEvent.ACTION_MOVE -> if (!sgd.isInProgress && e.pointerCount == 1) {
                    val c = clipAt(viewMs)
                    if (c != null && c.scale > 1f) {
                        c.tx += (e.x - lx) / box.width; c.ty += (e.y - ly) / box.height
                        clampPan(c); applyClipTransform()
                    }
                    if (abs(e.x - lx) + abs(e.y - ly) > 8) moved = true
                    lx = e.x; ly = e.y
                } else moved = true
                MotionEvent.ACTION_UP -> if (!moved) { timeline.clearSelection(); syncOverlays() }
            }
            true
        }
    }

    private fun clampPan(c: Clip) {
        val m = (c.scale - 1f) / 2f
        c.tx = c.tx.coerceIn(-m, m); c.ty = c.ty.coerceIn(-m, m)
    }

    // ---------- Simpan ----------
    /** Bila audio/teks/overlay melewati akhir video, tanyakan dulu: dipotong atau video diperpanjang. */
    private fun startExport() {
        if (proj.clips.isEmpty()) { toast("Belum ada video. Tekan + di timeline"); return }
        val total = proj.totalMs()
        val end = maxOf(
            total,
            proj.texts.maxOfOrNull { it.endMs } ?: 0L,
            proj.overlays.maxOfOrNull { it.endMs } ?: 0L,
            proj.audios.maxOfOrNull { it.endMs } ?: 0L
        )
        if (end - total > 1000) {
            AlertDialog.Builder(this)
                .setTitle("Ada bagian melewati akhir video")
                .setMessage("Durasi video ${fmt(total)}, tetapi audio/teks/overlay berlanjut sampai ${fmt(end)}.\n\nJika dilanjutkan, hasil berdurasi ${fmt(end)} dan frame terakhir ditahan (proses lebih lama).")
                .setPositiveButton("Potong di akhir video") { _, _ -> trimToVideoEnd(); doExport() }
                .setNeutralButton("Tetap ${fmt(end)}") { _, _ -> doExport() }
                .setNegativeButton("Batal", null)
                .show()
        } else doExport()
    }

    private fun trimToVideoEnd() {
        val total = proj.totalMs()
        pushUndo()
        proj.texts.removeAll { it.startMs >= total }
        proj.texts.forEach { if (it.endMs > total) it.endMs = total }
        proj.overlays.removeAll { it.startMs >= total }
        proj.overlays.forEach { if (it.endMs > total) it.endMs = total }
        proj.audios.removeAll { it.startMs >= total }
        proj.audios.forEach { if (it.endMs > total) it.outMs = it.inMs + (total - it.startMs) }
        timeline.clearSelection()
        afterChange()
    }

    private fun doExport() {
        if (proj.clips.isEmpty()) { toast("Belum ada video. Tekan + di timeline"); return }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return
        }
        player.pause()
        val (w, h) = outSize()
        val tmp = File(cacheDir, "out_${System.currentTimeMillis()}.mp4")

        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.max = 100
        val wrap = FrameLayout(this).apply { setPadding(dp(24f), dp(16f), dp(24f), dp(8f)); addView(pb) }
        val ex = Exporter(this)
        exporter = ex
        val dlg = AlertDialog.Builder(this).setTitle("Menggabung & menyimpan video…").setView(wrap)
            .setCancelable(false).setNegativeButton("Batal") { _, _ -> ex.transformer?.cancel() }.create()
        dlg.show()

        var finished = false
        try {
            val t = ex.start(proj, w, h, buildEffects(effectIdx), tmp, coverUri, coverSec * 1000L) { ok, err ->
                finished = true
                dlg.dismiss()
                if (!ok) { toast("Gagal: ${err ?: "tidak diketahui"}"); return@start }
                io.execute {
                    // periksa hasil video sebelum disimpan
                    var durMs = 0L
                    try {
                        val r = MediaMetadataRetriever(); r.setDataSource(tmp.absolutePath)
                        durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        r.release()
                    } catch (_: Exception) {}
                    val sizeKb = tmp.length() / 1024
                    if (sizeKb < 20 || durMs <= 0) {
                        tmp.delete()
                        runOnUiThread { toast("Hasil video kosong (ukuran ${sizeKb} KB). Coba lagi tanpa efek/overlay, lalu kabari saya.") }
                        return@execute
                    }
                    val res = Exporter.saveVideo(this, tmp)
                    val cv = coverUri
                    tmp.delete()
                    runOnUiThread {
                        val sb = StringBuilder()
                        if (res.uri != null || res.error == null) {
                            sb.append("Video: ${res.where}\nDurasi: ${fmt(durMs)}  •  Ukuran: ${"%.1f".format(sizeKb / 1024f)} MB")
                            sb.append("\nBuka aplikasi Galeri → tab Video / album ADJM-MP4.")
                        } else sb.append("Gagal menyimpan video: ${res.error}")
                        if (cv != null) sb.append("\n\nSampul tampil $coverSec detik di awal video.")
                        val b = AlertDialog.Builder(this).setTitle(if (res.uri != null) "Video tersimpan" else "Hasil penyimpanan")
                            .setMessage(sb.toString()).setPositiveButton("OK", null)
                        if (res.uri != null) b.setNeutralButton("Putar") { _, _ ->
                            try {
                                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setDataAndType(res.uri, "video/mp4")
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (e: Exception) { toast("Tidak ada aplikasi pemutar video") }
                        }
                        b.show()
                    }
                }
            }
            val holderP = androidx.media3.transformer.ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (finished) return
                    if (t.getProgress(holderP) != Transformer.PROGRESS_STATE_NOT_STARTED) pb.progress = holderP.progress
                    handler.postDelayed(this, 300)
                }
            }
            handler.post(poll)
        } catch (e: Exception) {
            dlg.dismiss(); toast("Gagal memulai: ${e.message}")
        }
    }
}
