package com.example.screenshare

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var httpServer: SimpleMjpegServer? = null
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var lastFrame: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            // Note: mediaProjection setup omitted here; keep as null for compile-time safety if not provided
            virtualDisplay = null

            httpServer = SimpleMjpegServer(8080)
            httpServer?.start()

            // Simulate frames (to avoid needing MediaProjection at compile time)
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

    private inner class SimpleMjpegServer(port: Int) : NanoHTTPD(port) {
        @Volatile private var last: ByteArray? = null

        override fun serve(session: IHTTPSession?): Response {
            val uri = session?.uri ?: "/"
            return when (uri) {
                "/stream" -> serveMjpeg()
                else -> newFixedLengthResponse(Response.Status.OK, "text/html", "<html><body><h1>Screen Share</h1></body></html>")
            }
        }

        fun updateFrame(frame: ByteArray) {
            last = frame
        }

        private fun serveMjpeg(): Response {
            val boundary = "--frame"
            return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$boundary", object : Response.IStreamer {
                override fun stream(out: OutputStream) {
                    try {
                        while (!Thread.currentThread().isInterrupted) {
                            val frame = last ?: lastFrame
                            if (frame != null) {
                                val header = ("$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n").toByteArray()
                                out.write(header)
                                out.write(frame)
                                out.write("\r\n".toByteArray())
                                out.flush()
                            }
                            Thread.sleep(100)
                        }
                    } catch (e: Exception) {
                        // client disconnected
                    }
                }
            })
        }
    }
}
