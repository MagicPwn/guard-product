package com.example.lockdowndpc.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.lockdowndpc.R
import com.example.lockdowndpc.kiosk.BidiText
import com.example.lockdowndpc.policy.AuditLog
import com.example.lockdowndpc.recovery.DeviceOwnerRelease
import com.example.lockdowndpc.recovery.EmergencyAccessController
import com.example.lockdowndpc.recovery.EmergencyAccessWindow
import com.example.lockdowndpc.security.AdminPinStore
import com.example.lockdowndpc.security.AdminSession
import com.example.lockdowndpc.ui.theme.LockdownTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/**
 * Authenticated recovery console for the two operations that must remain
 * reachable when ordinary policy management is not enough: a bounded emergency
 * exception, and a verified exit from Device Owner management.
 *
 * <p>Both operations ask for the administrator PIN again even though the
 * activity itself is only reachable from a live administrator session. The
 * activity is also excluded from screenshots in release builds, and no secret
 * is written to the audit log.
 */
class RecoveryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdminSession.isUnlocked()) {
            finish()
            return
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, SYSTEM_BAR_DARK_SCRIM),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, SYSTEM_BAR_DARK_SCRIM),
        )
        setTitle(R.string.recovery_access_title)
        setContent {
            LockdownTheme {
                RecoveryAccessScreen(onClose = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!AdminSession.isUnlocked()) {
            finish()
        }
    }
}

private enum class SensitiveAction {
    START_EMERGENCY,
    STOP_EMERGENCY,
    RELEASE_OWNER,
}

