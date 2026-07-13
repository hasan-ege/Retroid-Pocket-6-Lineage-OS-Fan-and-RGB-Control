package com.example.handheldsettings.tile

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import com.example.handheldsettings.data.Prefs
import com.example.handheldsettings.service.SystemControlService

class OverlayTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTileUi(isEnabled())
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(applicationContext)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
            return
        }
        val next = !isEnabled()
        saveEnabled(next)
        updateTileUi(next)
        SystemControlService.startOrUpdate(applicationContext)
    }

    private fun isEnabled(): Boolean {
        val prefs = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        return prefs.getBoolean(Prefs.OVERLAY_ENABLED, false)
    }

    private fun saveEnabled(enabled: Boolean) {
        getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.OVERLAY_ENABLED, enabled).apply()
    }

    private fun updateTileUi(enabled: Boolean) {
        qsTile?.apply {
            label = "OSD Overlay"
            subtitle = if (enabled) "Showing" else "Hidden"
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
