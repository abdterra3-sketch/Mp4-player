package com.adjm.mp4player

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DraftSettings(
    var portrait: Boolean = true,
    var res720: Boolean = true,
    var fx: Int = 0,
    var cover: String? = null,
    var coverSec: Int = 2,
    var curMs: Long = 0L
)

/** Draf proyek: disimpan otomatis saat layar ditutup dan dibuka lagi saat aplikasi dijalankan. */
object Draft {
    private fun file(ctx: Context) = File(ctx.filesDir, "draft.json")

    fun exists(ctx: Context): Boolean = file(ctx).exists()

    fun clear(ctx: Context) { file(ctx).delete() }

    fun save(ctx: Context, p: Project, s: DraftSettings) {
        try {
            val root = JSONObject()
            root.put("v", 1)
            root.put("portrait", s.portrait)
            root.put("res720", s.res720)
            root.put("fx", s.fx)
            root.put("cover", s.cover ?: JSONObject.NULL)
            root.put("coverSec", s.coverSec)
            root.put("curMs", s.curMs)

            val clips = JSONArray()
            for (c in p.clips) clips.put(JSONObject()
                .put("uri", c.uri.toString()).put("src", c.srcMs).put("in", c.inMs).put("out", c.outMs)
                .put("scale", c.scale.toDouble()).put("tx", c.tx.toDouble()).put("ty", c.ty.toDouble())
                .put("vol", c.volume.toDouble()))
            root.put("clips", clips)

            val texts = JSONArray()
            for (t in p.texts) texts.put(JSONObject()
                .put("text", t.text).put("start", t.startMs).put("end", t.endMs)
                .put("nx", t.nx.toDouble()).put("ny", t.ny.toDouble()).put("size", t.size.toDouble())
                .put("font", t.font).put("anim", t.anim)
                .put("color", t.color).put("bg", t.bgColor))
            root.put("texts", texts)

            val audios = JSONArray()
            for (a in p.audios) audios.put(JSONObject()
                .put("uri", a.uri.toString()).put("name", a.name).put("src", a.srcMs)
                .put("start", a.startMs).put("in", a.inMs).put("out", a.outMs)
                .put("vol", a.volume.toDouble()))
            root.put("audios", audios)

            val ovs = JSONArray()
            for (o in p.overlays) ovs.put(JSONObject()
                .put("uri", o.uri.toString()).put("start", o.startMs).put("end", o.endMs)
                .put("nx", o.nx.toDouble()).put("ny", o.ny.toDouble()).put("size", o.size.toDouble()))
            root.put("overlays", ovs)

            val tmp = File(ctx.filesDir, "draft.json.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file(ctx))
        } catch (_: Exception) {
        }
    }

    /** Ringkasan draf: (jumlah klip video, total durasi ms). */
    fun summary(ctx: Context): Pair<Int, Long>? = try {
        val root = JSONObject(file(ctx).readText())
        val arr = root.getJSONArray("clips")
        var total = 0L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            total += o.getLong("out") - o.getLong("in")
        }
        Pair(arr.length(), total)
    } catch (_: Exception) { null }

    class LoadResult(val settings: DraftSettings, val dropped: Int)

    private fun readable(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (_: Exception) { false }

    fun load(ctx: Context, p: Project): LoadResult? {
        return try {
            val root = JSONObject(file(ctx).readText())
            var dropped = 0
            p.clips.clear(); p.texts.clear(); p.audios.clear(); p.overlays.clear()

            val clips = root.getJSONArray("clips")
            for (i in 0 until clips.length()) {
                val o = clips.getJSONObject(i)
                val uri = Uri.parse(o.getString("uri"))
                if (!readable(ctx, uri)) { dropped++; continue }
                p.clips.add(Clip(
                    uri = uri, srcMs = o.getLong("src"), inMs = o.getLong("in"), outMs = o.getLong("out"),
                    scale = o.optDouble("scale", 1.0).toFloat(), tx = o.optDouble("tx", 0.0).toFloat(),
                    ty = o.optDouble("ty", 0.0).toFloat(), volume = o.optDouble("vol", 1.0).toFloat()
                ))
            }
            val texts = root.optJSONArray("texts") ?: JSONArray()
            for (i in 0 until texts.length()) {
                val o = texts.getJSONObject(i)
                p.texts.add(TextItem(
                    text = o.getString("text"), startMs = o.getLong("start"), endMs = o.getLong("end"),
                    nx = o.optDouble("nx", 0.5).toFloat(), ny = o.optDouble("ny", 0.5).toFloat(),
                    size = o.optDouble("size", 0.07).toFloat(), font = o.optInt("font", 0), anim = o.optInt("anim", 0),
                    color = o.optInt("color", -1), bgColor = o.optInt("bg", 0)
                ))
            }
            val audios = root.optJSONArray("audios") ?: JSONArray()
            for (i in 0 until audios.length()) {
                val o = audios.getJSONObject(i)
                val uri = Uri.parse(o.getString("uri"))
                if (!readable(ctx, uri)) { dropped++; continue }
                p.audios.add(AudioItem(
                    uri = uri, name = o.optString("name", "audio"), srcMs = o.getLong("src"),
                    startMs = o.getLong("start"), inMs = o.getLong("in"), outMs = o.getLong("out"),
                    volume = o.optDouble("vol", 1.0).toFloat()
                ))
            }
            val ovs = root.optJSONArray("overlays") ?: JSONArray()
            for (i in 0 until ovs.length()) {
                val o = ovs.getJSONObject(i)
                val uri = Uri.parse(o.getString("uri"))
                if (!readable(ctx, uri)) { dropped++; continue }
                p.overlays.add(OverlayItem(
                    uri = uri, startMs = o.getLong("start"), endMs = o.getLong("end"),
                    nx = o.optDouble("nx", 0.7).toFloat(), ny = o.optDouble("ny", 0.3).toFloat(),
                    size = o.optDouble("size", 0.3).toFloat()
                ))
            }
            val s = DraftSettings(
                portrait = root.optBoolean("portrait", true), res720 = root.optBoolean("res720", true),
                fx = root.optInt("fx", 0),
                cover = if (root.isNull("cover")) null else root.optString("cover"),
                coverSec = root.optInt("coverSec", 2), curMs = root.optLong("curMs", 0L)
            )
            LoadResult(s, dropped)
        } catch (_: Exception) { null }
    }
}
