package com.example.handheldsettings.tile

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.handheldsettings.data.CpuMode
import com.example.handheldsettings.data.Prefs
import com.example.handheldsettings.service.SystemControlService

class CpuProfileTileService : TileService() {
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

    private fun currentMode(): CpuMode {
        val prefs = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        return runCatching {
            CpuMode.valueOf(prefs.getString(Prefs.CPU_MODE, CpuMode.BALANCED.name)!!)
        }.getOrDefault(CpuMode.BALANCED)
    }

    private fun saveMode(mode: CpuMode) {
        getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .edit().putString(Prefs.CPU_MODE, mode.name).apply()
    }

    private fun updateTileUi(mode: CpuMode) {
        qsTile?.apply {
            label = "CPU Profile"
            subtitle = mode.label
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
