package com.example.handheldsettings.tile

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.handheldsettings.data.Prefs
import com.example.handheldsettings.service.SystemControlService

class AutoTdpTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTileUi(isEnabled())
    }

    override fun onClick() {
        super.onClick()
        val next = !isEnabled()
        saveEnabled(next)
        updateTileUi(next)
        SystemControlService.startOrUpdate(applicationContext)
    }

    private fun isEnabled(): Boolean {
        val prefs = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        return prefs.getBoolean(Prefs.AUTOTDP_ENABLED, false)
    }

    private fun saveEnabled(enabled: Boolean) {
        getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.AUTOTDP_ENABLED, enabled).apply()
    }

    private fun updateTileUi(enabled: Boolean) {
        qsTile?.apply {
            label = "AutoTDP"
            subtitle = if (enabled) "Enabled" else "Disabled"
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
