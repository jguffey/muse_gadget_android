package com.cromestant.musegadget.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import com.cromestant.musegadget.audio.LinkVoiceTransport
import com.cromestant.musegadget.audio.VoiceNote
import com.cromestant.musegadget.ble.GattPeripheral
import com.cromestant.musegadget.link.VoiceTurnListener
import java.util.Base64
import com.cromestant.musegadget.identity.IdentityProtocol
import com.cromestant.musegadget.identity.TokenStore
import com.cromestant.musegadget.link.GadgetLink
import com.cromestant.musegadget.pairing.PairingSession
import com.cromestant.musegadget.setup.Credentials
import com.cromestant.musegadget.setup.DeviceIdentity
import com.cromestant.musegadget.setup.NetworkInfo
import com.cromestant.musegadget.setup.SetupController
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Foreground service owning GattPeripheral + SetupController + PairingSession.
 * API 29: plain foreground service, no foregroundServiceType needed at
 * targetSdk 29. Advertise ONLY while unpaired.
 */
class GadgetService : Service() {
    companion object {
        const val CHANNEL_ID = "musegadget_ble"
        const val NOTIF_ID = 1
        const val FIRMWARE_VERSION = "1.0"

        /**
         * Cross-app command: send one pre-recorded voice note (extra "wav",
         * WAV bytes) over the paired link. Recording happens in the caller's
         * foreground activity — background AudioRecord yields zeros on Portal.
         */
        const val ACTION_VOICE_TURN = "com.cromestant.musegadget.action.VOICE_TURN"
        /**
         * Optional VOICE_TURN extras naming the reply broadcast target, so any
         * dashboard app can use the voice-turn API. Absent or blank falls back
         * to [REPLY_ACTION]/[REPLY_PACKAGE].
         */
        const val EXTRA_REPLY_PACKAGE = "reply_package"
        const val EXTRA_REPLY_ACTION = "reply_action"
        /** Default reply broadcast (PortalDash); extras: text / error / state. */
        const val REPLY_ACTION = "com.example.portaldash.action.VOICE_REPLY"
        const val REPLY_PACKAGE = "com.example.portaldash"

        /**
         * Resolve the reply broadcast target: explicit extras win, missing or
         * blank values fall back to the PortalDash defaults. Returns
         * (action, package).
         */
        fun resolveReplyTarget(packageExtra: String?, actionExtra: String?): Pair<String, String> {
            val pkg = packageExtra?.takeIf { it.isNotBlank() } ?: REPLY_PACKAGE
            val act = actionExtra?.takeIf { it.isNotBlank() } ?: REPLY_ACTION
            return act to pkg
        }

        // Singleton for the single-activity UI; set in onCreate, cleared in onDestroy.
        @Volatile var instance: GadgetService? = null
            private set
    }

    private val binder = LocalBinder()
    private var peripheral: GattPeripheral? = null
    private var setup: SetupController? = null
    private var pairing: PairingSession? = null
    private var mac: String = ""
    @Volatile private var supported: Boolean = false
    @Volatile private var paired: Boolean = false

    /** Link session orchestrator; created in onCreate, started/stopped with the service. */
    lateinit var gadgetLink: GadgetLink
        private set

