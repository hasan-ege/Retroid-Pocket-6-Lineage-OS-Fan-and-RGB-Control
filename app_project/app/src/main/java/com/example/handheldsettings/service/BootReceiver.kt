package com.example.handheldsettings.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED and auto-starts SystemControlService.
 * This ensures the fan control loop runs even after a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED") {
            Log.i(TAG, "Boot detected ($action) — starting SystemControlService")
            SystemControlService.startOrUpdate(context)
        }
    }
}
