package com.example.screenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.util.Collections

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var webServer: WebServer? = null
    private var width = 1080
    private var height = 1920
    private var dpi = 320

    companion object {
        private const val TAG = "ScreenCaptureService"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        
        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", -1)
            val data = intent.getParcelableExtra<Intent>("data")
            
            if (resultCode != -1 && data != null) {
                Log.d(TAG, "Starting projection with resultCode: $resultCode")
                startProjection(resultCode, data)
            } else {
                Log.e(TAG, "Invalid resultCode or data. resultCode: $resultCode, data: $data")
                stopSelf()
            }
        } else {
            Log.e(TAG, "Intent is null")
            stopSelf()
        }
        
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            mediaProjection?.registerCallback(MediaProjectionCallback(), null)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            imageReader?.setOnImageAvailableListener(ImageAvailableListener(), null)
            startWebServer()
            createNotification()
            
            Log.d(TAG, "Projection started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting projection: ${e.message}")
            stopSelf()
        }
    }

    private fun stopProjection() {
        try {
            virtualDisplay?.release()
            mediaProjection?.stop()
            webServer?.stop()
            Log.d(TAG, "Projection stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping projection: ${e.message}")
        }
    }

    private fun startWebServer() {
        webServer = WebServer()
        try {
            webServer?.start()
            Log.d(TAG, "Web server started at: ${getLocalIpAddress()}:8080")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting web server: ${e.message}")
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP address: ${ex.message}")
        }
        return "0.0.0.0"
    }

    private fun createNotification() {
        try {
            val channelId = "screen_capture_channel"
            val channelName = "Screen Capture Service"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, channelName, importance)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = Notification.Builder(this, channelId)
                .setContentTitle("Screen Sharing Active")
                .setContentText("Streaming screen to ${getLocalIpAddress()}:8080")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .build()

            startForeground(1, notification)
            Log.d(TAG, "Notification created")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification: ${e.message}")
        }
    }

    inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped")
            stopProjection()
        }
    }

    inner class ImageAvailableListener : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader?) {
            var image: Image? = null
            try {
                image = reader?.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    webServer?.updateImage(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image: ${e.message}")
            } finally {
                image?.close()
            }
        }
    }

    inner class WebServer : NanoHTTPD(8080) {
        private var latestImage: Bitmap? = null

        fun updateImage(bitmap: Bitmap) {
            latestImage = bitmap
        }

        override fun serve(session: IHTTPSession): Response {
            val response = newFixedLengthResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--frame", null)
            response.addHeader("Connection", "close")
            response.addHeader("Max-Age", "0")
            response.addHeader("Expires", "0")
            response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type")

            Thread {
                try {
                    while (true) {
                        latestImage?.let { image ->
                            val baos = ByteArrayOutputStream()
                            image.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                            val imageBytes = baos.toByteArray()

                            val boundaryData = (
                                "--frame\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${imageBytes.size}\r\n\r\n"
                            ).toByteArray()

                            val finalData = ByteArray(boundaryData.size + imageBytes.size + 4)
                            System.arraycopy(boundaryData, 0, finalData, 0, boundaryData.size)
                            System.arraycopy(imageBytes, 0, finalData, boundaryData.size, imageBytes.size)
                            System.arraycopy("\r\n\r\n".toByteArray(), 0, finalData, boundaryData.size + imageBytes.size, 4)

                            response.setData(finalData.inputStream())
                            Thread.sleep(100)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error serving stream: ${e.message}")
                }
            }.start()

            return response
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopProjection()
    }
}
