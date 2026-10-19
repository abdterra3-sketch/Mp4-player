package com.adjm.mp4player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Mengambil frame video di waktu tertentu dengan decoder langsung.
 * Dipakai untuk thumbnail timeline karena MediaMetadataRetriever di beberapa HP
 * selalu mengembalikan frame pertama pada video yang hanya punya satu keyframe.
 */
object FrameGrabber {

    fun grab(ctx: Context, uri: Uri, timeUs: Long, targetH: Int): Bitmap? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            val rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            ex.selectTrack(track)
            ex.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val reached = info.size > 0 && (info.presentationTimeUs >= timeUs - 20000 || eos)
                    var bmp: Bitmap? = null
                    if (reached) {
                        val img = codec.getOutputImage(oi)
                        if (img != null) { bmp = toBitmap(img, targetH, rotation); img.close() }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (reached) return bmp
                    if (eos) return null
                }
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    /**
     * Mengambil banyak frame dalam SATU kali proses decoder (jauh lebih cepat daripada satu per satu).
     * timesUs harus berurutan naik. Setiap frame yang berhasil dikirim lewat onFrame(waktuUs, bitmap).
     */
    fun grabMany(ctx: Context, uri: Uri, timesUs: List<Long>, targetH: Int, onFrame: (Long, Bitmap) -> Unit) {
        if (timesUs.isEmpty()) return
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return
            val rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            ex.selectTrack(track)
            ex.seekTo(timesUs[0], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idx = 0
            val deadline = System.currentTimeMillis() + 90000
            while (idx < timesUs.size && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val pts = info.presentationTimeUs
                    if (info.size > 0 && pts >= timesUs[idx] - 20000) {
                        val img = codec.getOutputImage(oi)
                        var bmp: Bitmap? = null
                        if (img != null) { bmp = toBitmap(img, targetH, rotation); img.close() }
                        if (bmp != null) {
                            while (idx < timesUs.size && pts >= timesUs[idx] - 20000) { onFrame(timesUs[idx], bmp); idx++ }
                        } else idx++
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (eos) break
                }
            }
        } catch (_: Exception) {
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    private fun toBitmap(img: Image, targetH: Int, rotation: Int): Bitmap? {
        val crop = img.cropRect
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        if (w <= 0 || h <= 0) return null
        val out = ByteArray(w * h * 3 / 2)
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        var pos = 0
        for (r in 0 until h) {
            val base = (r + crop.top) * yP.rowStride + crop.left * yP.pixelStride
            for (c in 0 until w) out[pos++] = yb.get(base + c * yP.pixelStride)
        }
        for (r in 0 until h / 2) {
            for (c in 0 until w / 2) {
                val ui = (r + crop.top / 2) * uP.rowStride + (c + crop.left / 2) * uP.pixelStride
                val vi = (r + crop.top / 2) * vP.rowStride + (c + crop.left / 2) * vP.pixelStride
                out[pos++] = vb.get(vi)
                out[pos++] = ub.get(ui)
            }
        }
        val bos = ByteArrayOutputStream()
        YuvImage(out, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 80, bos)
        val bytes = bos.toByteArray()
        var sample = 1
        while (h / (sample * 2) >= targetH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }
}
