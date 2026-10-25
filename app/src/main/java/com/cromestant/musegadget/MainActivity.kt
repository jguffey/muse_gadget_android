package com.cromestant.musegadget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cromestant.musegadget.audio.AndroidPcmSource
import com.cromestant.musegadget.audio.LinkVoiceTransport
import com.cromestant.musegadget.audio.PttController
import com.cromestant.musegadget.identity.TokenStore
import com.cromestant.musegadget.link.VoiceTurnListener
import com.cromestant.musegadget.svc.GadgetService

/**
 * Single-activity UI: gate / token / status / PTT screens.
 * Framework widgets only, programmatic layout. API 29 permission flow:
 * BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION, RECORD_AUDIO via
 * requestPermissions with rationale; LocationManager.isLocationEnabled()
 * must be ON or BLE advertising silently fails (blocking dialog).
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_PERMS = 41
        private val WANTED = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    private lateinit var store: TokenStore
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = Handler(Looper.getMainLooper())

    private lateinit var gateView: TextView
    private lateinit var permView: TextView
    private lateinit var tokenHelp: TextView
    private lateinit var tokenInput: EditText
    private lateinit var tokenSave: Button
    private lateinit var tokenMsg: TextView
    private lateinit var tokenSaved: TextView
    private lateinit var tokenClear: Button
    private lateinit var statusView: TextView
    private lateinit var pttButton: Button
    private lateinit var pttResult: TextView
    private lateinit var refreshBtn: Button
    // Digest UI views
    private var digestView: android.widget.TextView? = null
    private var artifactsView: android.widget.TextView? = null
    private var tasksView: android.widget.TextView? = null
    private var tickerView: android.widget.TextView? = null
    private var avatarButton: android.widget.ImageButton? = null

    private var ptt: PttController? = null
    private val voiceListener = object : VoiceTurnListener {
        override fun onAck() {
            ui.post { pttResult.text = "${pttResult.text}\n(sent, listening...)" }
        }

        override fun onPartial(text: String) {
            if (text.trim().isEmpty()) return
            // Check for structured digest message: {"type": "digest|ticker|artifact|tasks", "content": "..."}
            if (tryHandleStructuredMessage(text)) return
            ui.post { pttResult.text = "Muse: $text" }
        }

        override fun onFinal(text: String) {
            // Check for structured digest message
            if (tryHandleStructuredMessage(text)) return
            ui.post { pttResult.text = if (text.isEmpty()) "(no reply text)" else "Muse: $text" }
        }

        override fun onError(error: String) {
            ui.post { pttResult.text = "Link error: $error" }
        }
    }

    /**
     * Try to parse [text] as a structured digest message.
     * Format: {"type": "digest"|"ticker"|"artifact"|"tasks", "content": "..."}
     * Returns true if handled, false if it's a normal chat reply.
     */
    private fun tryHandleStructuredMessage(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return false
        return try {
            val json = org.json.JSONObject(trimmed)
            val type = json.optString("type", "")
            val content = json.optString("content", "")
            if (type in listOf("digest", "ticker", "artifact", "tasks") && content.isNotEmpty()) {
                updateDigestSection(type, content)
                // Also speak the content if it's a digest update (optional)
                // For now, just update UI silently
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
    private val voiceTransport: LinkVoiceTransport by lazy {
        LinkVoiceTransport(link = { GadgetService.instance?.gadgetLink }, listener = voiceListener)
    }
    private var ticking = false
    private var recordStartMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TokenStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val setupText = 0xFFE0E0E0.toInt()
        gateView = TextView(this).apply { visibility = View.GONE; setTextColor(setupText) }
        permView = TextView(this).apply { setTextColor(setupText) }
        tokenHelp = TextView(this).apply {
            text = "Paste your SDK token from gadgets.muse.ai. It is stored only on this device and never logged or transmitted except inside the pairing protocol."
            setTextColor(setupText)
        }
        tokenInput = EditText(this).apply {
            hint = "mgst_..."
            setTextColor(setupText)
            setHintTextColor(0xFF888888.toInt())
        }
        tokenSave = Button(this).apply { text = "Save token" }
        tokenMsg = TextView(this).apply { setTextColor(setupText) }
        tokenSaved = TextView(this).apply { setTextColor(setupText) }
        tokenClear = Button(this).apply { text = "Clear token" }
        statusView = TextView(this).apply { setTextColor(setupText) }
        pttButton = Button(this).apply {
            text = "TAP TO TALK"
            textSize = 24f
            minimumHeight = 220
        }
        pttResult = TextView(this).apply {
            textSize = 26f
            setTextColor(0xFFE0E0E0.toInt())
            setLineSpacing(8f, 1.15f)
            setPadding(24, 24, 24, 24)
        }
        refreshBtn = Button(this).apply { text = "Refresh status" }

        tokenSave.setOnClickListener { saveToken() }
        tokenClear.setOnClickListener { store.clear(); renderToken() }
        // Tap = start with VAD auto-stop; Hold = record while held, stop on release.
        pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    onPttPress()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    onPttRelease()
                    true
                }
                else -> false
            }
        }
        refreshBtn.setOnClickListener { renderStatus() }

        // Digest UI: use the new layout as the main content.
        // Wrap in FrameLayout so gate/token views can overlay when needed.
        val digestRoot = buildDigestLayout()
        val frame = android.widget.FrameLayout(this).apply {
            addView(digestRoot, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        // Gate overlay: shown when not paired/provisioned
        val gateOverlay = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF181819.toInt())
            visibility = android.view.View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        for (v in listOf(gateView, permView, tokenHelp, tokenInput, tokenSave, tokenMsg, tokenSaved, tokenClear, statusView, refreshBtn)) {
            // pttButton is replaced by avatar; keep hidden
            if (v === pttButton) continue
            gateOverlay.addView(v)
        }
        frame.addView(gateOverlay)
        // Store reference for show/hide
        gateOverlay.tag = "gate_overlay"
        setContentView(frame)

        ensurePermissions()
    }

    /**
     * Build the digest UI layout per Charles's spec:
     * - Background #181819
     * - Top: main display + avatar (top-right, circular, tap = PTT)
     * - Under avatar: running tasks list
     * - Middle: digest (left) + artifacts (right)
     * - Bottom: full-width streaming ticker
     */
    private fun buildDigestLayout(): android.view.View {
        val BG = 0xFF181819.toInt()
        val TEXT = 0xFFE0E0E0.toInt()
        val ACCENT = 0xFF4CAF50.toInt()
        val HEADER_BG = 0xFF242426.toInt()

        fun textView(text: String, size: Float, bold: Boolean = false): android.widget.TextView {
            return android.widget.TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(TEXT)
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 12, 16, 12)
            }
        }

        fun sectionHeader(title: String): android.widget.TextView {
            return textView(title, 14f, true).apply {
                setBackgroundColor(HEADER_BG)
            }
        }

        fun scrollableContent(): android.widget.ScrollView {
            return android.widget.ScrollView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f
                )
            }
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ---- TOP SECTION: main display + avatar/tasks ----
        val topRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f
            )
        }

        // Main display (left, weight 3)
        val mainCol = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 3f)
        }
        val mainHeader = sectionHeader("MUSE")
        val mainScroll = scrollableContent()
        // Reuse pttResult as the main display
        mainScroll.addView(pttResult)
        mainCol.addView(mainHeader)
        mainCol.addView(mainScroll)

        // Right column (avatar + tasks, weight 1)
        val rightCol = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        // Avatar: circular placeholder, tap = PTT
        val avatar = android.widget.ImageButton(this).apply {
            // Placeholder: use a colored circle with initial
            // TODO: Replace with actual Lunk avatar asset
            setBackgroundColor(ACCENT)
            contentDescription = "Tap to talk"
            layoutParams = android.widget.LinearLayout.LayoutParams(160, 160).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setMargins(0, 16, 0, 8)
            }
            // Make it circular via outline (API 21+)
            clipToOutline = true
            // Tap = PTT (same as pttButton)
            setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { onPttPress(); true }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { onPttRelease(); true }
                    else -> false
                }
            }
        }
        avatarButton = avatar

        val tasksHeader = sectionHeader("TASKS")
        val tasksScroll = scrollableContent()
        val tv = textView("(no tasks)", 13f)
        tasksView = tv
        tasksScroll.addView(tv)

        rightCol.addView(avatar)
        rightCol.addView(tasksHeader)
        rightCol.addView(tasksScroll)

        topRow.addView(mainCol)
        topRow.addView(rightCol)
        root.addView(topRow)

        // ---- MIDDLE SECTION: digest + artifacts ----
        val midRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f
            )
        }

        // Digest (left)
        val digestCol = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val digestHeader = sectionHeader("DIGEST")
        val digestScroll = scrollableContent()
        val dv = textView("(digest empty)", 13f)
        digestView = dv
        digestScroll.addView(dv)
        digestCol.addView(digestHeader)
        digestCol.addView(digestScroll)

        // Artifacts (right)
        val artCol = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val artHeader = sectionHeader("ARTIFACTS")
        val artScroll = scrollableContent()
        val av = textView("(no artifacts)", 13f)
        artifactsView = av
        artScroll.addView(av)
        artCol.addView(artHeader)
        artCol.addView(artScroll)

        midRow.addView(digestCol)
        midRow.addView(artCol)
        root.addView(midRow)

        // ---- BOTTOM: ticker ----
        val ticker = android.widget.TextView(this).apply {
            text = "(ticker)"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setBackgroundColor(HEADER_BG)
            setPadding(16, 8, 16, 8)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            isFocusable = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        tickerView = ticker
        root.addView(ticker)

        return root
    }

    /**
     * Update the avatar to show recording state.
     * Red = recording (tap to stop). Green = idle (tap to talk).
     */
    fun setAvatarRecording(recording: Boolean) {
        runOnUiThread {
            avatarButton?.let { btn ->
                if (recording) {
                    btn.setBackgroundColor(0xFFCC0000.toInt())
                    btn.contentDescription = "Recording - tap to stop"
                } else {
                    btn.setBackgroundColor(0xFF4CAF50.toInt())
                    btn.contentDescription = "Tap to talk"
                }
            }
        }
    }

    /**
     * Update a digest UI section from a structured message.
     * Called when a chat message with type digest|ticker|artifact arrives.
     */
    fun updateDigestSection(type: String, content: String) {
        runOnUiThread {
            when (type) {
                "digest" -> digestView?.text = content
                "artifact" -> artifactsView?.text = content
                "tasks" -> tasksView?.text = content
                "ticker" -> {
                    tickerView?.text = content
                    // Restart marquee
                    tickerView?.isSelected = false
                    tickerView?.isSelected = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            startService(Intent(this, GadgetService::class.java))
        } catch (e: Exception) {
            // Best effort; gate screen reports the state.
        }
        checkLocationSetting()
        renderToken()
        // Service binds async; poll briefly for status.
        ui.postDelayed({ renderStatus() }, 800)
        renderPermSummary()
        // Start background chat listener for digest updates.
        ui.postDelayed({
            try {
                val link = GadgetService.instance?.gadgetLink
                link?.setOnBackgroundMessage { type, content ->
                    updateDigestSection(type, content)
                }
                // Plain side-chat replies (answers to text messages) go to the
                // main display via the same path as voice-turn finals.
                link?.setOnBackgroundText { text ->
                    try { link?.clearTtsQueue() } catch (_: Exception) { }
                    voiceListener.onFinal(text)
                }
                link?.startBackgroundChatListener()
            } catch (_: Exception) { }
        }, 2000)
    }

    override fun onDestroy() {
        try {
            GadgetService.instance?.gadgetLink?.let {
                it.setOnBackgroundMessage(null)
                it.setOnBackgroundText(null)
            }
        } catch (_: Exception) { }
        super.onDestroy()
    }

    // ---- permissions (API 29) ----

    private fun missingPerms(): List<String> =
        WANTED.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePermissions() {
        val missing = missingPerms()
        if (missing.isNotEmpty()) {
            if (missing.any { shouldShowRequestPermissionRationale(it) }) {
                permView.text = "Bluetooth/Location/Microphone are needed: BLE advertising needs Bluetooth+Location, PTT needs the microphone."
            }
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
        renderPermSummary()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, grants: IntArray) {
        if (code == REQ_PERMS) renderPermSummary()
    }

    private fun renderPermSummary() {
        val missing = missingPerms()
        permView.text = if (missing.isEmpty()) {
            "Permissions: all granted."
        } else {
            val degraded = mutableListOf<String>()
            if (missing.any { it.contains("LOCATION") || it.contains("BLUETOOTH") }) {
                degraded += "no BLE advertise"
            }
            if (missing.contains(Manifest.permission.RECORD_AUDIO)) degraded += "no mic"
            "Missing: ${missing.joinToString(", ")} (degraded: ${degraded.joinToString(", ")})"
        }
    }

    private fun checkLocationSetting() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val on = try {
            lm.isLocationEnabled
        } catch (e: Exception) {
            true
        }
        if (!on) {
            AlertDialog.Builder(this)
                .setTitle("Location is OFF")
                .setMessage("Location must be turned ON for BLE advertising (Android requirement).")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (e: Exception) {
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    // ---- token ----

    private fun saveToken() {
        val t = tokenInput.text.toString().trim()
        if (store.save(t)) {
            tokenMsg.text = "Saved."
            tokenInput.text.clear()
        } else {
            tokenMsg.text = "Malformed token (expected mgst_... from gadgets.muse.ai)."
        }
        renderToken()
    }

    private fun renderToken() {
        val saved = store.load()
        if (saved == null) {
            tokenSaved.text = "No token saved."
            tokenInput.visibility = View.VISIBLE
            tokenSave.visibility = View.VISIBLE
        } else {
            // Show only masked: first 8 chars + ...
            tokenSaved.text = "Saved: ${saved.take(8)}..."
            tokenInput.visibility = View.GONE
            tokenSave.visibility = View.GONE
        }
    }

    // ---- status + gate ----

    /** Show or hide the gate overlay (setup screens). */
    private fun setGateVisible(visible: Boolean) {
        val root = findViewById<android.view.View>(android.R.id.content)
        val overlay = root?.findViewWithTag<android.view.View>("gate_overlay")
        overlay?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun renderStatus() {
        val svc = GadgetService.instance
        if (svc == null) {
            statusView.text = "Service: starting..."
            return
        }
        if (!svc.isPeripheralSupported()) {
            setGateVisible(true); gateView.visibility = View.VISIBLE
            gateView.text = "This Portal Mini cannot do BLE advertising (peripheral mode unsupported) — live pairing with the Muse app will not work on this unit."
            pttButton.isEnabled = false
            tokenInput.isEnabled = false
            tokenSave.isEnabled = false
        } else {
            setGateVisible(false); gateView.visibility = View.GONE
            pttButton.isEnabled = true
            tokenInput.isEnabled = true
            tokenSave.isEnabled = true
        }
        statusView.text = "BLE: ${svc.bleName()} | advertising=${svc.isAdvertising()} | paired=${svc.isPaired()} | provisioned=${svc.isProvisioned()}"
    }

    // ---- PTT ----

    private fun pttController(): PttController {
        var c = ptt
        if (c == null) {
            c = PttController(
                source = AndroidPcmSource(),
                transport = voiceTransport,
                onAutoStop = { ui.post { pttResult.text = "${pttResult.text}\n(auto-stopped at 15s)" } },
                onTooShort = { ui.post { pttResult.text = "Didn't catch that (hold at least 0.3s)" } },
            )
            c.setOnVadAutoStop {
                ui.post {
                    stopTick()
                    pttButton.text = "TAP TO TALK"
                    resetPttButtonColor()
                    setAvatarRecording(false)
                    showCapture(c, auto = true)
                    renderStatus()
                    pttResult.text = "${pttResult.text}\n(auto-stopped on silence)"
                }
            }
            ptt = c
        }
        return c
    }

    /** Timestamp of ACTION_DOWN, for tap vs hold detection. */
    private var pttDownMs = 0L
    /** True if the current recording was started by a hold (not a tap). */
    private var pttHoldStarted = false

    private fun onPttPress() {
        val c = pttController()
        // If already recording (tap mode with VAD), ignore press.
        if (c.currentState() == PttController.State.RECORDING) return
        pttDownMs = System.currentTimeMillis()
        pttHoldStarted = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pttResult.text = "Microphone permission denied — PTT unavailable."
            return
        }
        // Hold mode: VAD auto-stop disabled while held.
        c.holdMode = true
        if (!c.start()) {
            pttResult.text = "Mic unavailable."
            c.holdMode = false
            return
        }
        // Barge-in: stop any spoken reply before recording the next turn.
        try {
            GadgetService.instance?.gadgetLink?.stopTtsPlayback()
        } catch (_: Exception) {
        }
        recordStartMs = System.currentTimeMillis()
        pttButton.text = "RECORDING 0.0s — RELEASE TO STOP (or tap for auto)"
        pttButton.setBackgroundColor(0xFFCC0000.toInt())
        setAvatarRecording(true)
        startTick()
    }

    private fun onPttRelease() {
        val c = pttController()
        if (c.currentState() != PttController.State.RECORDING) return
        val heldMs = System.currentTimeMillis() - pttDownMs
        if (heldMs < 300 && !pttHoldStarted) {
            // Quick tap: switch to VAD mode (auto-stop on silence).
            // Don't stop now; the VAD monitor will stop on 1.5s silence.
            c.holdMode = false
            pttButton.text = "RECORDING 0.0s — AUTO-STOP ON SILENCE"
            System.out.println("MuseGadgetVoice: tap detected, VAD auto-stop enabled")
        } else {
            // Hold release: stop immediately.
            stopTick()
            c.holdMode = false
            val ok = c.stop()
            pttButton.text = "TAP TO TALK"
            resetPttButtonColor()
            setAvatarRecording(false)
            if (ok) showCapture(c, auto = false)
            renderStatus()
        }
    }

    private fun onPttTap() {
        val c = pttController()
        if (c.currentState() == PttController.State.RECORDING) {
            stopTick()
            val ok = c.stop()
            pttButton.text = "TAP TO TALK"
            resetPttButtonColor()
            setAvatarRecording(false)
            if (ok) showCapture(c, auto = false)
            renderStatus()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pttResult.text = "Microphone permission denied — PTT unavailable."
            return
        }
        if (!c.start()) {
            pttResult.text = "Mic unavailable."
            return
        }
        // Barge-in: stop any spoken reply before recording the next turn.
        try {
            GadgetService.instance?.gadgetLink?.stopTtsPlayback()
        } catch (_: Exception) {
        }
        recordStartMs = System.currentTimeMillis()
        pttButton.text = "RECORDING 0.0s — TAP TO STOP"
        pttButton.setBackgroundColor(0xFFCC0000.toInt())
        startTick()
    }

    private fun showCapture(c: PttController, auto: Boolean) {
        val info = c.lastCaptureInfo ?: return
        val secs = info.durationMs / 1000.0
        pttResult.text = "Captured ${"%.1f".format(secs)}s, ${info.pcmBytes}B PCM -> ${info.b64Chars} base64 chars voice_note (sending via gadget link...)${if (auto) " [auto-stop]" else ""}"
    }

    private fun startTick() {
        ticking = true
        ticker.post(object : Runnable {
            override fun run() {
                val c = ptt ?: return
                if (!ticking || c.currentState() != PttController.State.RECORDING) return
                val el = System.currentTimeMillis() - recordStartMs
                pttButton.text = "RECORDING ${"%.1f".format(el / 1000.0)}s — TAP TO STOP"
                if (c.checkAutoStop()) {
                    ticking = false
                    pttButton.text = "TAP TO TALK"
                    resetPttButtonColor()
                    setAvatarRecording(false)
                    showCapture(c, auto = true)
                    renderStatus()
                    return
                }
                ticker.postDelayed(this, 200)
            }
        })
    }

    private fun stopTick() {
        ticking = false
    }

    private fun resetPttButtonColor() {
        pttButton.setBackgroundColor(0xFFCCCCCC.toInt())
    }
}
