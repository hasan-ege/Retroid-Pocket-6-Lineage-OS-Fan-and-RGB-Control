package com.example.handheldsettings.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

class MediaProjectionActivity : Activity() {
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), 1000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1000) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i("MediaProjection", "Got permission token, sending to SystemControlService")
                val serviceIntent = Intent(this, SystemControlService::class.java).apply {
                    action = SystemControlService.ACTION_SET_PROJECTION_INTENT
                    putExtra(SystemControlService.EXTRA_PROJECTION_INTENT, data)
                }
                startForegroundService(serviceIntent)
            } else {
                Log.e("MediaProjection", "Permission denied by user")
            }
        }
        finish()
    }
}
