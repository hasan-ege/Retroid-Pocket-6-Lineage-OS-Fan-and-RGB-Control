package com.example.handheldsettings.tile

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.handheldsettings.data.RgbMode
import com.example.handheldsettings.data.Prefs
import com.example.handheldsettings.service.SystemControlService

class RgbModeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTileUi(currentMode())
    }

    override fun onClick() {
        super.onClick()
        val next = currentMode().next()
        saveMode(next)
        updateTileUi(next)
        SystemControlService.startOrUpdate(applicationContext)
    }

    private fun currentMode(): RgbMode {
        val prefs = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        return runCatching {
            RgbMode.valueOf(prefs.getString(Prefs.RGB_MODE, RgbMode.OFF.name)!!)
        }.getOrDefault(RgbMode.OFF)
    }

    private fun saveMode(mode: RgbMode) {
        getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit().putString(Prefs.RGB_MODE, mode.name).apply()
    }

    private fun updateTileUi(mode: RgbMode) {
        qsTile?.apply {
            label = "RGB LED"
            subtitle = mode.label
            state = if (mode == RgbMode.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
