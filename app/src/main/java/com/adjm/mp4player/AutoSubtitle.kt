package com.adjm.mp4player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Mengambil suara dari klip video sebagai PCM mono 16 kHz, dipotong per bagian (chunk). */
object PcmExtractor {
    private class ShortBuf {
        var a = ShortArray(1 shl 16)
        var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v.toShort()
        }
    }

    private fun resample(src: ShortArray, n: Int, rate: Int): ShortArray {
        if (rate == 16000) return src.copyOf(n)
        val outN = (n.toLong() * 16000 / rate).toInt()
        val out = ShortArray(outN)
        val ratio = rate.toDouble() / 16000.0
        for (i in 0 until outN) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, n - 1)
            val i1 = min(i0 + 1, n - 1)
            val f = pos - i0
            out[i] = (src[i0] * (1.0 - f) + src[i1] * f).toInt().toShort()
        }
        return out
    }

    /** @return false bila klip tidak punya jalur suara. onChunk(awalChunkMs, pcm) dipanggil di thread yang sama. */
    fun extract(
        ctx: Context, uri: Uri, startMs: Long, endMs: Long, chunkMs: Long,
        cancelled: () -> Boolean, onChunk: (Long, ShortArray) -> Unit
    ): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return false
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
            ex.selectTrack(track)
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val startUs = startMs * 1000
            val endUs = endMs * 1000
            val info = MediaCodec.BufferInfo()
            val buf = ShortBuf()
            var emitted = 0L
            var inputDone = false
            var done = false
            val deadline = System.currentTimeMillis() + 20 * 60 * 1000L

            fun flush(force: Boolean) {
                val need = (chunkMs * rate / 1000).toInt()
                while (buf.n >= need || (force && buf.n >= rate)) {
                    val take = if (buf.n >= need) need else buf.n
                    onChunk(emitted * 1000 / rate, resample(buf.a, take, rate))
                    emitted += take
                    System.arraycopy(buf.a, take, buf.a, 0, buf.n - take)
                    buf.n -= take
                    if (take != need) break
                }
            }

            while (!done && !cancelled() && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
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
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    if (info.size > 0 && ch > 0 && rate > 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.nativeOrder())
                        val bps = if (isFloat) 4 else 2
                        val frames = info.size / (bps * ch)
                        for (i in 0 until frames) {
                            val tUs = info.presentationTimeUs + i * 1_000_000L / rate
                            var sum = 0f
                            for (c in 0 until ch) sum += if (isFloat) ob.getFloat() * 32768f else ob.getShort().toFloat()
                            if (tUs < startUs) continue
                            if (tUs >= endUs) { done = true; break }
                            buf.add((sum / ch).toInt().coerceIn(-32768, 32767))
                        }
                        flush(false)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                }
            }
            if (!cancelled()) flush(true)
            return true
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }
}

object Wav {
    fun encode(pcm: ShortArray, rate: Int = 16000): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort())            // PCM
        bb.putShort(1.toShort())            // mono
        bb.putInt(rate)
        bb.putInt(rate * 2)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }
}

class Seg(val startSec: Double, val endSec: Double, val text: String)
class GeminiResult(val segs: List<Seg>, val error: String?)

/** Klien sederhana untuk Gemini API: mengenali ucapan lalu menerjemahkannya ke bahasa Inggris. */
object Gemini {
    private const val PROMPT =
        "Transcribe the speech in this audio and translate it into natural English subtitles. " +
        "Split the speech into short subtitle segments of at most about 12 words each. " +
        "Return ONLY a JSON array, no other text. Each item must be an object: " +
        "{\"start\": <number, seconds from the start of this audio>, \"end\": <number, seconds>, \"text\": \"<English subtitle>\"}. " +
        "Timestamps must follow exactly when each phrase is spoken. Ignore music and background noise. " +
        "If there is no speech, return []."

    fun transcribeTranslate(apiKey: String, model: String, wav: ByteArray, cancelled: () -> Boolean): GeminiResult {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", PROMPT))
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "audio/wav")
                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))))))
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
        val bytes = body.toString().toByteArray(Charsets.UTF_8)

        var attempt = 0
        while (true) {
            if (cancelled()) return GeminiResult(emptyList(), "Dibatalkan")
            val (code, text) = post(apiKey, model, bytes)
            if (code == 200) return parse(text)
            if ((code == 429 || code == 503) && attempt < 3) {
                attempt++
                var waited = 0
                while (waited < 20000 && !cancelled()) { Thread.sleep(500); waited += 500 }
                continue
            }
            if (code == -1) return GeminiResult(emptyList(), "Tidak bisa terhubung ke internet: $text")
            return GeminiResult(emptyList(), "Gemini error $code: ${message(text)}")
        }
    }

    private fun post(apiKey: String, model: String, body: ByteArray): Pair<Int, String> {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 180000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            Pair(code, txt)
        } catch (e: Exception) {
            Pair(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun message(resp: String): String = try {
        JSONObject(resp).getJSONObject("error").getString("message")
    } catch (_: Exception) { resp.take(200) }

    private fun parseTime(v: Any?): Double {
        if (v is Number) return v.toDouble()
        if (v is String) {
            var total = 0.0
            for (p in v.trim().split(":")) total = total * 60 + (p.toDoubleOrNull() ?: 0.0)
            return total
        }
        return 0.0
    }

    private fun parse(resp: String): GeminiResult = try {
        val cands = JSONObject(resp).optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            GeminiResult(emptyList(), "Gemini tidak mengembalikan hasil (mungkin tidak ada ucapan atau diblokir)")
        } else {
            val parts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            var t = sb.toString().trim()
            if (t.startsWith("```")) t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = JSONArray(t)
            val list = ArrayList<Seg>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val text = o.optString("text").trim()
                if (text.isEmpty()) continue
                list.add(Seg(parseTime(o.opt("start")), parseTime(o.opt("end")), text))
            }
            GeminiResult(list, null)
        }
    } catch (e: Exception) {
        GeminiResult(emptyList(), "Gagal membaca hasil: ${e.message}")
    }
}