@Composable
private fun RecoveryAccessScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf(EmergencyAccessController.read(context)) }
    var message by remember { mutableStateOf<UiMessage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var startWarningVisible by remember { mutableStateOf(false) }
    var releaseWarningVisible by remember { mutableStateOf(false) }
    var releaseFinalVisible by remember { mutableStateOf(false) }
    var releaseSuccessVisible by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<SensitiveAction?>(null) }
    var pin by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            if (!AdminSession.isUnlocked()) {
                onClose()
                return@LaunchedEffect
            }
            snapshot = withContext(Dispatchers.IO) {
                EmergencyAccessController.read(context)
            }
        }
    }

    fun beginAuthentication(action: SensitiveAction) {
        if (busy) return
        pendingAction = action
        pin = ""
        authError = null
    }

    fun perform(action: SensitiveAction, recoveryCodeUsed: Boolean) {
        coroutineScope.launch {
            busy = true
            val notice = if (recoveryCodeUsed) {
                context.getString(R.string.recovery_auth_recovery_used)
            } else {
                null
            }
            try {
                when (action) {
                    SensitiveAction.START_EMERGENCY -> {
                        val next = withContext(Dispatchers.IO) {
                            EmergencyAccessController.start(
                                context,
                                EmergencyAccessWindow.DEFAULT_DURATION_MILLIS,
                            )
                        }
                        snapshot = next
                        val started = context.getString(
                            R.string.recovery_access_started,
                            EmergencyAccessWindow.DEFAULT_DURATION_MILLIS / 60_000L,
                        )
                        message = UiMessage(listOfNotNull(started, notice).joinToString("\n"), false)
                    }

                    SensitiveAction.STOP_EMERGENCY -> {
                        snapshot = withContext(Dispatchers.IO) {
                            EmergencyAccessController.end(context, "administrator")
                        }
                        val stopped = context.getString(R.string.recovery_access_stopped)
                        message = UiMessage(listOfNotNull(stopped, notice).joinToString("\n"), false)
                    }

                    SensitiveAction.RELEASE_OWNER -> {
                        releaseFinalVisible = true
                        if (notice != null) {
                            message = UiMessage(notice, true)
                        }
                    }
                }
            } catch (exception: RuntimeException) {
                val detail = exception.message ?: context.getString(R.string.recovery_unknown_error)
                val template = if (action == SensitiveAction.RELEASE_OWNER) {
                    R.string.recovery_release_failed
                } else {
                    R.string.recovery_action_failed
                }
                val failed = context.getString(template, BidiText.bidiIsolated(detail))
                message = UiMessage(
                    listOfNotNull(failed, notice).joinToString("\n"),
                    true,
                )
            } finally {
                busy = false
            }
        }
    }

    fun submitPin() {
        val action = pendingAction ?: return
        val entered = pin
        if (entered.isBlank() || busy) return
        pin = ""
        coroutineScope.launch {
            busy = true
            val result = withContext(Dispatchers.Default) {
                AdminPinStore.verify(context, entered)
            }
            when (result.status()) {
                AdminPinStore.Status.SUCCESS -> {
                    AdminSession.unlock()
                    pendingAction = null
                    authError = null
                    if (result.usedRecoveryCode()) {
                        AuditLog.append(context, context.getString(R.string.audit_recovery_used))
                    }
                    perform(action, result.usedRecoveryCode())
                }

                AdminPinStore.Status.INVALID -> {
                    authError = context.getString(R.string.recovery_auth_invalid)
                }

                AdminPinStore.Status.LOCKED -> {
                    authError = context.getString(R.string.recovery_auth_locked)
                }

                AdminPinStore.Status.NOT_CONFIGURED -> {
                    authError = context.getString(R.string.pin_error_save)
                }

                AdminPinStore.Status.ERROR -> {
                    authError = context.getString(R.string.pin_error_verify)
                }

                else -> {
                    authError = context.getString(R.string.recovery_unknown_error)
                }
            }
            busy = false
        }
    }

    fun releaseOwner() {
        if (busy) return
        coroutineScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                DeviceOwnerRelease.release(context)
            }
            if (result.released()) {
                releaseSuccessVisible = true
            } else {
                val detail = releaseFailureDetail(context, result)
                val template = if (result.restrictionsRestored()) {
                    R.string.recovery_release_partial
                } else {
                    R.string.recovery_release_failed
                }
                message = UiMessage(
                    context.getString(template, BidiText.bidiIsolated(detail)),
                    true,
                )
            }
            busy = false
        }
    }

    val statusText = if (snapshot.active()) {
        stringResource(
            R.string.recovery_access_active,
            BidiText.ltrIsolated(formatRemaining(snapshot.remainingMillis())),
        )
    } else {
        stringResource(R.string.recovery_access_inactive)
    }

    LockdownScreen(
        title = stringResource(R.string.recovery_access_title),
        subtitle = stringResource(R.string.recovery_access_subtitle),
    ) {
        MessageBanner(message)
        if (message != null) {
            Spacer(Modifier.height(16.dp))
        }
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(18.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        if (snapshot.active()) {
            PrimaryAction(
                text = stringResource(R.string.recovery_access_stop),
                icon = Icons.Rounded.LockOpen,
                enabled = !busy,
                onClick = { beginAuthentication(SensitiveAction.STOP_EMERGENCY) },
            )
        } else {
            PrimaryAction(
                text = stringResource(
                    R.string.recovery_access_start,
                    EmergencyAccessWindow.DEFAULT_DURATION_MILLIS / 60_000L,
                ),
                icon = Icons.Rounded.LockOpen,
                enabled = !busy,
                onClick = { startWarningVisible = true },
            )
        }

        Spacer(Modifier.height(28.dp))
        SectionCard(title = stringResource(R.string.recovery_release_title)) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.recovery_release_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                ActionRow(
                    icon = Icons.Rounded.Warning,
                    title = stringResource(R.string.recovery_release_confirm),
                    enabled = !busy,
                    onClick = { releaseWarningVisible = true },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SecondaryAction(text = stringResource(R.string.action_close), onClick = onClose)
    }

    if (startWarningVisible) {
        AlertDialog(
            onDismissRequest = { startWarningVisible = false },
            title = { Text(stringResource(R.string.recovery_access_start_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.recovery_access_start_body,
                        EmergencyAccessWindow.DEFAULT_DURATION_MILLIS / 60_000L,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        startWarningVisible = false
                        beginAuthentication(SensitiveAction.START_EMERGENCY)
                    }
                ) {
                    Text(stringResource(R.string.recovery_access_start_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { startWarningVisible = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (releaseWarningVisible) {
        AlertDialog(
            onDismissRequest = { releaseWarningVisible = false },
            title = { Text(stringResource(R.string.recovery_release_title)) },
            text = { Text(stringResource(R.string.recovery_release_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        releaseWarningVisible = false
                        beginAuthentication(SensitiveAction.RELEASE_OWNER)
                    }
                ) {
                    Text(stringResource(R.string.recovery_release_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { releaseWarningVisible = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (releaseFinalVisible) {
        AlertDialog(
            onDismissRequest = { releaseFinalVisible = false },
            title = { Text(stringResource(R.string.recovery_release_final_title)) },
            text = { Text(stringResource(R.string.recovery_release_final_body)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        releaseFinalVisible = false
                        releaseOwner()
                    },
                ) {
                    Text(stringResource(R.string.recovery_release_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { releaseFinalVisible = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (releaseSuccessVisible) {
        AlertDialog(
            onDismissRequest = {
                releaseSuccessVisible = false
                AdminSession.lock()
                onClose()
            },
            title = { Text(stringResource(R.string.recovery_release_success_title)) },
            text = { Text(stringResource(R.string.recovery_release_success_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        releaseSuccessVisible = false
                        AdminSession.lock()
                        onClose()
                    }
                ) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    val action = pendingAction
    if (action != null) {
        AlertDialog(
            onDismissRequest = {
                pendingAction = null
                pin = ""
                authError = null
            },
            title = { Text(stringResource(R.string.recovery_auth_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.recovery_auth_body))
                    Spacer(Modifier.height(12.dp))
                    PinField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = stringResource(R.string.locked_field_pin),
                        imeAction = ImeAction.Done,
                        onImeAction = ::submitPin,
                    )
                    if (authError != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = authError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && pin.isNotBlank(), onClick = ::submitPin) {
                    Text(stringResource(R.string.recovery_auth_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        pin = ""
                        authError = null
                    }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun formatRemaining(millis: Long): String {
    val seconds = max(1L, (millis + 999L) / 1000L)
    return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60L, seconds % 60L)
}

private fun releaseFailureDetail(
    context: Context,
    result: DeviceOwnerRelease.Result,
): String = when (result.errors().firstOrNull()) {
    "exit-kiosk-first" -> context.getString(R.string.recovery_release_exit_kiosk)
    "close-maintenance-first" -> context.getString(R.string.recovery_release_close_maintenance)
    null -> context.getString(R.string.recovery_unknown_error)
    else -> result.errors().first()
}