    inner class LocalBinder : Binder() {
        fun service(): GadgetService = this@GadgetService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        val store = TokenStore(this)
        mac = store.loadOrGenerateMac { IdentityProtocol.generateMac(randomMacBytes()) }
        val nodeId = IdentityProtocol.nodeId(mac)
        val deviceId = IdentityProtocol.deviceId(mac)
        pairing = PairingSession(nodeId, deviceId, mac, FIRMWARE_VERSION, store.load())
        val network = object : NetworkInfo {
            override fun isOnline(): Boolean = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("api.muse.ai", 443), 1500)
                    true
                }
            } catch (e: Exception) {
                false
            }

            override fun currentConnectionEntry(): Map<String, Any?> {
                // Mirror the official client: report the real SSID when known and
                // mark the entry open (secure=false) so the Muse app skips the
                // password field -- the device is already online and ignores
                // whatever credentials come back.
                val ssid = try {
                    val wm = getSystemService(WIFI_SERVICE) as WifiManager
                    wm.connectionInfo?.ssid
                        ?.removeSurrounding("\"")
                        ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                } catch (e: Exception) {
                    null
                }
                return mapOf(
                    "ssid" to (ssid ?: "current connection"),
                    "rssi" to -40,
                    "secure" to false,
                )
            }
        }
        // No-op provision stub: records credentials locally for now.
        val provision: (Credentials, ((() -> Boolean) -> Boolean)) -> Unit = { creds, commit ->
            val ok = commit { true }
            if (ok) {
                getSharedPreferences("musegadget_provision", MODE_PRIVATE).edit()
                    .putString("username", creds.username)
                    .putString("api_url_v2", creds.apiUrlV2)
                    .putString("noise_host", creds.noiseHost)
                    .putString("access_token", creds.accessToken)
                    .putString("refresh_token", creds.refreshToken)
                    .putLong("token_issued_at", System.currentTimeMillis())
                    .apply()
            }
        }
        gadgetLink = GadgetLink(this).also { link ->
            link.onUnpaired = {
                paired = false
                try {
                    peripheral?.setPaired(false)
                } catch (_: Exception) {
                }
            }
        }
        // Peripheral needs onMessage/onDisconnect wired to the controller;
        // create peripheral first with deferred lookup.
        val periph = GattPeripheral(
            this, mac,
            onMessage = { msg -> setup?.onWrite(msg) },
            onDisconnected = { setup?.onDisconnect() },
        )
        peripheral = periph
        setup = SetupController(
            pairing!!,
            DeviceIdentity(nodeId, deviceId, mac),
            FIRMWARE_VERSION,
            transport = periph,
            network = network,
            provision = provision,
            onComplete = { paired = true; periph.setPaired(true) },
        )
        supported = try {
            periph.isPeripheralSupported()
        } catch (e: Exception) {
            false
        }
        // Waits for provisioning internally; safe to start unconditionally.
        gadgetLink.start()
        startFg()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_VOICE_TURN) {
            handleVoiceNote(intent.getByteArrayExtra("wav"), intent)
        } else {
            start()
        }
        return START_STICKY
    }

    @Volatile private var headlessTurnBusy = false

    /**
     * Send one caller-recorded voice note over the paired link, speak the
     * reply (the link layer does that itself), and broadcast the final text
     * back to the caller ([EXTRA_REPLY_PACKAGE]/[EXTRA_REPLY_ACTION] extras,
     * PortalDash defaults). Recording lives in the caller's foreground
     * activity: background AudioRecord yields zeros on Portal, so the service
     * never captures.
     */
    private fun handleVoiceNote(wav: ByteArray?, intent: Intent?) {
        val (replyAction, replyPackage) = resolveReplyTarget(
            intent?.getStringExtra(EXTRA_REPLY_PACKAGE),
            intent?.getStringExtra(EXTRA_REPLY_ACTION),
        )
        if (wav == null || wav.size < 1000) {
            sendVoiceReply(replyAction, replyPackage, error = "Didn't catch that")
            return
        }
        synchronized(this) {
            if (headlessTurnBusy) {
                sendVoiceReply(replyAction, replyPackage, error = "Busy, try again")
                return
            }
            headlessTurnBusy = true
        }
        fun finishWithError(msg: String) {
            headlessTurnBusy = false
            sendVoiceReply(replyAction, replyPackage, error = msg)
        }
        if (!isProvisioned()) {
            finishWithError("Gadget not paired yet")
            return
        }
        try {
            gadgetLink.stopTtsPlayback()
        } catch (_: Exception) {
        }
        val listener = object : VoiceTurnListener {
            override fun onAck() {
                sendVoiceReply(replyAction, replyPackage, state = "thinking")
            }

            override fun onPartial(text: String) {
                if (text.trim().isEmpty()) return
                if (isStructuredMessage(text)) return
                sendVoiceReply(replyAction, replyPackage, partial = text)
            }

            override fun onFinal(text: String) {
                headlessTurnBusy = false
                sendVoiceReply(replyAction, replyPackage, text = text)
            }

            override fun onError(error: String) {
                headlessTurnBusy = false
                sendVoiceReply(replyAction, replyPackage, error = error)
            }
        }
        val frame = try {
            VoiceNote.frameVoiceNote(Base64.getEncoder().encodeToString(wav))
        } catch (e: Exception) {
            finishWithError("Voice unavailable")
            return
        }
        LinkVoiceTransport({ gadgetLink }, listener).sendVoiceNote(frame)
    }

    private fun sendVoiceReply(
        replyAction: String,
        replyPackage: String,
        text: String? = null,
        error: String? = null,
        state: String? = null,
        partial: String? = null,
    ) {
        val reply = Intent(replyAction).setPackage(replyPackage)
        if (text != null) reply.putExtra("text", text)
        if (error != null) reply.putExtra("error", error)
        if (state != null) reply.putExtra("state", state)
        if (partial != null) reply.putExtra("partial", partial)
        sendBroadcast(reply)
    }

    /** Digest/ticker/artifact/tasks side messages must not render as chat text. */
    private fun isStructuredMessage(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return false
        return try {
            val type = org.json.JSONObject(trimmed).optString("type", "")
            type in listOf("digest", "ticker", "artifact", "tasks")
        } catch (_: Exception) {
            false
        }
    }

    /** Open GATT server and advertise while unpaired; no-op when unsupported. */
    fun start() {
        val periph = peripheral ?: return
        if (!supported) return
        try {
            periph.start()
        } catch (e: Exception) {
            return
        }
        setup?.start()
        try {
            // API-29 gotcha: location services must be ON (not just permission)
            // for advertising; isMultipleAdvertisementSupported() gate above.
            // MTU defaults to 23 until negotiated (ASSUMED_MTU=163 handled in
            // GattPeripheral); adapter.name set needs BLUETOOTH_ADMIN (guarded).
            periph.setPaired(paired)
        } catch (e: Exception) {
            // Best effort.
        }
    }

    fun isPeripheralSupported(): Boolean = supported

    fun isAdvertising(): Boolean = try {
        peripheral?.isAdvertising() == true
    } catch (e: Exception) {
        false
    }

    fun setPaired(p: Boolean) {
        paired = p
        try {
            peripheral?.setPaired(p)
        } catch (e: Exception) {
            // Best effort.
        }
    }

    fun isPaired(): Boolean = paired

    fun isProvisioned(): Boolean = try {
        gadgetLink.isProvisioned()
    } catch (e: Exception) {
        false
    }

    fun bleName(): String = try {
        IdentityProtocol.bleName(mac)
    } catch (e: Exception) {
        "MuseGadget"
    }

    override fun onDestroy() {
        try { gadgetLink.stop() } catch (e: Exception) { }
        try { setup?.stop() } catch (e: Exception) { }
        try { peripheral?.stop() } catch (e: Exception) { }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MuseGadget BLE", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MuseGadget BLE")
                .setContentText("Advertising as ${bleName()}")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MuseGadget BLE")
                .setContentText("Advertising as ${bleName()}")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build()
        }
        startForeground(NOTIF_ID, notif)
    }

    private fun randomMacBytes(): ByteArray =
        ByteArray(6).also { SecureRandom().nextBytes(it) }
}
