package com.coupeau.voicebubble

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.math.hypot

/**
 * Watches which app is in front. Inside the chosen apps it shows the bubble on top of the
 * app's own mic/send button and handles tap (talk / stop / send), long-press (cancel),
 * and drag (move, or drop at the bottom to hide).
 */
class BubbleService : AccessibilityService(), Dictation.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var bubble: BubbleView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var dictation: Dictation
    private var attached = false

    private var currentPkg: String? = null
    private var hiddenForPkg: String? = null

    /** Text that was already in the box before we started, so dictation adds to it. */
    private var baseText = ""
    private var field: AccessibilityNodeInfo? = null
    private var mutedStreams = emptyList<Int>()

    private var repositionPending = false

    // ---- lifecycle ---------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // Android can reconnect the service; never leave an old bubble behind.
        live?.let { old -> if (old !== this) old.shutdownBubble() }
        if (::bubble.isInitialized) removeWindow()
        live = this
        // Positions saved by test build 0.1 could keep the bubble off the button; clear them once.
        val prefs = Prefs.get(this)
        if (prefs.getInt("layout_version", 1) < 2) {
            Prefs.clearAllPositions(this)
            prefs.edit().putInt("layout_version", 2).apply()
        }
        dictation = Dictation(this, this)
        bubble = BubbleView(this)
        params = WindowManager.LayoutParams(
            0, 0,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applySize()
        bubble.setOnTouchListener(TouchHandler())
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)
        ensureWindow()
        updateNotification()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdownBubble()
        return super.onUnbind(intent)
    }

    private fun shutdownBubble() {
        handler.removeCallbacksAndMessages(null)
        if (::dictation.isInitialized && dictation.active) dictation.cancel()
        unmute()
        if (::bubble.isInitialized) removeWindow()
    }

    override fun onDestroy() {
        if (live === this) live = null
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
        if (::dictation.isInitialized) dictation.release()
        unmute()
        if (::bubble.isInitialized) removeWindow()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_ENABLED -> {
                if (!Prefs.enabled(this)) stopDictation(keepText = true)
                updateNotification()
                updateVisibility()
            }
            Prefs.KEY_SIZE_DP -> {
                applySize()
                reposition()
            }
            else -> updateVisibility()
        }
    }

    // ---- which app is in front ---------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::bubble.isInitialized) return
        val pkg = activePackage() ?: return
        if (pkg != currentPkg) {
            onAppChanged(pkg)
        } else if (attached) {
            scheduleReposition()
        }
    }

    private fun activePackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) {
        null
    }

    private fun onAppChanged(pkg: String) {
        if (dictation.active) stopDictation(keepText = true)
        setMode(BubbleView.Mode.IDLE)
        if (hiddenForPkg != pkg) hiddenForPkg = null
        currentPkg = pkg
        updateVisibility()
    }

    private fun isTargetApp(pkg: String?) =
        pkg != null && (pkg == packageName || pkg in Prefs.apps(this))

    private fun updateVisibility() {
        val pkg = currentPkg
        val show = Prefs.enabled(this) && isTargetApp(pkg) && hiddenForPkg != pkg
        if (show) {
            attach()
            reposition()
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 600)
        } else {
            detach()
        }
    }

    /**
     * The bubble lives in ONE window for the whole life of the service. Showing and hiding
     * only changes visibility; the window is never removed and re-added, because repeated
     * add/remove left "ghost" bubbles on screen on some phones.
     */
    private var windowAdded = false

    private fun ensureWindow(): Boolean {
        if (windowAdded) return true
        return try {
            hiddenFlags(true)
            bubble.visibility = android.view.View.GONE
            wm.addView(bubble, params)
            windowAdded = true
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hiddenFlags(hidden: Boolean) {
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.flags = if (hidden) params.flags or f else params.flags and f.inv()
    }

    private fun attach() {
        if (attached) return
        if (!ensureWindow()) return
        hiddenFlags(false)
        bubble.visibility = android.view.View.VISIBLE
        attached = true
        try {
            wm.updateViewLayout(bubble, params)
        } catch (_: Exception) {
        }
    }

    private fun detach() {
        if (!windowAdded) {
            attached = false
            return
        }
        bubble.visibility = android.view.View.GONE
        hiddenFlags(true)
        attached = false
        try {
            wm.updateViewLayout(bubble, params)
        } catch (_: Exception) {
        }
    }

    /** Only when the service is shutting down. */
    private fun removeWindow() {
        attached = false
        if (!windowAdded) return
        try {
            wm.removeViewImmediate(bubble)
        } catch (_: Exception) {
        }
        windowAdded = false
    }

    // ---- placing the bubble ------------------------------------------------------------

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sizePx() = dp(Prefs.sizeDp(this).toFloat()).toInt()

    private fun applySize() {
        val s = sizePx()
        params.width = s
        params.height = s
        if (attached) wm.updateViewLayout(bubble, params)
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = resources.displayMetrics
            m.widthPixels to m.heightPixels
        }
    }

    private fun scheduleReposition() {
        if (repositionPending) return
        repositionPending = true
        handler.postDelayed({
            repositionPending = false
            if (attached) {
                reposition()
                // If you sent or cleared the message yourself, go back to the mic.
                if (bubble.mode == BubbleView.Mode.READY_TO_SEND && currentFieldText().isBlank()) {
                    setMode(BubbleView.Mode.IDLE)
                }
            }
        }, 120)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (attached && !dragging) reposition()
            if (attached) handler.postDelayed(this, 600)
        }
    }

    private var dragging = false

    private fun reposition() {
        if (!attached) return
        val pkg = currentPkg ?: return
        val size = sizePx()
        val (sw, sh) = screenSize()

        val manual = Prefs.manualPos(this, pkg)
        val target = if (manual == null && Prefs.snap(this)) targetCentre() else null
        val (x, y) = when {
            manual != null -> manual
            target != null -> (target.first - size / 2) to (target.second - size / 2)
            // Fallback: bottom right, roughly where chat apps put their mic.
            else -> (sw - size - dp(12f).toInt()) to (sh - size - dp(110f).toInt())
        }
        val nx = x.coerceIn(0, (sw - size).coerceAtLeast(0))
        val ny = y.coerceIn(0, (sh - size).coerceAtLeast(0))
        if (nx != params.x || ny != params.y) {
            params.x = nx
            params.y = ny
            try {
                wm.updateViewLayout(bubble, params)
            } catch (_: Exception) {
            }
        }
    }

    // ---- finding things inside the other app --------------------------------------------

    private val exactIds = setOf("voice_note_btn", "send", "send_button", "send_btn", "mic_button", "voice_input_button")
    private val buttonIdHints = listOf("voice_note_btn", "mic", "send", "voice_input", "speech", "record_button")
    private val buttonDescHints = Regex("voice|mic|record|send|dictat|speak|listen", RegexOption.IGNORE_CASE)
    private val excludeDesc = Regex("play|pause|seek|duration|transcri|message from|voice message,", RegexOption.IGNORE_CASE)

    /** The app's own mic or send button beside the message box, to sit on top of. */
    private fun findButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val (_, sh) = screenSize()
        val maxSide = dp(140f)
        val fieldRect = findField()?.let { f -> Rect().also { f.getBoundsInScreen(it) } }
        val rowSlack = dp(60f)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        val r = Rect()
        walk(root) { n ->
            if (!n.isVisibleToUser || n.isEditable) return@walk
            val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val exact = id in exactIds
            val idHit = exact || buttonIdHints.any { id.contains(it) }
            val descHit = buttonDescHints.containsMatchIn(desc) && !excludeDesc.containsMatchIn(desc)
            if (!idHit && !descHit) return@walk
            n.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0 || r.width() > maxSide || r.height() > maxSide) return@walk
            if (fieldRect != null) {
                // Must be on the same row as the message box (not in the chat above it).
                if (r.centerY() < fieldRect.top - rowSlack || r.centerY() > fieldRect.bottom + rowSlack) return@walk
            } else if (!exact || r.centerY() < sh * 0.35f) {
                return@walk
            }
            var score = r.right
            if (idHit) score += 5000
            if (exact) score += 10000
            if (score > bestScore) {
                bestScore = score
                best = n
            }
        }
        return best
    }

    /** Centre of the button the bubble should sit on, if there is one. */
    private fun targetCentre(): Pair<Int, Int>? {
        val n = findButton() ?: return null
        val r = Rect().also { n.getBoundsInScreen(it) }
        return r.centerX() to r.centerY()
    }

    private fun findSendButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE
        val r = Rect()
        walk(root) { n ->
            if (!n.isVisibleToUser || n.isEditable) return@walk
            val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase() ?: ""
            val desc = n.contentDescription?.toString()?.lowercase() ?: ""
            val hit = id == "send" || id.endsWith("send_button") || id.endsWith("send_btn") ||
                desc == "send" || desc.startsWith("send ") || desc.startsWith("send message") ||
                desc == "send sms" || desc == "send mms"
            if (!hit) return@walk
            n.getBoundsInScreen(r)
            if (r.bottom > bestBottom) {
                bestBottom = r.bottom
                best = n
            }
        }
        return best
    }

    private fun findField(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        var best: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE
        val r = Rect()
        walk(root) { n ->
            if (n.isEditable && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                if (r.bottom > bestBottom) {
                    bestBottom = r.bottom
                    best = n
                }
            }
        }
        return best
    }

    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 3000) {
            val n = queue.removeFirst()
            count++
            visit(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
    }

    private val placeholders = setOf(
        "message", "type a message", "text message", "rcs message", "sms message",
        "enter text", "compose email", "search", "",
    )

    private fun fieldText(n: AccessibilityNodeInfo?): String {
        if (n == null) return ""
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        val t = n.text?.toString() ?: return ""
        if (Build.VERSION.SDK_INT >= 26 && n.hintText != null && t == n.hintText.toString()) return ""
        // WhatsApp reports its grey "Message" placeholder as text. If the box only holds a
        // typical placeholder and the app shows its mic (not Send), the box is really empty.
        if (t.trim().lowercase() in placeholders && findSendButton() == null) return ""
        return t
    }

    private fun currentFieldText(): String = fieldText(findField())

    private fun setFieldText(text: String) {
        var n = field
        if (n == null || !n.refresh()) {
            n = findField()
            field = n
        }
        n ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            n = findField() ?: return
            field = n
            n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    private fun compose(spoken: String): String {
        val base = baseText.trimEnd()
        val s = spoken.trim()
        return when {
            s.isEmpty() -> baseText
            base.isEmpty() -> s.replaceFirstChar { it.uppercase() }
            else -> "$base $s"
        }
    }

    // ---- the three taps ----------------------------------------------------------------

    private fun onTap() {
        when (bubble.mode) {
            BubbleView.Mode.IDLE -> startDictation()
            BubbleView.Mode.LISTENING -> {
                val text = stopDictation(keepText = true)
                val canSend = text.isNotBlank() && findSendButton() != null
                setMode(if (canSend) BubbleView.Mode.READY_TO_SEND else BubbleView.Mode.IDLE)
            }
            BubbleView.Mode.READY_TO_SEND -> {
                val send = findSendButton()
                if (send != null && clickNode(send)) {
                    buzz(30)
                } else {
                    toast("Couldn't find the Send button. Tap it yourself.")
                }
                setMode(BubbleView.Mode.IDLE)
                handler.postDelayed({ reposition() }, 400)
            }
        }
    }

    private fun onLongPress() {
        buzz(60)
        when (bubble.mode) {
            BubbleView.Mode.LISTENING, BubbleView.Mode.READY_TO_SEND -> {
                // Cancel: put the box back how it was before you started talking.
                dictation.cancel()
                unmute()
                setFieldText(baseText)
                setMode(BubbleView.Mode.IDLE)
                toast("Cancelled")
            }
            BubbleView.Mode.IDLE -> {
                // Snap back to the app's button if it was dragged somewhere else.
                currentPkg?.let { Prefs.clearManualPos(this, it) }
                reposition()
                toast("Bubble back in its normal place")
            }
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 4) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        return false
    }

    private fun startDictation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Allow the microphone in Voice Bubble first")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        field = findField()
        if (field == null) {
            toast("Tap the message box first")
            return
        }
        baseText = fieldText(field)
        buzz(20)
        mute()
        setMode(BubbleView.Mode.LISTENING)
        dictation.start()
    }

    /** Returns the full text now in the box. */
    private fun stopDictation(keepText: Boolean): String {
        if (!dictation.active) return currentFieldText()
        val spoken = dictation.stop()
        unmute()
        val full = if (keepText) compose(spoken) else baseText
        setFieldText(full)
        return full
    }

    private fun setMode(m: BubbleView.Mode) {
        if (::bubble.isInitialized) bubble.mode = m
    }

    // ---- Dictation.Listener -------------------------------------------------------------

    override fun onDictationText(committed: String, partial: String) {
        setFieldText(compose(listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ")))
    }

    override fun onDictationLevel(rmsDb: Float) = bubble.setLevel(rmsDb)

    override fun onDictationFailed(message: String) {
        unmute()
        setMode(BubbleView.Mode.IDLE)
        toast(message)
    }

    // ---- restart beeps ------------------------------------------------------------------

    /** Silences the little beep some phones play each time listening restarts. */
    private fun mute() {
        if (!Prefs.muteBeeps(this)) return
        val am = getSystemService(AudioManager::class.java) ?: return
        val streams = listOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_MUSIC)
        mutedStreams = streams.filter {
            try {
                if (am.isStreamMute(it)) false
                else {
                    am.adjustStreamVolume(it, AudioManager.ADJUST_MUTE, 0); true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun unmute() {
        val am = getSystemService(AudioManager::class.java) ?: return
        mutedStreams.forEach {
            try {
                am.adjustStreamVolume(it, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {
            }
        }
        mutedStreams = emptyList()
    }

    // ---- notification with Pause / Turn on ----------------------------------------------

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice Bubble on/off", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val on = Prefs.enabled(this)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val toggle = PendingIntent.getBroadcast(
            this, 1, Intent(this, ToggleReceiver::class.java).setAction(ToggleReceiver.ACTION_TOGGLE), flags
        )
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (on) "Voice Bubble is on" else "Voice Bubble is paused")
            .setContentText(if (on) "Shows in WhatsApp, Translate and your other chosen apps" else "Tap Turn on to bring the bubble back")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (on) "Pause" else "Turn on", toggle).build())
            .addAction(Notification.Action.Builder(null, "Settings", open).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }

    // ---- small helpers ------------------------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun buzz(ms: Long) {
        val v = getSystemService(Vibrator::class.java) ?: return
        try {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    /** Tap, long-press and drag on the bubble. */
    private inner class TouchHandler : android.view.View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop * 1.5f
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            onLongPress()
        }

        private fun inHideZone(rawY: Float) = rawY > screenSize().second - dp(90f)

        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false; longPressed = false
                    handler.postDelayed(longPress, 550)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !longPressed && hypot(dx, dy) > slop) {
                        dragging = true
                        this@BubbleService.dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        bubble.dropTarget = inHideZone(e.rawY)
                        try {
                            wm.updateViewLayout(bubble, params)
                        } catch (_: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) {
                        bubble.dropTarget = false
                        val pkg = currentPkg
                        if (inHideZone(e.rawY)) {
                            // "Store" it: hidden until you leave this app and come back.
                            hiddenForPkg = pkg
                            if (dictation.active) stopDictation(keepText = true)
                            setMode(BubbleView.Mode.IDLE)
                            detach()
                            params.x = startX; params.y = startY
                            toast("Bubble hidden. Leave and reopen the app to bring it back.")
                        } else if (pkg != null) {
                            val size = sizePx()
                            val t = if (Prefs.snap(this@BubbleService)) targetCentre() else null
                            val cx = params.x + size / 2
                            val cy = params.y + size / 2
                            if (t != null && hypot((cx - t.first).toFloat(), (cy - t.second).toFloat()) < dp(110f)) {
                                // Dropped near the app's button: snap back on to it.
                                Prefs.clearManualPos(this@BubbleService, pkg)
                                reposition()
                            } else {
                                Prefs.setManualPos(this@BubbleService, pkg, params.x, params.y)
                            }
                        }
                        this@BubbleService.dragging = false
                    } else if (!longPressed) {
                        onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    this@BubbleService.dragging = false
                    handler.removeCallbacks(longPress)
                    bubble.dropTarget = false
                }
            }
            return true
        }
    }

    companion object {
        private var live: BubbleService? = null
        private const val CHANNEL = "bubble_state"
        private const val NOTIF_ID = 7

        fun isEnabled(ctx: Context): Boolean {
            val list = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return list.split(':').any {
                it.equals("${ctx.packageName}/${BubbleService::class.java.name}", ignoreCase = true) ||
                    it.equals("${ctx.packageName}/.BubbleService", ignoreCase = true)
            }
        }
    }
}
