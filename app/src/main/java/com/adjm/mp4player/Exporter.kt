package com.adjm.mp4player

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.*
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

fun decodeScaled(ctx: Context, uri: Uri, maxW: Int): Bitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (o.outWidth / (s * 2) >= maxW) s *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = s }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
} catch (_: Exception) { null }

/** Menggambar teks + overlay gambar pada satu bitmap penuh-layar, sesuai waktu. */
class FrameOverlay(
    private val ctx: Context, private val texts: List<TextItem>,
    private val ovs: List<OverlayItem>, private val w: Int, private val h: Int
) : BitmapOverlay() {
    private var lastKey = ""
    private var last: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val imgs = HashMap<Long, Bitmap?>()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val ms = presentationTimeUs / 1000
        val at = texts.filter { ms >= it.startMs && ms < it.endMs }
        val ao = ovs.filter { ms >= it.startMs && ms < it.endMs }
        val key = at.joinToString(",") { it.id.toString() } + "|" + ao.joinToString(",") { it.id.toString() }
        if (key != lastKey) { last = render(at, ao); lastKey = key }
        return last
    }

    private fun render(at: List<TextItem>, ao: List<OverlayItem>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for (o in ao) {
            val src = imgs.getOrPut(o.id) { decodeScaled(ctx, o.uri, w) } ?: continue
            val tw = max(1, (o.size * w).toInt())
            val th = max(1, (tw * src.height.toFloat() / src.width).toInt())
            val cx = o.nx * w; val cy = o.ny * h
            c.drawBitmap(src, null, RectF(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f), null)
        }
        for (t in at) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = t.size * w; typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(t.size * w / 10f, 0f, 2f, 0xCC000000.toInt())
            }
            val maxW = (w * 0.9f).toInt()
            val desired = ceil(StaticLayout.getDesiredWidth(t.text, tp)).toInt().coerceIn(1, maxW)
            val sl = StaticLayout.Builder.obtain(t.text, 0, t.text.length, tp, desired)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            c.save()
            c.translate(t.nx * w - desired / 2f, t.ny * h - sl.height / 2f)
            sl.draw(c)
            c.restore()
        }
        return bmp
    }
}

class Exporter(private val ctx: Context) {
    var transformer: Transformer? = null

    fun start(proj: Project, outW: Int, outH: Int, fx: List<Effect>, out: File, done: (Boolean, String?) -> Unit): Transformer {
        val total = proj.totalMs()

        // --- Video ---
        val vb = EditedMediaItemSequence.Builder()
        for (c in proj.clips) {
            val mi = MediaItem.Builder().setUri(c.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.inMs).setEndPositionMs(c.outMs).build()
                ).build()
            val ve = mutableListOf<Effect>()
            if (c.scale != 1f || c.tx != 0f || c.ty != 0f) {
                val s = c.scale; val tx = c.tx * 2f; val ty = -c.ty * 2f
                ve.add(MatrixTransformation { _ -> Matrix().apply { postScale(s, s); postTranslate(tx, ty) } })
            }
            ve.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
            ve.addAll(fx)
            vb.addItem(EditedMediaItem.Builder(mi).setEffects(Effects(emptyList(), ve)).build())
        }
        val seqs = mutableListOf(vb.build())

        // --- Audio tambahan ---
        val ab = EditedMediaItemSequence.Builder()
        var cursor = 0L
        var has = false
        for (a in proj.audios.sortedBy { it.startMs }) {
            val s = max(a.startMs, cursor)
            if (s >= total) continue
            val shift = s - a.startMs
            val inMs = a.inMs + shift
            val len = min(a.outMs - inMs, total - s)
            if (len < 200) continue
            val gap = s - cursor
            if (gap > 0) ab.addGap(gap * 1000)
            val mi = MediaItem.Builder().setUri(a.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(inMs).setEndPositionMs(inMs + len).build()
                ).build()
            ab.addItem(EditedMediaItem.Builder(mi).setRemoveVideo(true).build())
            cursor = s + len
            has = true
        }
        if (has) seqs.add(ab.build())

        val cb = Composition.Builder(seqs)
        if (proj.texts.isNotEmpty() || proj.overlays.isNotEmpty()) {
            val ov = FrameOverlay(ctx, proj.texts.map { it.copy() }, proj.overlays.map { it.copy() }, outW, outH)
            cb.setEffects(Effects(emptyList(), listOf(OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(ov)))))
        }

        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { done(true, null) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done(false, exportException.message)
                }
            }).build()
        transformer = t
        t.start(cb.build(), out.absolutePath)
        return t
    }

    companion object {
        private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        fun saveVideo(ctx: Context, src: File): Boolean = try {
            val name = "ADJM_${stamp()}.mp4"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ADJM-MP4")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
                cv.clear(); cv.put(MediaStore.Video.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, cv, null, null)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ADJM-MP4")
                dir.mkdirs()
                val f = File(dir, name)
                src.copyTo(f, true)
                MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("video/mp4"), null)
            }
            true
        } catch (e: Exception) { false }

        /** Simpan foto sampul (crop tengah sesuai rasio video) ke galeri. */
        fun saveCover(ctx: Context, uri: Uri, outW: Int, outH: Int): Boolean = try {
            val src = decodeScaled(ctx, uri, outW)!!
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val da = outW.toFloat() / outH; val sa = src.width.toFloat() / src.height
            val r = if (sa > da) { val nw = (src.height * da).toInt(); val x = (src.width - nw) / 2; Rect(x, 0, x + nw, src.height) }
            else { val nh = (src.width / da).toInt(); val y = (src.height - nh) / 2; Rect(0, y, src.width, y + nh) }
            Canvas(bmp).drawBitmap(src, r, Rect(0, 0, outW, outH), null)
            val name = "ADJM_sampul_${stamp()}.jpg"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ADJM-MP4")
                }
                val u = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                ctx.contentResolver.openOutputStream(u)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ADJM-MP4")
                dir.mkdirs()
                val f = File(dir, name)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
            }
            true
        } catch (e: Exception) { false }
    }
}
