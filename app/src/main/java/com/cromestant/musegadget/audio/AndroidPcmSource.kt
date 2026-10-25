package com.cromestant.musegadget.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioRecord PCM source: 16 kHz mono 16-bit. Read loop on a worker
 * thread into a growable buffer. Never throws: recorder null
 * (getMinBufferSize ERROR_BAD_VALUE) or SecurityException (RECORD_AUDIO
 * not granted) just reports unavailable via start() == false.
 */
class AndroidPcmSource(
    private val sampleRate: Int = 16000,
) : PcmSource {
    private var recorder: AudioRecord? = null
    private var reader: Thread? = null
    private val running = AtomicBoolean(false)
    private val buf = ByteArrayOutputStream()
    private val bufLock = Any()
    /** Recent RMS amplitude (0.0-1.0) for voice activity detection. */
    @Volatile private var currentAmplitude = 0.0
    /** Max amplitude seen during current recording (for mistake detection). */
    @Volatile private var maxAmplitude = 0.0

    override fun start(): Boolean {
        synchronized(bufLock) { buf.reset() }
        maxAmplitude = 0.0
        currentAmplitude = 0.0
        val minBuf = try {
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (e: Exception) {
            return false
        }
        if (minBuf <= 0) return false // ERROR_BAD_VALUE / ERROR: no recorder
        val rec = try {
            AudioRecord(
                // MIC, not VOICE_RECOGNITION: the recognition source is
                // unrouted on Portal builds and yields all-zero buffers.
                // Raw MIC captures real (if quiet) room audio.
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: SecurityException) {
            return false // RECORD_AUDIO not granted; caller checks permission first
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (e: Exception) { }
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            try { rec.release() } catch (e2: Exception) { }
            return false
        }
        recorder = rec
        running.set(true)
        reader = Thread({
            val chunk = ByteArray(minBuf)
            while (running.get()) {
                val n = try {
                    rec.read(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    break
                }
                if (n <= 0) continue
                synchronized(bufLock) { buf.write(chunk, 0, n) }
                // Compute RMS amplitude for VAD (16-bit PCM).
                var sum = 0.0
                val samples = n / 2
                if (samples > 0) {
                    for (i in 0 until samples) {
                        val lo = chunk[i * 2].toInt() and 0xFF
                        val hi = chunk[i * 2 + 1].toInt()
                        val sample = (hi shl 8) or lo
                        val normalized = sample / 32768.0
                        sum += normalized * normalized
                    }
                    currentAmplitude = kotlin.math.sqrt(sum / samples)
                    if (currentAmplitude > maxAmplitude) maxAmplitude = currentAmplitude
                }
            }
        }, "pcm-capture").also { it.isDaemon = true; it.start() }
        return true
    }

    /** Current RMS amplitude (0.0-1.0). Used for voice activity detection. */
    override fun getAmplitude(): Double = currentAmplitude
    /** Max amplitude during current recording. Used for mistake detection. */
    override fun getMaxAmplitude(): Double = maxAmplitude

    override fun stopAndDrain(): ByteArray {
        running.set(false)
        try { reader?.join(1000) } catch (e: Exception) { }
        reader = null
        try { recorder?.stop() } catch (e: Exception) { }
        try { recorder?.release() } catch (e: Exception) { }
        recorder = null
        synchronized(bufLock) { return buf.toByteArray() }
    }
}
