package com.coupeau.voicebubble

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup checklist and settings. Built in code so it stays one simple file. */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var accessStatus: TextView
    private lateinit var micStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var masterSwitch: Switch
    private lateinit var appsBox: LinearLayout
    private lateinit var engineStatus: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(48))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(dp(20), bars.top + dp(16), dp(20), bars.bottom + dp(48))
                insets
            }
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun build() {
        title("Voice Bubble")
        para(
            "A dictation bubble that sits on top of WhatsApp's green mic. Tap to talk, " +
                "pause as long as you like, tap to stop, tap again to send."
        )

        heading("1. Setup")
        accessStatus = status()
        button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find \"Voice Bubble\" (maybe under Installed apps) and switch it on", Toast.LENGTH_LONG).show()
        }
        para(
            "Greyed out or \"Restricted setting\"? Tap the button below, then the ⋮ menu " +
                "(top right), then \"Allow restricted settings\", and try again.",
            small = true,
        )
        button("Open Voice Bubble's App info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        micStatus = status()
        button("Allow microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }

        if (Build.VERSION.SDK_INT >= 33) {
            notifStatus = status()
            button("Allow notification (for the Pause button)") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            button("Add on/off tile to Quick Settings") { addTile() }
        } else {
            notifStatus = status()
            para("To add the on/off tile: pull down Quick Settings, tap the pencil, drag \"Voice Bubble\" in.", small = true)
        }

        heading("2. On / off")
        masterSwitch = Switch(this).apply {
            text = "Bubble on"
            textSize = 18f
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setEnabled(this@MainActivity, on) }
        }
        root.addView(masterSwitch, lp(top = 8))
        para("Also: the Quick Settings tile, or Pause in the notification.", small = true)

        heading("3. Size")
        val sizeLabel = TextView(this).apply { textSize = 16f }
        fun showSize(v: Int) {
            sizeLabel.text = "Bubble size: $v dp  (WhatsApp's mic is about 48–56)"
        }
        showSize(Prefs.sizeDp(this))
        root.addView(sizeLabel, lp(top = 4))
        root.addView(SeekBar(this).apply {
            max = Prefs.MAX_SIZE_DP - Prefs.MIN_SIZE_DP
            progress = Prefs.sizeDp(this@MainActivity) - Prefs.MIN_SIZE_DP
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + Prefs.MIN_SIZE_DP
                    showSize(v)
                    Prefs.setSizeDp(this@MainActivity, v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, lp(top = 4))

        heading("4. Position")
        root.addView(CheckBox(this).apply {
            text = "Sit on top of the app's own mic / send button"
            isChecked = Prefs.snap(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setSnap(this@MainActivity, on) }
        }, lp())
        para(
            "Drag the bubble to move it; it remembers the spot for that app. Long-press it (when idle) " +
                "to snap it back. Drag it to the very bottom of the screen to hide it until you next open that app.",
            small = true,
        )
        button("Reset all dragged positions") {
            Prefs.clearAllPositions(this)
            Toast.makeText(this, "Positions reset", Toast.LENGTH_SHORT).show()
        }

        heading("5. Apps")
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appsBox, lp())
        renderApps()
        val custom = EditText(this).apply {
            hint = "Other app's package name, e.g. org.telegram.messenger"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 14f
        }
        root.addView(custom, lp(top = 8))
        button("Add app") {
            val pkg = custom.text.toString().trim()
            if (pkg.isNotEmpty()) {
                Prefs.setApps(this, Prefs.apps(this) + pkg)
                custom.setText("")
                renderApps()
            }
        }

        heading("6. Listening method")
        para(
            "Auto picks the smoothest one your phone supports. Continuous methods keep the mic " +
                "open the whole time (Android 13+). If one fails, Auto moves on to the next.",
            small = true,
        )
        val engines = listOf<Pair<String, Dictation.Engine?>>("Auto (recommended)" to null) +
            Dictation.Engine.values().map { it.label to it }
        val group = android.widget.RadioGroup(this)
        val current = Prefs.engineOverride(this)
        engines.forEachIndexed { i, (label, e) ->
            group.addView(android.widget.RadioButton(this).apply {
                id = 1000 + i
                text = label
                isChecked = e == current
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setEngineOverride(this, engines[checkedId - 1000].second)
        }
        root.addView(group, lp())
        engineStatus = TextView(this).apply { textSize = 14f; alpha = 0.8f }
        root.addView(engineStatus, lp(top = 6))
        button("Re-test the continuous methods") {
            Prefs.clearBrokenEngines(this)
            refreshStatus()
            Toast.makeText(this, "Auto will try the best method again next time", Toast.LENGTH_SHORT).show()
        }

        heading("7. Other")
        root.addView(CheckBox(this).apply {
            text = "Silence the beep when listening restarts (mutes media sound while you talk)"
            isChecked = Prefs.muteBeeps(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setMuteBeeps(this@MainActivity, on) }
        }, lp())

        heading("Try it here")
        para("The bubble also shows in this screen, so you can test without sending anything.", small = true)
        root.addView(EditText(this).apply {
            hint = "Message"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }, lp(top = 8))

        heading("How it works")
        para(
            "🔵 Blue mic: tap to start talking. Your words appear in the message box as you speak.\n" +
                "🔴 Red: listening. Pause to think as long as you like. Tap to stop.\n" +
                "🟢 Green arrow: tap to send.\n" +
                "Long-press while red or green: cancel, and the box goes back to how it was."
        )
    }

    private fun renderApps() {
        appsBox.removeAllViews()
        val chosen = Prefs.apps(this)
        val all = LinkedHashMap(Prefs.KNOWN_APPS)
        chosen.filter { it !in all }.forEach { all[it] = it }
        for ((pkg, label) in all) {
            val installed = isInstalled(pkg)
            appsBox.addView(CheckBox(this).apply {
                text = if (installed) label else "$label (not installed)"
                isChecked = pkg in chosen
                setOnCheckedChangeListener { _, on ->
                    val now = Prefs.apps(this@MainActivity)
                    Prefs.setApps(this@MainActivity, if (on) now + pkg else now - pkg)
                }
            })
        }
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        // Android 11+ hides other apps' details unless declared; treat unknown as installed.
        Build.VERSION.SDK_INT >= 30
    }

    private fun refreshStatus() {
        val acc = BubbleService.isEnabled(this)
        setStatus(accessStatus, acc, "Accessibility: on", "Accessibility: OFF, the bubble can't appear yet")
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setStatus(micStatus, mic, "Microphone: allowed", "Microphone: not allowed yet")
        if (Build.VERSION.SDK_INT >= 33) {
            val n = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            setStatus(notifStatus, n, "Notification: allowed", "Notification: not allowed (optional)")
        } else {
            notifStatus.visibility = View.GONE
        }
        masterSwitch.isChecked = Prefs.enabled(this)
        val last = Prefs.lastEngine(this)?.label ?: "not used yet"
        val note = Prefs.brokenNote(this)
        engineStatus.text = "Last used: $last" + if (note.isNotEmpty()) "\nSkipped: $note" else ""
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (requestCode == 2 && BubbleService.isEnabled(this)) {
            // Nudge the service to post its notification now.
            Prefs.setEnabled(this, !Prefs.enabled(this))
            Prefs.setEnabled(this, !Prefs.enabled(this))
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) return
        val sbm = getSystemService(StatusBarManager::class.java) ?: return
        sbm.requestAddTileService(
            ComponentName(this, ToggleTileService::class.java),
            "Voice Bubble",
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result ->
            val msg = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Tile is in Quick Settings"
                else -> "Tile not added"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- tiny layout helpers ----

    private fun lp(top: Int = 4) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun title(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 28f; typeface = Typeface.DEFAULT_BOLD
    }, lp(top = 0))

    private fun heading(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
    }, lp(top = 28))

    private fun para(t: String, small: Boolean = false) = root.addView(TextView(this).apply {
        text = t; textSize = if (small) 14f else 16f
        if (small) alpha = 0.75f
    }, lp(top = 6))

    private fun status() = TextView(this).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        .also { root.addView(it, lp(top = 14)) }

    private fun setStatus(v: TextView, ok: Boolean, yes: String, no: String) {
        v.text = if (ok) "✅ $yes" else "⚠️ $no"
        v.setTextColor(if (ok) Color.rgb(0x1E, 0x9E, 0x50) else Color.rgb(0xD3, 0x2F, 0x2F))
    }

    private fun button(label: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }, lp(top = 4))
}
