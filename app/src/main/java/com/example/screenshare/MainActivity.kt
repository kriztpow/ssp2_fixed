package com.example.screenshare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private var isSharing = false
    private lateinit var projectionManager: MediaProjectionManager

    private val requestCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { intent ->
                    startCaptureService(result.resultCode, intent)
                    isSharing = true
                    updateStatus()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnToggle.setOnClickListener {
            if (!isSharing) {
                val captureIntent = projectionManager.createScreenCaptureIntent()
                requestCapture.launch(captureIntent)
            } else {
                stopCaptureService()
                isSharing = false
                updateStatus()
            }
        }

        updateStatus()
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val svc = Intent(this, ScreenCaptureService::class.java)
        svc.putExtra("resultCode", resultCode)
        svc.putExtra("data", data)
        ContextCompat.startForegroundService(this, svc)
    }

    private fun stopCaptureService() {
        val svc = Intent(this, ScreenCaptureService::class.java)
        stopService(svc)
    }

    private fun updateStatus() {
        tvStatus.text = if (isSharing) "Screen Sharing: ON" else "Screen Sharing: OFF"
        btnToggle.text = if (isSharing) "Stop Sharing" else "Start Sharing"
    }
}
