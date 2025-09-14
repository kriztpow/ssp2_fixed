package com.example.screenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var httpServer: SimpleMjpegServer? = null
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var lastFrame: ByteArray? = null

    companion object {
        const val NOTIF_CHANNEL_ID = "screen_capture_channel"
        const val NOTIF_CHANNEL_NAME = "Screen Capture"
        const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannelIfNeeded()
        val notification = buildNotification()

        // Arrancar el servicio en primer plano inmediatamente
        startForeground(NOTIF_ID, notification)

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(
                width,
                height,
                android.graphics.PixelFormat.RGBA_8888,
                2
            )
            // MediaProjection no configurado en este ejemplo (null para compilar seguro)
            virtualDisplay = null

            httpServer = SimpleMjpegServer(8080)
            httpServer?.start()

            // Simulación de frames para evitar necesitar MediaProjection en compilación
            pool.submit {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                        lastFrame = baos.toByteArray()
                        baos.close()
                        bmp.recycle()
                        Thread.sleep(200)
                    } catch (t: Throwable) {
                        Log.e("SCS", "Frame loop error", t)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SCS", "Start error", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        httpServer?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        pool.shutdownNow()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Screen sharing active")
            .setContentText("Your screen is being shared")
            .setSmallIcon(android.R.drawable.ic_menu_camera)  // usar tu ícono real
            .setOngoing(true)
            .build()
    }

    private inner class SimpleMjpegServer(port: Int) : NanoHTTPD(port) {
        @Volatile private var last: ByteArray? = null

        override fun serve(session: IHTTPSession?): Response {
            val uri = session?.uri ?: "/"
            return when (uri) {
                "/stream" -> serveMjpeg()
                else -> newFixedLengthResponse(
                    Response.Status.OK,
                    "text/html",
                    "<html><body><h1>Screen Share</h1></body></html>"
                )
            }
        }

        fun updateFrame(frame: ByteArray) {
            last = frame
        }

        private fun serveMjpeg(): Response {
            val boundary = "--frame"

            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut)

            pool.submit {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val frame = last ?: lastFrame
                        if (frame != null) {
                            val header = ("$boundary\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: ${frame.size}\r\n\r\n").toByteArray()
                            pipedOut.write(header)
                            pipedOut.write(frame)
                            pipedOut.write("\r\n".toByteArray())
                            pipedOut.flush()
                        }
                        Thread.sleep(100)
                    }
                } catch (e: Exception) {
                    Log.e("SCS", "Stream thread error", e)
                } finally {
                    try {
                        pipedOut.close()
                    } catch (_: Exception) {}
                }
            }

            return newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=$boundary",
                pipedIn
            )
        }
    }
}
