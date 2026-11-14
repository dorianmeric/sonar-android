package com.echosonar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.max

/**
 * Full-duplex ultrasonic audio path: one AudioTrack playing chirps and one AudioRecord capturing
 * continuously into a ring buffer.
 *
 * Both devices are opened at the same sample rate so capture and playback stay on one clock domain;
 * the small unknown latency between them is measured per ping from the coupling pulse.
 */
class AudioEngine(
    private val context: Context,
    val sampleRateHz: Int,
    private val micSource: Int,
) {
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var recorderThread: Thread? = null
    private val ring = SampleRing(sampleRateHz * 2)
    private val readBuffer = ShortArray(max(512, sampleRateHz / 50))

    @Volatile
    private var running = false

    @Volatile
    var capturePeak: Float = 0f
        private set

    @Volatile
    var readErrors: Int = 0
        private set

    @Volatile
    var shortReads: Int = 0
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    val framesWritten: Long get() = ring.framesWritten

    var recordBufferBytes: Int = 0
        private set
    var trackBufferBytes: Int = 0
        private set
    var trackWriteFailures: Int = 0
        private set

    /** Opens both endpoints. Returns an error message, or null on success. */
    fun open(): String? {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return "Microphone permission has not been granted, so there is nothing to capture."
        }

        val minRecord = AudioSupport.minRecordBuffer(sampleRateHz)
        val minTrack = AudioSupport.minTrackBuffer(sampleRateHz)
        if (minRecord <= 0) {
            return "This device rejects ${sampleRateHz / 1000} kHz mono capture (AudioRecord min buffer $minRecord)."
        }
        if (minTrack <= 0) {
            return "This device rejects ${sampleRateHz / 1000} kHz mono playback (AudioTrack min buffer $minTrack)."
        }

        val captureFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        recordBufferBytes = max(minRecord * 3, sampleRateHz / 4 * 2)
        val recorder = try {
            AudioRecord.Builder()
                .setAudioFormat(captureFormat)
                .setAudioSource(micSource)
                .setBufferSizeInBytes(recordBufferBytes)
                .build()
        } catch (e: Exception) {
            return "AudioRecord could not be created: ${e.message}"
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return "Microphone is unavailable (state ${recorder.state}). Is another app recording?"
        }

        val playbackFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        trackBufferBytes = max(minTrack * 2, sampleRateHz / 10 * 2)
        val player = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(playbackFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBufferBytes)
                .build()
        } catch (e: Exception) {
            recorder.release()
            return "AudioTrack could not be created: ${e.message}"
        }
        if (player.state != AudioTrack.STATE_INITIALIZED) {
            recorder.release()
            player.release()
            return "Speaker is unavailable (state ${player.state})."
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { am.requestAudioFocus(request) }
        focusRequest = request

        record = recorder
        track = player
        ring.clear()
        return null
    }

    fun start(): String? {
        val recorder = record ?: return "Audio engine is not open"
        val player = track ?: return "Audio engine is not open"
        if (running) return null

        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            return "Microphone refused to start (state ${recorder.recordingState})."
        }
        player.play()
        running = true
        isRunning = true

        recorderThread = Thread({ recordLoop(recorder) }, "echosonar-capture").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return null
    }

    private fun recordLoop(recorder: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            val read = try {
                recorder.read(readBuffer, 0, readBuffer.size)
            } catch (e: Exception) {
                readErrors++
                break
            }
            if (read > 0) {
                var peak = 0f
                for (i in 0 until read) {
                    val v = readBuffer[i].toInt()
                    val abs = if (v < 0) -v else v
                    if (abs > peak) peak = abs.toFloat()
                }
                if (peak > capturePeak) capturePeak = peak / 32768f
                ring.write(readBuffer, 0, read)
            } else {
                if (read == 0) shortReads++ else readErrors++
                if (read < 0) break
            }
        }
    }

    /** Queues one chirp burst. Returns false when the write was rejected. */
    fun play(pcm: ShortArray): Boolean {
        val player = track ?: return false
        if (!running) return false
        val written = try {
            player.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            trackWriteFailures++
            return false
        }
        if (written < pcm.size) trackWriteFailures++
        return written == pcm.size
    }

    /**
     * Copies the capture window that starts at absolute frame [startFrame].
     * Returns null when it is not complete yet or has been overwritten.
     */
    fun capture(startFrame: Long, length: Int, destination: FloatArray): Boolean =
        ring.read(startFrame, length, destination)

    fun stop() {
        running = false
        isRunning = false
        val thread = recorderThread
        recorderThread = null
        record?.let {
            runCatching { it.stop() }
        }
        thread?.join(300)
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    fun release() {
        stop()
        record?.let { runCatching { it.release() } }
        track?.let { runCatching { it.release() } }
        record = null
        track = null
        focusRequest?.let { request ->
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.abandonAudioFocusRequest(request) }
        }
        focusRequest = null
        ring.clear()
    }
}