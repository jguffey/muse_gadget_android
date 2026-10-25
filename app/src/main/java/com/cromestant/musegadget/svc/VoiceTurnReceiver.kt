package com.cromestant.musegadget.svc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Cross-app voice-turn entry point (any dashboard app can send VOICE_TURN).
 * Receives an explicit VOICE_TURN command carrying "wav" bytes and hands it
 * to [GadgetService], which sends it over the paired link and broadcasts the
 * reply back. The service speaks the reply itself; this receiver only routes.
 *
 * Optional VOICE_TURN extras [GadgetService.EXTRA_REPLY_PACKAGE] and
 * [GadgetService.EXTRA_REPLY_ACTION] name the reply broadcast target;
 * when absent the PortalDash defaults are used.
 */
class VoiceTurnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GadgetService.ACTION_VOICE_TURN) return
        val svc = Intent(context, GadgetService::class.java)
            .setAction(GadgetService.ACTION_VOICE_TURN)
            .putExtra("wav", intent.getByteArrayExtra("wav"))
        // Forward the generalized reply target (absent = PortalDash defaults).
        intent.getStringExtra(GadgetService.EXTRA_REPLY_PACKAGE)?.let {
            svc.putExtra(GadgetService.EXTRA_REPLY_PACKAGE, it)
        }
        intent.getStringExtra(GadgetService.EXTRA_REPLY_ACTION)?.let {
            svc.putExtra(GadgetService.EXTRA_REPLY_ACTION, it)
        }
        try {
            context.startForegroundService(svc)
        } catch (e: Exception) {
            System.out.println("MuseGadgetVoice: voice-turn start failed: " + e.javaClass.simpleName)
        }
    }
}
