package com.example.handheldsettings.tile

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.handheldsettings.data.FanMode
import com.example.handheldsettings.data.Prefs
import com.example.handheldsettings.service.SystemControlService

/**
 * Quick Settings tile for fan mode (RP6_Complete_Guide.md §6.7).
 * Each tap cycles: Off → Quiet → Smart → Sport → Max → Off
 */
class FanQuickSettingsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileUi(currentMode())
    }

    override fun onClick() {
        super.onClick()
        val next = currentMode().nextTileMode()
        saveMode(next)
        updateTileUi(next)
        SystemControlService.startOrUpdate(applicationContext)
    }

    private fun currentMode(): FanMode {
        val prefs = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        return runCatching {
            FanMode.valueOf(prefs.getString(Prefs.FAN_MODE, FanMode.SMART.name)!!)
        }.getOrDefault(FanMode.SMART)
    }

    private fun saveMode(mode: FanMode) {
        getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit().putString(Prefs.FAN_MODE, mode.name).apply()
    }

    private fun updateTileUi(mode: FanMode) {
        qsTile?.apply {
            label = "Fan"
            subtitle = mode.label
            state = if (mode == FanMode.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
