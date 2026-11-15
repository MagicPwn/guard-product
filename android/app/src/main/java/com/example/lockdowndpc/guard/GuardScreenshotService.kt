package com.example.lockdowndpc.guard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.lockdowndpc.R
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/** Captures exactly one consented frame, uploads it, and never writes it to local storage. */
class GuardScreenshotService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        val callback = intent?.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
        if (!callId.matches(Regex("^[A-Za-z0-9._:-]{1,128}$")) || data == null) {
            callback?.send(RESULT_ERROR, Bundle().apply {
                putString(RESULT_MESSAGE, getString(R.string.guard_screenshot_invalid))
            })
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCapture(callId)
        thread(name = "guard-screenshot", isDaemon = true) {
            var message = getString(R.string.guard_screenshot_sent)
            var success = false
            try {
                Thread.sleep(CAPTURE_DELAY_MILLIS)
                val jpeg = capture(data, resultCode)
                GuardRemoteClient(this).uploadScreenshot(callId, jpeg.first, jpeg.second, jpeg.third)
                success = true
                GuardCallCoordinator.cancel(this, callId)
            } catch (exception: Exception) {
                message = getString(R.string.guard_screenshot_failed,
                    exception.message ?: exception.javaClass.simpleName)
            } finally {
                releaseProjection()
                notifyCompletion(message, success)
                callback?.send(if (success) RESULT_OK else RESULT_ERROR, Bundle().apply {
                    putString(RESULT_MESSAGE, message)
                })
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCapture(callId: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL,
                getString(R.string.guard_screenshot_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0,
            GuardCallCoordinator.callIntent(this, callId, "screenshot", false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.guard_screenshot_notification_title))
            .setContentText(getString(R.string.guard_screenshot_notification_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Throws(IOException::class)
    private fun capture(permissionData: Intent, resultCode: Int): Triple<ByteArray, Int, Int> {
        val manager = getSystemService(MediaProjectionManager::class.java)
            ?: throw IOException("MediaProjection is unavailable")
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        if (width !in 1..4096 || height !in 1..4096) throw IOException("Invalid screen size")
        projection = manager.getMediaProjection(resultCode, permissionData)
            ?: throw IOException("Screen capture permission was not granted")
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "Guard consented screenshot", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null
        ) ?: throw IOException("Unable to start screen capture")

        val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MILLIS
        var image = reader?.acquireLatestImage()
        while (image == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100L)
            image = reader?.acquireLatestImage()
        }
        if (image == null) throw IOException("The screen did not produce a frame")
        val bitmap = image.use { frame ->
            val plane = frame.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val paddedWidth = width + rowPadding / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            if (cropped !== padded) padded.recycle()
            cropped
        }
        try {
            return Triple(encodeJpeg(bitmap), width, height)
        } finally {
            bitmap.recycle()
        }
    }

    @Throws(IOException::class)
    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        for (quality in intArrayOf(85, 70, 50, 35, 20)) {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) continue
            val bytes = output.toByteArray()
            if (bytes.size <= MAX_JPEG_BYTES) return bytes
        }
        throw IOException("Screenshot exceeds the upload limit")
    }

    private fun releaseProjection() {
        try { virtualDisplay?.release() } catch (_: RuntimeException) {}
        try { reader?.close() } catch (_: RuntimeException) {}
        try { projection?.stop() } catch (_: RuntimeException) {}
        virtualDisplay = null
        reader = null
        projection = null
    }

    private fun notifyCompletion(message: String, success: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(if (success) R.string.guard_screenshot_complete_title
            else R.string.guard_screenshot_failed_title))
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(COMPLETE_ID, notification)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_RECEIVER = "receiver"
        const val RESULT_OK = 41
        const val RESULT_ERROR = 42
        const val RESULT_MESSAGE = "message"
        private const val CHANNEL = "guard_consented_screenshot"
        private const val NOTIFICATION_ID = 9201
        private const val COMPLETE_ID = 9202
        private const val CAPTURE_DELAY_MILLIS = 3_000L
        private const val CAPTURE_TIMEOUT_MILLIS = 6_000L
        private const val MAX_JPEG_BYTES = 2_000_000
    }
}
