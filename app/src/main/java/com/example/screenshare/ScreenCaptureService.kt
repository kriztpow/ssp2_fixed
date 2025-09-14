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
import android.view.Surface
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.net.InetAddress
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

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", -1)
            val data = intent.getParcelableExtra<Intent>("data")
            startProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent?) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data!!)
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
    }

    private fun stopProjection() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        webServer?.stop()
    }

    private fun startWebServer() {
        webServer = WebServer()
        try {
            webServer?.start()
            Log.d("WebServer", "Server started at: ${getLocalIpAddress()}:8080")
        } catch (e: Exception) {
            Log.e("WebServer", "Error starting server: ${e.message}")
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
            Log.e("IPAddress", "Error getting IP address: ${ex.message}")
        }
        return "0.0.0.0"
    }

    private fun createNotification() {
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
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
    }

    inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
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

                    // Create bitmap
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    webServer?.updateImage(bitmap)
                }
            } catch (e: Exception) {
                Log.e("ImageAvailable", "Error processing image: ${e.message}")
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

                            response.write("--frame\r\n".toByteArray())
                            response.write("Content-Type: image/jpeg\r\n".toByteArray())
                            response.write("Content-Length: ${imageBytes.size}\r\n\r\n".toByteArray())
                            response.write(imageBytes)
                            response.write("\r\n\r\n".toByteArray())

                            // Small delay to prevent overwhelming the client
                            Thread.sleep(100)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WebServer", "Error serving stream: ${e.message}")
                }
            }.start()

            return response
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProjection()
    }
}
