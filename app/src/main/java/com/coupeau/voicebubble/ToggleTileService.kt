package com.coupeau.voicebubble

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The "Voice Bubble" tile in the pull-down Quick Settings panel: the main on/off switch. */
class ToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        Prefs.setEnabled(this, !Prefs.enabled(this))
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Prefs.enabled(this)
        val ready = BubbleService.isEnabled(this)
        tile.state = if (on && ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                !ready -> "Needs setup"
                on -> "On"
                else -> "Off"
            }
        }
        tile.updateTile()
    }
}
