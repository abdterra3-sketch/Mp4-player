package com.adjm.mp4player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.max

/** Pengali volume untuk fade suara: 0 = senyap, 1 = penuh. */
object AudioFades {
    fun gain(posMs: Long, durMs: Long, fadeInMs: Long, fadeOutMs: Long): Float {
        var g = 1f
        if (fadeInMs > 0 && posMs < fadeInMs) g = minOf(g, max(0L, posMs).toFloat() / fadeInMs)
        if (fadeOutMs > 0 && durMs - posMs < fadeOutMs) g = minOf(g, max(0L, durMs - posMs).toFloat() / fadeOutMs)
        val c = g.coerceIn(0f, 1f)
        return c * c   // kurva halus: fade terasa lebih panjang dan merata
    }
}

/** Pengolah audio: volume tetap + fade masuk/keluar pada satu item (audio PCM 16-bit). */
class FadeGainProcessor(
    private val volume: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val durationUs: Long
) : BaseAudioProcessor() {
    private var frame = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val ch = inputAudioFormat.channelCount
        val sr = inputAudioFormat.sampleRate.toLong()
        val fadeInFrames = fadeInUs * sr / 1_000_000L
        val fadeOutFrames = fadeOutUs * sr / 1_000_000L
        val totalFrames = durationUs * sr / 1_000_000L
        while (inputBuffer.remaining() >= 2 * ch) {
            var g = volume
            if (fadeInFrames > 0 && frame < fadeInFrames) { val f = frame.toFloat() / fadeInFrames; g *= f * f }
            val left = totalFrames - frame
            if (fadeOutFrames > 0 && left < fadeOutFrames) { val f = max(0L, left).toFloat() / fadeOutFrames; g *= f * f }
            for (i in 0 until ch) {
                val s = inputBuffer.getShort().toInt()
                val v = (s * g).toInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
            frame++
        }
        out.flip()
    }
}
