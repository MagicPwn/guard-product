package com.example.lockdowndpc.guard

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.lockdowndpc.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/** One-shot screenshot consent. Capture starts only after an explicit child tap. */
class GuardScreenshotActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var callId = ""
    private var accepted = false
    private var requestActive = true
    private var statusJob: Job? = null
    private var status: TextView? = null
    private var captureButton: Button? = null
    private var cancelButton: Button? = null

    private val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val message = resultData?.getString(GuardScreenshotService.RESULT_MESSAGE).orEmpty()
            status?.text = message
            captureButton?.isEnabled = false
            cancelButton?.isEnabled = true
            if (resultCode == GuardScreenshotService.RESULT_OK) {
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1_500L)
            } else {
                captureButton?.isEnabled = true
            }
        }
    }

    private val projectionPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (!requestActive) {
            finish()
            return@registerForActivityResult
        }
        if (result.resultCode != RESULT_OK || data == null) {
            status?.setText(R.string.guard_screenshot_cancelled)
            captureButton?.isEnabled = true
            return@registerForActivityResult
        }
        val service = Intent(this, GuardScreenshotService::class.java).apply {
            putExtra(GuardScreenshotService.EXTRA_CALL_ID, callId)
            putExtra(GuardScreenshotService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(GuardScreenshotService.EXTRA_PROJECTION_DATA, data)
            putExtra(GuardScreenshotService.EXTRA_RECEIVER, receiver)
        }
        ContextCompat.startForegroundService(this, service)
        status?.setText(R.string.guard_screenshot_countdown)
        Handler(Looper.getMainLooper()).postDelayed({ moveTaskToBack(true) }, 500L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callId = intent.getStringExtra(GuardCallCoordinator.EXTRA_CALL_ID).orEmpty()
        if (!callId.matches(Regex("^[A-Za-z0-9._:-]{1,128}$"))) {
            finish()
            return
        }
        GuardCallCoordinator.setActiveCall(callId)
        buildUi()
        monitorServerStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.guard_screenshot_title)
            textSize = 26f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.guard_screenshot_intro)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        })
        status = TextView(this).apply {
            text = getString(R.string.guard_screenshot_waiting)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        root.addView(status)
        captureButton = Button(this).apply {
            setText(R.string.guard_screenshot_capture)
            setOnClickListener { requestCapture() }
        }
        root.addView(captureButton)
        cancelButton = Button(this).apply {
            setText(R.string.guard_screenshot_decline)
            setOnClickListener { declineOrClose() }
        }
        root.addView(cancelButton)
        setContentView(root)
    }

    private fun requestCapture() {
        if (!requestActive) {
            status?.setText(R.string.guard_screenshot_ended)
            return
        }
        captureButton?.isEnabled = false
        cancelButton?.isEnabled = false
        status?.setText(R.string.guard_screenshot_requesting)
        thread(name = "guard-screenshot-consent", isDaemon = true) {
            try {
                GuardRemoteClient(this).respondToCall(callId, true)
                accepted = true
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    if (manager == null) {
                        status?.setText(R.string.guard_screenshot_failed
                            .let { getString(it, "MediaProjection unavailable") })
                        captureButton?.isEnabled = true
                    } else {
                        projectionPermission.launch(manager.createScreenCaptureIntent())
                    }
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    status?.text = getString(R.string.guard_screenshot_failed,
                        exception.message ?: exception.javaClass.simpleName)
                    captureButton?.isEnabled = true
                    cancelButton?.isEnabled = true
                }
            }
        }
    }

    private fun declineOrClose() {
        if (!accepted) GuardCallCoordinator.respond(this, callId, false, null)
        finish()
    }

    private fun monitorServerStatus() {
        statusJob = scope.launch {
            while (isActive) {
                delay(STATUS_POLL_MS)
                val state = try {
                    withContext(Dispatchers.IO) {
                        GuardRemoteClient(this@GuardScreenshotActivity).callStatus(callId).status
                    }
                } catch (_: Exception) {
                    continue
                }
                if (state == "ended" || state == "rejected" || state == "expired") {
                    requestActive = false
                    captureButton?.isEnabled = false
                    cancelButton?.isEnabled = false
                    status?.setText(R.string.guard_screenshot_ended)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isFinishing && !isDestroyed) finish()
                    }, 1_500L)
                    break
                }
            }
        }
    }

    override fun onDestroy() {
        statusJob?.cancel()
        scope.cancel()
        GuardCallCoordinator.clearActiveCall(callId)
        super.onDestroy()
    }

    companion object {
        private const val STATUS_POLL_MS = 5_000L
    }
}
