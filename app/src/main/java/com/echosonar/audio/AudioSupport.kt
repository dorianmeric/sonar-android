package com.echosonar.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.max

/** Capabilities and diagnostics of the device audio path. */
object AudioSupport {

    const val PREFERRED_RATE = 48_000
    const val FALLBACK_RATE = 44_100

    fun minRecordBuffer(sampleRate: Int): Int = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    fun minTrackBuffer(sampleRate: Int): Int = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    /** First sample rate (of the supported set) that can both record and play. */
    fun resolveRate(preferred: Int = PREFERRED_RATE): Int {
        val candidates = LinkedHashSet<Int>().apply {
            add(preferred)
            add(PREFERRED_RATE)
            add(FALLBACK_RATE)
        }
        for (rate in candidates) {
            if (minRecordBuffer(rate) > 0 && minTrackBuffer(rate) > 0) return rate
        }
        return preferred
    }

    fun nativeOutputRate(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val value = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return value?.toIntOrNull() ?: PREFERRED_RATE
    }

    fun mediaVolumePercent(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (maxVolume > 0) (current * 100) / maxVolume else 0
    }

    fun supportsUnprocessed(): Boolean = true

    fun micSourceLabel(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED (raw)"
        MediaRecorder.AudioSource.MIC -> "MIC (system tuning)"
        else -> "source $source"
    }

    /**
     * Human readable diagnostics shown in the INFO sheet. Values that matter for ultrasonic work are
     * called out explicitly, because silent platform restrictions are the usual reason a scan fails.
     */
    fun diagnostics(context: Context): List<Pair<String, String>> {
        val record48 = minRecordBuffer(48_000)
        val track48 = minTrackBuffer(48_000)
        val record44 = minRecordBuffer(44_100)
        val track44 = minTrackBuffer(44_100)
        return listOf(
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Native output rate" to "${nativeOutputRate(context)} Hz",
            "48 kHz capture" to if (record48 > 0) "supported" else "unavailable",
            "48 kHz playback" to if (track48 > 0) "supported" else "unavailable",
            "44.1 kHz capture" to if (record44 > 0) "supported" else "unavailable",
            "44.1 kHz playback" to if (track44 > 0) "supported" else "unavailable",
            "Nyquist at 48 kHz" to "24.0 kHz",
            "Media volume" to "${mediaVolumePercent(context)} %",
            "Unprocessed mic" to if (supportsUnprocessed()) "available" else "not advertised",
        )
    }
}