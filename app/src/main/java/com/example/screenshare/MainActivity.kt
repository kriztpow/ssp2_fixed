package com.example.screenshare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
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
    private var resultDataIntent: Intent? = null
    private lateinit var projectionManager: MediaProjectionManager

    private val requestCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { intent ->
                    resultDataIntent = intent
                    startCaptureService()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnToggle.setOnClickListener {
            if (!isSharing) {
                val captureIntent = projectionManager.createScreenCaptureIntent()
                requestCapture.launch(captureIntent)
            } else {
                stopCaptureService()
            }
        }

        updateStatus()
    }

    private fun startCaptureService() {
        val svc = Intent(this, ScreenCaptureService::class.java)
        svc.putExtra("resultData", resultDataIntent)
        ContextCompat.startForegroundService(this, svc)
        isSharing = true
        updateStatus()
    }

    private fun stopCaptureService() {
        val svc = Intent(this, ScreenCaptureService::class.java)
        stopService(svc)
        isSharing = false
        updateStatus()
    }

    private fun updateStatus() {
        val ip = getLocalIpAddress()
        tvStatus.text = if (isSharing) "Compartiendo en http://$ip:8080/" else "Detenido - IP: $ip"
        btnToggle.text = if (isSharing) "Detener" else "Compartir"
    }

    private fun getLocalIpAddress(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        return String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isSharing) stopCaptureService()
    }
}
