package com.cromestant.musegadget.svc

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceReplyTargetTest {
    @Test
    fun missingExtrasFallBackToPortalDash() {
        val (action, pkg) = GadgetService.resolveReplyTarget(null, null)
        assertEquals(GadgetService.REPLY_ACTION, action)
        assertEquals(GadgetService.REPLY_PACKAGE, pkg)
    }

    @Test
    fun blankExtrasFallBackToPortalDash() {
        val (action, pkg) = GadgetService.resolveReplyTarget("  ", "")
        assertEquals(GadgetService.REPLY_ACTION, action)
        assertEquals(GadgetService.REPLY_PACKAGE, pkg)
    }

    @Test
    fun explicitExtrasWin() {
        val (action, pkg) = GadgetService.resolveReplyTarget(
            "com.example.otherdash",
            "com.example.otherdash.action.VOICE_REPLY",
        )
        assertEquals("com.example.otherdash.action.VOICE_REPLY", action)
        assertEquals("com.example.otherdash", pkg)
    }

    @Test
    fun mixedExtrasFallBackPerField() {
        val (action, pkg) = GadgetService.resolveReplyTarget("com.example.otherdash", null)
        assertEquals(GadgetService.REPLY_ACTION, action)
        assertEquals("com.example.otherdash", pkg)
    }
}
