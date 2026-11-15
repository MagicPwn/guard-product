package com.example.lockdowndpc.guard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.lockdowndpc.R
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A visible, child-controlled LiveKit room. No capture starts before an explicit tap. */
class GuardCallActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var eventJob: Job? = null
    private var statusJob: Job? = null
    private var room: Room? = null
    private var callId: String = ""
    private var kind: String = "audio"
    private var connected = false
    private var sharingScreen = false
    private var microphoneEnabled = false
    private var cameraEnabled = false
    private var status: TextView? = null
    private var acceptButton: Button? = null
    private var rejectButton: Button? = null
    private var muteButton: Button? = null
    private var cameraButton: Button? = null
    private var shareButton: Button? = null
    private var endButton: Button? = null
    private var remoteView: TextureViewRenderer? = null

    private val mediaPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { beginAcceptedCall() }

    private val screenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            setStatus(getString(R.string.guard_call_screen_cancelled))
            return@registerForActivityResult
        }
        val participant = room?.localParticipant
        if (participant == null) {
            setStatus(getString(R.string.guard_call_not_connected))
            return@registerForActivityResult
        }
        scope.launch {
            try {
                val params = ScreenCaptureParams(
                    mediaProjectionPermissionResultData = data,
                    notificationId = SCREEN_NOTIFICATION_ID,
                    notification = screenSharingNotification(),
                ) {
                    sharingScreen = false
                    setStatus(getString(R.string.guard_call_screen_stopped))
                }
                participant.setScreenShareEnabled(true, params)
                sharingScreen = true
                shareButton?.setText(R.string.guard_call_stop_screen)
                setStatus(getString(R.string.guard_call_screen_live))
            } catch (exception: Exception) {
                setStatus(getString(R.string.guard_call_failed, exception.message.orEmpty()))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callId = intent.getStringExtra(GuardCallCoordinator.EXTRA_CALL_ID).orEmpty()
        kind = intent.getStringExtra(GuardCallCoordinator.EXTRA_CALL_KIND) ?: "audio"
        if (!callId.matches(Regex("^[A-Za-z0-9._:-]{1,128}$"))) {
            finish()
            return
        }
        GuardCallCoordinator.setActiveCall(callId)
        ensureScreenChannel()
        buildUi()
        if (intent.getBooleanExtra(GuardCallCoordinator.EXTRA_AUTO_ACCEPT, false)) {
            acceptCall()
        } else {
            setStatus(getString(R.string.guard_call_waiting_consent, kindLabel(kind)))
        }
        monitorServerStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = getString(R.string.guard_call_title, kindLabel(kind))
            textSize = 24f
            gravity = Gravity.CENTER
        }
        root.addView(title)
        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }
        root.addView(status)
        remoteView = TextureViewRenderer(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMargins(0, 12, 0, 12) }
            contentDescription = getString(R.string.guard_call_remote_video)
        }
        root.addView(remoteView)

        val consentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        acceptButton = Button(this).apply {
            setText(R.string.guard_call_accept)
            setOnClickListener { acceptCall() }
        }
        rejectButton = Button(this).apply {
            setText(R.string.guard_call_reject)
            setOnClickListener { rejectCall() }
        }
        consentRow.addView(acceptButton)
        consentRow.addView(rejectButton)
        root.addView(consentRow)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        muteButton = Button(this).apply {
            setText(R.string.guard_call_mute)
            isEnabled = false
            setOnClickListener { toggleMicrophone() }
        }
        cameraButton = Button(this).apply {
            setText(R.string.guard_call_camera_on)
            isEnabled = false
            setOnClickListener { toggleCamera() }
        }
        shareButton = Button(this).apply {
            setText(R.string.guard_call_share_screen)
            isEnabled = false
            setOnClickListener { toggleScreenShare() }
        }
        controls.addView(muteButton)
        controls.addView(cameraButton)
        controls.addView(shareButton)
        root.addView(controls)

        endButton = Button(this).apply {
            setText(R.string.guard_call_end)
            setOnClickListener { endCall() }
        }
        root.addView(endButton)
        setContentView(root)
    }

    private fun acceptCall() {
        acceptButton?.isEnabled = false
        rejectButton?.isEnabled = false
        setStatus(getString(R.string.guard_call_connecting))
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (kind == "video") add(Manifest.permission.CAMERA)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isEmpty()) beginAcceptedCall() else mediaPermissions.launch(permissions.toTypedArray())
    }

    private fun beginAcceptedCall() {
        scope.launch {
            try {
                val credentials = withContext(Dispatchers.IO) {
                    val client = GuardRemoteClient(this@GuardCallActivity)
                    client.respondToCall(callId, true)
                    client.callCredentials(callId)
                }
                connect(credentials)
            } catch (exception: Exception) {
                setStatus(getString(R.string.guard_call_failed, exception.message.orEmpty()))
            }
        }
    }

    private suspend fun connect(credentials: GuardRemoteClient.LiveKitCredentials) {
        try {
            val created = LiveKit.create(applicationContext)
            room = created
            remoteView?.let { created.initVideoRenderer(it) }
            eventJob = scope.launch {
                created.events.collect { event ->
                    when (event) {
                        is RoomEvent.TrackSubscribed -> {
                            val track = event.track
                            if (track is RemoteVideoTrack) {
                                remoteView?.let { track.addRenderer(it) }
                            }
                        }
                        is RoomEvent.Disconnected -> {
                            connected = false
                            setStatus(getString(R.string.guard_call_ended))
                            finish()
                        }
                        is RoomEvent.Reconnecting -> setStatus(getString(R.string.guard_call_reconnecting))
                        is RoomEvent.Reconnected -> setStatus(getString(R.string.guard_call_connected))
                        else -> Unit
                    }
                }
            }
            created.connect(credentials.url, credentials.token)
            connected = true
            setStatus(getString(R.string.guard_call_connected))
            acceptButton?.visibility = Button.GONE
            rejectButton?.visibility = Button.GONE
            muteButton?.isEnabled = true
            cameraButton?.isEnabled = kind == "video"
            shareButton?.isEnabled = kind == "screen"
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    created.localParticipant.setMicrophoneEnabled(true)
                    microphoneEnabled = true
                    muteButton?.setText(R.string.guard_call_mute)
                } catch (_: Exception) {
                    setStatus(getString(R.string.guard_call_microphone_failed))
                }
            }
            if (kind == "video" && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    created.localParticipant.setCameraEnabled(true)
                    cameraEnabled = true
                    cameraButton?.setText(R.string.guard_call_camera_off)
                } catch (_: Exception) {
                    cameraEnabled = false
                }
            }
        } catch (exception: Exception) {
            setStatus(getString(R.string.guard_call_failed, exception.message.orEmpty()))
        }
    }

    private fun toggleMicrophone() {
        val current = room ?: return
        scope.launch {
            try {
                current.localParticipant.setMicrophoneEnabled(!microphoneEnabled)
                microphoneEnabled = !microphoneEnabled
                muteButton?.setText(if (microphoneEnabled) R.string.guard_call_mute
                    else R.string.guard_call_unmute)
            } catch (exception: Exception) {
                setStatus(getString(R.string.guard_call_failed, exception.message.orEmpty()))
            }
        }
    }

    private fun toggleCamera() {
        val current = room ?: return
        scope.launch {
            try {
                current.localParticipant.setCameraEnabled(!cameraEnabled)
                cameraEnabled = !cameraEnabled
                cameraButton?.setText(if (cameraEnabled) R.string.guard_call_camera_off
                    else R.string.guard_call_camera_on)
            } catch (exception: Exception) {
                setStatus(getString(R.string.guard_call_failed, exception.message.orEmpty()))
            }
        }
    }

    private fun toggleScreenShare() {
        val current = room ?: return
        if (sharingScreen) {
            scope.launch {
                try {
                    current.localParticipant.setScreenShareEnabled(false)
                    sharingScreen = false
                    shareButton?.setText(R.string.guard_call_share_screen)
                    setStatus(getString(R.string.guard_call_screen_stopped))
                } catch (exception: Exception) {
                    setStatus(getString(R.string.guard_call_failed, exception.message.orEmpty()))
                }
            }
            return
        }
        val projection = getSystemService(MediaProjectionManager::class.java) ?: return
        screenCapture.launch(projection.createScreenCaptureIntent())
    }

    private fun rejectCall() {
        GuardCallCoordinator.respond(this, callId, false, null)
        finish()
    }

    private fun endCall() {
        val button = endButton ?: return
        button.isEnabled = false
        button.setText(R.string.guard_call_ending)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    GuardRemoteClient(this@GuardCallActivity).endCall(callId)
                }
                room?.disconnect()
                finish()
            } catch (exception: Exception) {
                setStatus(getString(R.string.guard_call_end_failed,
                    exception.message ?: exception.javaClass.simpleName))
                button.setText(R.string.guard_call_end)
                button.isEnabled = true
            }
        }
    }

    private fun monitorServerStatus() {
        statusJob = scope.launch {
            while (isActive) {
                delay(STATUS_POLL_MS)
                val status = try {
                    withContext(Dispatchers.IO) {
                        GuardRemoteClient(this@GuardCallActivity).callStatus(callId).status
                    }
                } catch (_: Exception) {
                    continue
                }
                if (status == "ended" || status == "rejected" || status == "expired") {
                    room?.disconnect()
                    setStatus(getString(R.string.guard_call_ended))
                    finish()
                    break
                }
            }
        }
    }

    private fun screenSharingNotification() = NotificationCompat.Builder(this, SCREEN_CHANNEL)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle(getString(R.string.guard_call_screen_notification_title))
        .setContentText(getString(R.string.guard_call_screen_notification_text))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(PendingIntent.getActivity(this, SCREEN_NOTIFICATION_ID,
            GuardCallCoordinator.callIntent(this, callId, kind, false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun ensureScreenChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(SCREEN_CHANNEL,
            getString(R.string.guard_call_screen_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun setStatus(value: String) {
        status?.text = value
    }

    private fun kindLabel(value: String): String = when (value) {
        "video" -> getString(R.string.guard_call_kind_video)
        "screen" -> getString(R.string.guard_call_kind_screen)
        else -> getString(R.string.guard_call_kind_audio)
    }

    override fun onDestroy() {
        GuardCallCoordinator.clearActiveCall(callId)
        eventJob?.cancel()
        statusJob?.cancel()
        room?.disconnect()
        room = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SCREEN_CHANNEL = "guard_screen_share"
        private const val SCREEN_NOTIFICATION_ID = 9102
        private const val STATUS_POLL_MS = 5_000L
    }
}
