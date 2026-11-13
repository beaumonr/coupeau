package com.coupeau.voicebubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Pause / Turn on button in the notification. */
class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            Prefs.setEnabled(context, !Prefs.enabled(context))
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.coupeau.voicebubble.TOGGLE"
    }
}
