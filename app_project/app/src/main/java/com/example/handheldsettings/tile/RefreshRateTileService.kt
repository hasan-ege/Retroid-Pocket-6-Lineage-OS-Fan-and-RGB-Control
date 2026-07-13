package com.example.handheldsettings.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.handheldsettings.hardware.RefreshRateController

/**
 * Quick Settings tile for display refresh rate (RP6_Complete_Guide.md §11.4).
 * Each tap cycles: 60 Hz → 90 Hz → 120 Hz → 60 Hz
 */
class RefreshRateTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileUi(RefreshRateController.current(applicationContext))
    }

    override fun onClick() {
        super.onClick()
        val next = RefreshRateController.current(applicationContext).next()
        RefreshRateController.set(applicationContext, next)
        updateTileUi(next)
    }

    private fun updateTileUi(rate: RefreshRateController.RefreshRate) {
        qsTile?.apply {
            label = "Refresh"
            subtitle = rate.label
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
