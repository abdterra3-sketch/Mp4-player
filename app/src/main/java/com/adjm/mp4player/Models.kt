package com.adjm.mp4player

import android.content.Context
import android.net.Uri

object Ids { private var n = 1L; fun next() = n++ }

data class Clip(
    val id: Long = Ids.next(), val uri: Uri, val srcMs: Long,
    var inMs: Long = 0, var outMs: Long = srcMs,
    var scale: Float = 1f, var tx: Float = 0f, var ty: Float = 0f,
    var volume: Float = 1f,
    var fadeInMs: Long = 0L,    // transisi memudar masuk (gelap -> terang)
    var fadeOutMs: Long = 0L    // transisi memudar keluar (terang -> gelap)
) { val durMs: Long get() = outMs - inMs }

data class TextItem(
    val id: Long = Ids.next(), var text: String, var startMs: Long, var endMs: Long,
    var nx: Float = 0.5f, var ny: Float = 0.5f, var size: Float = 0.07f,
    var font: Int = 0, var anim: Int = 0,
    var color: Int = -1,      // warna huruf (ARGB), -1 = putih
    var bgColor: Int = 0,     // warna kotak latar (ARGB), 0 = tanpa latar
    var outlineW: Float = -1f,      // tebal outline (bagian dari ukuran huruf); -1 = otomatis (font grafiti saja)
    var outlineColor: Int = -16777216   // warna outline (ARGB), standar hitam
)

data class OverlayItem(
    val id: Long = Ids.next(), val uri: Uri, var startMs: Long, var endMs: Long,
    var nx: Float = 0.7f, var ny: Float = 0.3f, var size: Float = 0.3f
)

data class AudioItem(
    val id: Long = Ids.next(), val uri: Uri, val name: String, val srcMs: Long,
    var startMs: Long, var inMs: Long = 0, var outMs: Long = srcMs,
    var volume: Float = 1f
) {
    val durMs: Long get() = outMs - inMs
    val endMs: Long get() = startMs + outMs - inMs
}

class Snapshot(val clips: List<Clip>, val texts: List<TextItem>, val audios: List<AudioItem>, val overlays: List<OverlayItem>)

class Project {
    val clips = mutableListOf<Clip>()
    val texts = mutableListOf<TextItem>()
    val audios = mutableListOf<AudioItem>()
    val overlays = mutableListOf<OverlayItem>()
    fun totalMs(): Long = clips.sumOf { it.durMs }
    fun snap() = Snapshot(clips.map { it.copy() }, texts.map { it.copy() }, audios.map { it.copy() }, overlays.map { it.copy() })
    fun restore(s: Snapshot) {
        clips.clear(); clips.addAll(s.clips.map { it.copy() })
        texts.clear(); texts.addAll(s.texts.map { it.copy() })
        audios.clear(); audios.addAll(s.audios.map { it.copy() })
        overlays.clear(); overlays.addAll(s.overlays.map { it.copy() })
    }
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

/** Transisi memudar: pengali kecerahan video (0 = gelap, 1 = normal) pada waktu tertentu di timeline. */
object Fades {
    fun factor(clips: List<Clip>, tMs: Long): Float {
        var start = 0L
        for ((i, c) in clips.withIndex()) {
            val end = start + c.durMs
            if (tMs >= start && (tMs < end || (i == clips.size - 1 && tMs >= end))) {
                var f = 1f
                if (c.fadeInMs > 0 && tMs - start < c.fadeInMs) f = minOf(f, (tMs - start).toFloat() / c.fadeInMs)
                if (c.fadeOutMs > 0) {
                    if (tMs >= end) f = 0f
                    else if (end - tMs < c.fadeOutMs) f = minOf(f, (end - tMs).toFloat() / c.fadeOutMs)
                }
                return f.coerceIn(0f, 1f)
            }
            start = end
        }
        return 1f
    }
}
