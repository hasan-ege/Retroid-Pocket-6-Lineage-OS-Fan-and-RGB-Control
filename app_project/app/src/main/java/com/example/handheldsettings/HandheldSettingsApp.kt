package com.example.handheldsettings

import android.app.Application
import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * Application subclass.
 * Opens a single persistent su shell at startup (libsu manages the lifecycle).
 * Magisk will show a grant dialog the first time this runs — that's expected.
 */
class HandheldSettingsApp : Application() {

    companion object {
        private const val TAG = "HandheldSettingsApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Configure libsu: use the Magisk shell, non-interactive, verbose logging in debug
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )

        // Eagerly open the shell so Magisk shows its permission dialog NOW
        // (before the user tries to do anything) rather than mid-operation.
        Shell.getShell { shell ->
            if (shell.isRoot) {
                Log.i(TAG, "Root shell granted — Magisk access OK")
                // Self-grant WRITE_SECURE_SETTINGS so the refresh-rate feature works
                // without needing the user to run adb manually.
                Shell.cmd("pm grant ${packageName} android.permission.WRITE_SECURE_SETTINGS")
                    .submit { result ->
                        Log.i(TAG, "WRITE_SECURE_SETTINGS grant: ${result.isSuccess}")
                    }
            } else {
                Log.w(TAG, "Root shell NOT granted — hardware control will not work")
            }
        }
    }
}
