package com.cromestant.musegadget.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

private class FakeClock(var t: Long = 0) : PttClock {
    override fun nowMs(): Long = t
}

private class FakeSource(var bytes: ByteArray = ByteArray(3200)) : PcmSource {
    var started = false
    var available = true
    var fakeMaxAmplitude = 0.5
    override fun start(): Boolean {
        if (!available) return false
        started = true
        return true
    }
    override fun stopAndDrain(): ByteArray {
        started = false
        return bytes
    }
    override fun getMaxAmplitude(): Double = fakeMaxAmplitude
    override fun getAmplitude(): Double = 0.5
}

class PttControllerTest {
    @Test
    fun tooShortDiscards() {
        val clock = FakeClock(1000)
        val transport = FakeVoiceTransport()
        var tooShort = 0
        val ptt = PttController(clock, FakeSource(), transport, onTooShort = { tooShort++ })
        assertTrue(ptt.start())
        clock.t = 1000 + 299
        assertFalse(ptt.stop())
        assertEquals(1, tooShort)
        assertNull(transport.lastJson)
        assertEquals(PttController.State.IDLE, ptt.currentState())
    }

    @Test
    fun validCaptureProducesFramedNote() {
        val clock = FakeClock(0)
        val transport = FakeVoiceTransport()
        val ptt = PttController(clock, FakeSource(ByteArray(640)), transport)
        assertTrue(ptt.start())
        clock.t = 2300
        assertTrue(ptt.stop())
        val json = transport.lastJson
        assertNotNull(json)
        assertTrue(json!!.startsWith(VoiceNote.NOTE_HEAD))
        assertTrue(json.endsWith(VoiceNote.NOTE_TAIL))
        val info = ptt.lastCaptureInfo!!
        assertEquals(2300L, info.durationMs)
        assertEquals(640, info.pcmBytes)
        val inner = json.removePrefix(VoiceNote.NOTE_HEAD).removeSuffix(VoiceNote.NOTE_TAIL)
        assertEquals(Base64.getDecoder().decode(inner).size, 44 + 640)
        assertEquals(inner.length, info.b64Chars)
    }

    @Test
    fun silentCaptureDiscarded() {
        val clock = FakeClock(0)
        val transport = FakeVoiceTransport()
        var tooShort = 0
        val source = FakeSource().apply { fakeMaxAmplitude = 0.01 }
        val ptt = PttController(clock, source, transport, onTooShort = { tooShort++ })
        assertTrue(ptt.start())
        clock.t = 2500
        assertFalse(ptt.stop())
        assertEquals(1, tooShort)
        assertNull(transport.lastJson)
        assertEquals(PttController.State.IDLE, ptt.currentState())
    }

    @Test
    fun autoStopAt15s() {
        val clock = FakeClock(0)
        val transport = FakeVoiceTransport()
        var auto = 0
        val ptt = PttController(clock, FakeSource(), transport, onAutoStop = { auto++ })
        assertTrue(ptt.start())
        clock.t = 14999
        assertFalse(ptt.checkAutoStop())
        assertEquals(PttController.State.RECORDING, ptt.currentState())
        clock.t = 15000
        assertTrue(ptt.checkAutoStop())
        assertEquals(1, auto)
        assertNotNull(transport.lastJson)
        assertEquals(PttController.State.IDLE, ptt.currentState())
    }
}
