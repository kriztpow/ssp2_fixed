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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var webServer: WebServer? = null
    private var mediaProjectionManager: MediaProjectionManager? = null

    private val notificationId = 101
    private val channelId = "ScreenCaptureChannel"

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", -1)
            val data = intent.getParcelableExtra<Intent>("data")
            if (data != null && resultCode != -1) {
                startCapture(resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        imageReader = ImageReader.Builder(1080).setHeight(1920).setFormat(PixelFormat.RGBA_8888).setMaxImages(2).build()

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            1080,
            1920,
             resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        webServer = WebServer()
        try {
            webServer?.start()
            showNotification(webServer?.getHostAddress())
        } catch (e: IOException) {
            e.printStackTrace()
        }

        imageReader?.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    webServer?.updateImage(bitmap)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image?.close()
            }
        }, null)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val width = image.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun showNotification(ipAddress: String?) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Transmisión de pantalla activa")
            .setContentText("Dirección: $ipAddress")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(notificationId, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Screen Capture Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        webServer?.stop()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }

    inner class WebServer : NanoHTTPD(8080) {

        private val imageQueue = LinkedBlockingQueue<Bitmap>(2)
        private var isStreaming = false

        fun updateImage(bitmap: Bitmap) {
            if (imageQueue.size >= 2) {
                imageQueue.poll()
            }
            imageQueue.offer(bitmap)
        }

        fun getHostAddress(): String {
            var ip = ""
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            ip = addr.hostAddress
                            break
                        }
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            return "http://$ip:8080/stream"
        }

        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/stream") {
                isStreaming = true
                val response = Response.newChunkedResponse(
                    Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=--frameboundary",
                    null
                )
                response.addHeader("Connection", "close")
                response.addHeader("Max-Age", "0")
                response.addHeader("Expires", "0")
                response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0")
                response.addHeader("Pragma", "no-cache")
                response.addHeader("Access-Control-Allow-Origin", "*")

                Thread {
                    try {
                        while (isStreaming) {
                            val image = imageQueue.take()
                            val baos = ByteArrayOutputStream()
                            image.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                            val imageBytes = baos.toByteArray()

                            val part = "--frameboundary\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: ${imageBytes.size}\r\n" +
                                    "\r\n"
                            response.write(part.toByteArray())
                            response.write(imageBytes)
                            response.write("\r\n".toByteArray())
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        isStreaming = false
                    }
                }.start()

                return response
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }
}
