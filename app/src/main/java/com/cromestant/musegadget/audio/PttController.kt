package com.cromestant.musegadget.audio

import java.util.Base64

/** Clock for PTT timing; injectable for tests. */
interface PttClock {
    fun nowMs(): Long
}

object SystemPttClock : PttClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** PCM source; Android impl wraps AudioRecord, tests use a fake. */
interface PcmSource {
    /** Begin capture; returns false when capture is unavailable. */
    fun start(): Boolean

    /** Stop capture and return all PCM bytes recorded so far. */
    fun stopAndDrain(): ByteArray

    /** Current RMS amplitude (0.0-1.0) for VAD. Default 0.0 for fakes. */
    fun getAmplitude(): Double = 0.0

    /** Max amplitude during recording, for mistake detection. Default 0.0. */
    fun getMaxAmplitude(): Double = 0.0
}

data class CaptureInfo(val durationMs: Long, val pcmBytes: Int, val b64Chars: Int)

/**
 * Tap-to-start -> tap-to-stop PTT state machine (no VAD).
 * Min 0.3s (shorter => discard + onTooShort), max 15s auto-stop.
 * Pure JVM, thread-safe. Sample rate fixed at 16 kHz mono 16-bit.
 */
class PttController(
    private val clock: PttClock = SystemPttClock,
    private val source: PcmSource,
    private val transport: VoiceTransport,
    private val sampleRate: Int = 16000,
    private val onAutoStop: (() -> Unit)? = null,
    private val onTooShort: (() -> Unit)? = null,
) {
    companion object {
        const val MIN_MS = 300L
        const val MAX_MS = 15000L
        /** Silence threshold: amplitude below this counts as silence. */
        const val SILENCE_THRESHOLD = 0.02
        /** Auto-stop after this much continuous silence (tap mode only). */
        const val SILENCE_AUTO_STOP_MS = 1500L
        /** Mistake detection: discard if max amplitude never exceeded this. */
        const val MIN_VOICE_AMPLITUDE = 0.03
    }

    enum class State { IDLE, RECORDING }

    private val lock = Any()

    @Volatile private var state = State.IDLE
    private var startMs = 0L
    /** When true, VAD auto-stop is disabled (user is holding the button). */
    @Volatile var holdMode = false
    private var vadThread: Thread? = null
    @Volatile private var onVadAutoStop: (() -> Unit)? = null

    /** Set callback for VAD auto-stop (UI update). */
    fun setOnVadAutoStop(cb: (() -> Unit)?) { onVadAutoStop = cb }
    @Volatile var lastCaptureInfo: CaptureInfo? = null
        private set

    fun currentState(): State = state

    /** Begin capture. Returns false if already recording or source unavailable. */
    fun start(): Boolean {
        synchronized(lock) {
            if (state == State.RECORDING) return false
            if (!source.start()) return false
            startMs = clock.nowMs()
            state = State.RECORDING
            startVadMonitor()
            return true
        }
    }

    /**
     * Start the VAD monitor thread. Watches amplitude during recording;
     * auto-stops after SILENCE_AUTO_STOP_MS of continuous silence,
     * unless holdMode is true (user holding the button).
     */
    private fun startVadMonitor() {
        stopVadMonitor()
        val t = Thread({
            var silenceStart = 0L
            try {
                while (state == State.RECORDING) {
                    Thread.sleep(100)
                    if (state != State.RECORDING) break
                    if (holdMode) {
                        silenceStart = 0L
                        continue
                    }
                    val amp = try { source.getAmplitude() } catch (_: Exception) { 0.0 }
                    val now = clock.nowMs()
                    // Don't trigger VAD in the first second (let user start speaking)
                    if (now - startMs < 1000) {
                        silenceStart = 0L
                        continue
                    }
                    if (amp < SILENCE_THRESHOLD) {
                        if (silenceStart == 0L) silenceStart = now
                        if (now - silenceStart >= SILENCE_AUTO_STOP_MS) {
                            System.out.println("MuseGadgetVoice: VAD auto-stop on silence")
                            stop(auto = true)
                            onVadAutoStop?.invoke()
                            break
                        }
                    } else {
                        silenceStart = 0L
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "ptt-vad-monitor")
        t.isDaemon = true
        t.start()
        vadThread = t
    }

    private fun stopVadMonitor() {
        try { vadThread?.interrupt() } catch (_: Exception) { }
        vadThread = null
    }

    /**
     * Stop capture. [auto] marks the 15s auto-stop path (same handling;
     * fires onAutoStop after a valid capture).
     * Returns true when a voice note was produced.
     */
    fun stop(auto: Boolean = false): Boolean {
        val pcm: ByteArray
        val elapsed: Long
        val maxAmp: Double
        synchronized(lock) {
            if (state != State.RECORDING) return false
            elapsed = clock.nowMs() - startMs
            maxAmp = try { source.getMaxAmplitude() } catch (_: Exception) { 1.0 }
            pcm = try {
                source.stopAndDrain()
            } finally {
                state = State.IDLE
                stopVadMonitor()
            }
        }
        if (elapsed < MIN_MS) {
            onTooShort?.invoke()
            return false
        }
        // Mistake detection: discard if never got above a whisper
        // (accidental trigger, cat bump, background noise).
        if (maxAmp < MIN_VOICE_AMPLITUDE) {
            System.out.println("MuseGadgetVoice: discarding silent recording (maxAmp=" + maxAmp + ")")
            onTooShort?.invoke()
            return false
        }
        System.out.println("MuseGadgetVoice: PttController.stop elapsed=" + elapsed + "ms pcm=" + pcm.size + " maxAmp=" + maxAmp)
        val wav = WavEncoder.pcmToWav(pcm, sampleRate)
        val b64 = Base64.getEncoder().encodeToString(wav)
        lastCaptureInfo = CaptureInfo(elapsed, pcm.size, b64.length)
        transport.sendVoiceNote(VoiceNote.frameVoiceNote(b64))
        if (auto) onAutoStop?.invoke()
        return true
    }

    /** Call from a timer tick; fires the 15s auto-stop once. */
    fun checkAutoStop(): Boolean {
        synchronized(lock) {
            if (state != State.RECORDING) return false
            if (clock.nowMs() - startMs < MAX_MS) return false
        }
        return stop(auto = true)
    }
}
