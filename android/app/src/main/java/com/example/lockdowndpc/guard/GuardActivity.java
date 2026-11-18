package com.example.lockdowndpc.guard;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.lockdowndpc.R;
import com.example.lockdowndpc.policy.AuditLog;
import com.example.lockdowndpc.policy.PolicyReconciliationCoordinator;
import com.example.lockdowndpc.security.AdminSession;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GuardActivity extends AppCompatActivity {
    private static final ExecutorService REMOTE_EXECUTOR = Executors.newSingleThreadExecutor();
    private Switch enabled;
    private Switch quiz;
    private Switch launcher;
    private EditText packageName;
    private EditText minutes;
    private EditText scheduleWindow;
    private EditText scheduleDays;
    private TextView scheduleStatus;
    private Button saveButton;
    private Button rollbackButton;
    private TextView quizPackStatus;
    private EditText remoteServer;
    private TextView remoteStatus;
    private Button remoteEnrollButton;
    private Button remoteSyncButton;
    private Button remotePinButton;

    private final ActivityResultLauncher<String[]> openQuizPack = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importQuizPack(uri);
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!AdminSession.isUnlocked()) { finish(); return; }
        setTitle(R.string.guard_title);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(32));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText(R.string.guard_title);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);
        TextView warning = new TextView(this);
        warning.setText(R.string.guard_status_warning);
        warning.setTextColor(Color.rgb(70, 78, 92));
        warning.setPadding(0, dp(6), 0, 0);
        root.addView(warning);
        GuardPolicy policy;
        try {
            policy = GuardPolicyStore.read(this);
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.guard_degraded, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout limits = addSection(root, R.string.guard_section_limits,
                R.string.guard_section_limits_hint);
        enabled = addSwitch(limits, R.string.guard_enable, policy.enabled());
        String first = policy.dailyBudgets().isEmpty() ? "" : policy.dailyBudgets().keySet().iterator().next();
        packageName = addField(limits, R.string.guard_package_hint, first);
        minutes = addField(limits, R.string.guard_minutes_hint,
                first.isEmpty() ? "60" : Integer.toString(policy.dailyBudgets().get(first)));
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        scheduleWindow = addField(limits, R.string.guard_schedule_window_hint, "16:00-18:00");
        scheduleDays = addField(limits, R.string.guard_schedule_days_hint, "*");
        scheduleStatus = addNote(limits);
        button(limits, R.string.guard_schedule_apply).setOnClickListener(
                view -> applySchedule(false));
        button(limits, R.string.guard_schedule_clear).setOnClickListener(
                view -> applySchedule(true));
        launcher = addSwitch(limits, R.string.guard_launcher, policy.launcherEnabled());
        TextView launcherNotice = addNote(limits);
        launcherNotice.setText(R.string.guard_launcher_unavailable);
        quiz = addSwitch(limits, R.string.guard_quiz, policy.quizGateEnabled());
        button(limits, R.string.guard_usage_access).setOnClickListener(view -> {
            if (!AdminSession.isUnlocked()) { finish(); return; }
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        });
        TextView usageHint = addNote(limits);
        usageHint.setText(R.string.guard_usage_access_hint);
        saveButton = button(limits, R.string.guard_save);
        saveButton.setOnClickListener(this::save);
        TextView saveHint = addNote(limits);
        saveHint.setText(R.string.guard_section_save_hint);

        LinearLayout quizSection = addSection(root, R.string.guard_section_quiz,
                R.string.guard_section_quiz_hint);
        quizPackStatus = addNote(quizSection);
        EditText trustAnchor = addField(quizSection, R.string.guard_quiz_trust_hint, "");
        trustAnchor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        button(quizSection, R.string.guard_quiz_install_trust).setOnClickListener(
                view -> installTrustAnchor(trustAnchor.getText().toString()));
        button(quizSection, R.string.guard_quiz_reset_trust).setOnClickListener(
                view -> resetQuizTrust());
        button(quizSection, R.string.guard_quiz_import).setOnClickListener(view -> {
            if (!AdminSession.isUnlocked()) { finish(); return; }
            openQuizPack.launch(new String[]{"application/json", "text/plain"});
        });
        rollbackButton = button(quizSection, R.string.guard_quiz_rollback);
        rollbackButton.setOnClickListener(view -> rollbackQuizPack());
        button(quizSection, R.string.guard_quiz_open).setOnClickListener(view ->
                startActivity(new Intent(this, GuardQuizActivity.class)));

        addRemoteSection(root);
        refreshQuizPackStatus();
        refreshScheduleStatus(policy);
        TextView footerGap = new TextView(this);
        footerGap.setPadding(0, dp(16), 0, 0);
        root.addView(footerGap);
        button(root, R.string.guard_close).setOnClickListener(view -> finish());
    }

    private void addRemoteSection(LinearLayout root) {
        LinearLayout section = addSection(root, R.string.guard_remote_section,
                R.string.guard_section_service_hint);
        remoteServer = addField(section, R.string.guard_remote_server_hint,
                GuardRemoteStore.serverUrl(this));
        remoteServer.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        button(section, R.string.guard_remote_save_server).setOnClickListener(
                view -> saveRemoteServer());
        remoteEnrollButton = button(section, R.string.guard_remote_enroll);
        remoteEnrollButton.setOnClickListener(view -> runRemote(true));
        remoteSyncButton = button(section, R.string.guard_remote_sync);
        remoteSyncButton.setOnClickListener(view -> runRemote(false));
        remotePinButton = button(section, R.string.guard_remote_pin_key);
        remotePinButton.setOnClickListener(view -> pinRemoteKey());
        remoteStatus = addNote(section);
        refreshRemoteStatus();
    }

    private void saveRemoteServer() {
        if (!AdminSession.isUnlocked()) { finish(); return; }
        try {
            String normalized = GuardRemoteStore.normalizeServerUrl(
                    remoteServer.getText().toString());
            if (!GuardRemoteStore.setServerUrl(this, normalized)) {
                Toast.makeText(this, R.string.guard_remote_failed, Toast.LENGTH_LONG).show();
                return;
            }
            AuditLog.append(this, "guard-remote-server-configured");
            GuardScheduler.schedule(this);
            GuardScheduler.syncNow(this);
            refreshRemoteStatus();
            Toast.makeText(this, R.string.guard_remote_server_saved, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, R.string.guard_remote_invalid_server, Toast.LENGTH_LONG).show();
        }
    }

    private void runRemote(boolean enroll) {
        if (!AdminSession.isUnlocked()) { finish(); return; }
        try {
            GuardRemoteStore.setServerUrl(this, remoteServer.getText().toString());
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, R.string.guard_remote_invalid_server, Toast.LENGTH_LONG).show();
            return;
        }
        setRemoteButtonsEnabled(false);
        remoteStatus.setText(R.string.guard_remote_working);
        REMOTE_EXECUTOR.execute(() -> {
            GuardSyncManager.Outcome outcome = enroll
                    ? GuardSyncManager.enroll(this) : GuardSyncManager.sync(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setRemoteButtonsEnabled(true);
                showRemoteOutcome(outcome);
                GuardScheduler.schedule(this);
                GuardMonitorService.startIfNeeded(this);
                refreshLocalPolicyFields();
                refreshQuizPackStatus();
            });
        });
    }

    private void pinRemoteKey() {
        if (!AdminSession.isUnlocked()) { finish(); return; }
        try {
            GuardRemoteStore.setServerUrl(this, remoteServer.getText().toString());
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, R.string.guard_remote_invalid_server, Toast.LENGTH_LONG).show();
            return;
        }
        setRemoteButtonsEnabled(false);
        REMOTE_EXECUTOR.execute(() -> {
            GuardSyncManager.Outcome outcome = GuardSyncManager.pinCurrentServerKey(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setRemoteButtonsEnabled(true);
                showRemoteOutcome(outcome);
            });
        });
    }

    private void showRemoteOutcome(GuardSyncManager.Outcome outcome) {
        if (!outcome.pairingCode.isEmpty()) {
            String expiry = java.text.DateFormat.getDateTimeInstance().format(
                    new java.util.Date(outcome.pairingExpiresAt));
            remoteStatus.setText(getString(R.string.guard_remote_pairing_code,
                    outcome.pairingCode, expiry));
            Toast.makeText(this, getString(R.string.guard_remote_pairing_code,
                    outcome.pairingCode, expiry), Toast.LENGTH_LONG).show();
            return;
        } else if (outcome.status == GuardSyncManager.Status.ERROR) {
            remoteStatus.setText(getString(R.string.guard_remote_failed, outcome.message));
        } else {
            remoteStatus.setText(outcome.message);
        }
        Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show();
        refreshRemoteStatus();
    }

    private void refreshRemoteStatus() {
        String url = GuardRemoteStore.serverUrl(this);
        if (url.isEmpty()) {
            remoteStatus.setText(R.string.guard_remote_disabled);
            return;
        }
        String pairing = GuardRemoteStore.isConfirmed(this)
                ? getString(R.string.guard_remote_paired, "parent")
                : GuardRemoteStore.isPaired(this)
                    ? getString(R.string.guard_remote_working)
                    : getString(R.string.guard_remote_not_paired);
        String lastSync = GuardRemoteStore.lastSyncAt(this) == 0L ? "-" :
                java.text.DateFormat.getDateTimeInstance().format(
                        new java.util.Date(GuardRemoteStore.lastSyncAt(this)));
        String summary = getString(R.string.guard_remote_status, pairing,
                GuardRemoteStore.acceptedCloudRevision(this), lastSync);
        String plain = GuardRemoteStore.pinnedKeyId(this);
        if (!plain.isEmpty()) summary += "\n" + getString(R.string.guard_remote_key,
                plain.substring(0, Math.min(16, plain.length())));
        String error = GuardRemoteStore.lastError(this);
        if (!error.isEmpty()) summary += "\n" + getString(R.string.guard_remote_failed, error);
        remoteStatus.setText(summary);
    }

    private void setRemoteButtonsEnabled(boolean enabled) {
        if (remoteEnrollButton != null) remoteEnrollButton.setEnabled(enabled);
        if (remoteSyncButton != null) remoteSyncButton.setEnabled(enabled);
        if (remotePinButton != null) remotePinButton.setEnabled(enabled);
    }

    private void refreshLocalPolicyFields() {
        try {
            GuardPolicy policy = GuardPolicyStore.read(this);
            enabled.setChecked(policy.enabled());
            quiz.setChecked(policy.quizGateEnabled());
            String first = policy.dailyBudgets().isEmpty() ? ""
                    : policy.dailyBudgets().keySet().iterator().next();
            if (!first.isEmpty()) {
                packageName.setText(first);
                minutes.setText(Integer.toString(policy.dailyBudgets().get(first)));
            }
            refreshScheduleStatus(policy);
        } catch (RuntimeException ignored) {
        }
    }

    private LinearLayout addSection(LinearLayout root, int title, int hint) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(18);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setTextColor(getColor(R.color.brand_primary));
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headingParams.topMargin = dp(26);
        root.addView(heading, headingParams);
        TextView description = new TextView(this);
        description.setText(hint);
        description.setTextSize(13);
        description.setTextColor(Color.rgb(70, 78, 92));
        description.setPadding(0, dp(4), 0, dp(10));
        root.addView(description);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(8), dp(16), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.rgb(200, 208, 220));
        card.setBackground(background);
        root.addView(card);
        return card;
    }

    private TextView addNote(LinearLayout root) {
        TextView note = new TextView(this);
        note.setTextSize(13);
        note.setTextColor(Color.rgb(70, 78, 92));
        note.setPadding(0, dp(4), 0, dp(8));
        root.addView(note);
        return note;
    }

    private Switch addSwitch(LinearLayout root, int label, boolean checked) {
        Switch value = new Switch(this);
        value.setText(label);
        value.setChecked(checked);
        value.setPadding(0, dp(8), 0, dp(8));
        root.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return value;
    }

    private EditText addField(LinearLayout root, int hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(true);
        field.setPadding(dp(8), dp(10), dp(8), dp(10));
        root.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private Button button(LinearLayout root, int label) {
        Button value = new Button(this);
        value.setText(label);
        value.setAllCaps(false);
        value.setMinHeight(dp(48));
        root.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applySchedule(boolean clear) {
        if (!AdminSession.isUnlocked()) { finish(); return; }
        final String target = packageName.getText().toString().trim();
        final GuardPolicy.GuardSchedule schedule;
        try {
            GuardPolicy.requirePackage(target);
            schedule = clear ? null
                    : GuardScheduleParser.parse(scheduleWindow.getText().toString(),
                            scheduleDays.getText().toString());
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        PolicyReconciliationCoordinator.runOnPolicyThread(() -> {
            try {
                if (!AdminSession.isUnlocked()) { report(false, "Administrator session expired"); return; }
                if (GuardPolicyEnforcer.protectedPackages(this).contains(target)) {
                    report(false, "System, emergency, and launcher apps cannot be scheduled");
                    return;
                }
                getPackageManager().getApplicationInfo(target, 0);
                GuardPolicy old = GuardPolicyStore.read(this);
                Map<String, java.util.List<GuardPolicy.GuardSchedule>> schedules =
                        new LinkedHashMap<>(old.schedules());
                if (clear) schedules.remove(target);
                else schedules.put(target, java.util.List.of(schedule));
                GuardPolicy next = new GuardPolicy(Math.addExact(old.revision(), 1L), old.timezone(),
                        old.enabled(), old.launcherEnabled(), old.quizGateEnabled(), old.rewardMinutes(),
                        old.maxAttemptsPerDay(), old.cooldownSeconds(), old.dailyBudgets(),
                        old.blockedPackages(), schedules, old.quizTriggers(), old.quizSettings(),
                        old.helpContacts(), old.alwaysAllowedPackages(),
                        old.budgetWarningMinutes(), old.continuousUseReminderMinutes());
                if (!AdminSession.isUnlocked()) { report(false, "Administrator session expired"); return; }
                if (!GuardPolicyStore.save(this, next)) { report(false, "Cannot save schedule"); return; }
                GuardScheduler.schedule(this);
                GuardMonitorService.startIfNeeded(this);
                GuardPolicyEnforcer.Result result = GuardPolicyEnforcer.enforce(this);
                AuditLog.append(this, "guard-schedule-saved:" + next.revision() + ":" + result.verified());
                report(result.verified(), String.join("; ", result.errors()));
                runOnUiThread(() -> refreshScheduleStatus(next));
            } catch (Exception exception) {
                report(false, exception.getClass().getSimpleName());
            }
        });
    }

    private void refreshScheduleStatus(GuardPolicy policy) {
        java.util.List<GuardPolicy.GuardSchedule> windows =
                policy.schedules().get(packageName.getText().toString().trim());
        scheduleStatus.setText((CharSequence) (windows == null
                ? getString(R.string.guard_schedule_none)
                : getString(R.string.guard_schedule_current,
                        GuardScheduleParser.describe(windows.get(0)))));
    }

    private void save(View ignored) {
        if (!AdminSession.isUnlocked()) { finish(); return; }
        final String target = packageName.getText().toString().trim();
        final int budget;
        try {
            GuardPolicy.requirePackage(target);
            budget = Integer.parseInt(minutes.getText().toString().trim());
            if (budget < 0 || budget > 1440) throw new IllegalArgumentException("Invalid budget");
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, R.string.guard_invalid_input, Toast.LENGTH_LONG).show();
            return;
        }
        final boolean limitsEnabled = enabled.isChecked();
        final boolean gateEnabled = quiz.isChecked();
        saveButton.setEnabled(false);
        saveButton.setText(R.string.guard_saving);
        PolicyReconciliationCoordinator.runOnPolicyThread(() -> {
            try {
                if (!AdminSession.isUnlocked()) { report(false, "Administrator session expired"); return; }
                if (GuardPolicyEnforcer.protectedPackages(this).contains(target)) {
                    report(false, "System, emergency, and launcher apps cannot be limited");
                    return;
                }
                getPackageManager().getApplicationInfo(target, 0);
                GuardPolicy old = GuardPolicyStore.read(this);
                Map<String, Integer> budgets = new LinkedHashMap<>(old.dailyBudgets());
                budgets.put(target, budget);
                GuardPolicy next = new GuardPolicy(Math.addExact(old.revision(), 1L), old.timezone(),
                        limitsEnabled, launcher.isChecked(), gateEnabled, old.rewardMinutes(),
                        old.maxAttemptsPerDay(), old.cooldownSeconds(), budgets, old.blockedPackages(),
                        old.schedules(), old.quizTriggers(), old.quizSettings(), old.helpContacts(),
                        old.alwaysAllowedPackages(), old.budgetWarningMinutes(),
                        old.continuousUseReminderMinutes());
                if (!AdminSession.isUnlocked()) { report(false, "Administrator session expired"); return; }
                if (!GuardPolicyStore.save(this, next)) { report(false, "Cannot save policy"); return; }
                GuardScheduler.schedule(this);
                GuardMonitorService.startIfNeeded(this);
                GuardPolicyEnforcer.Result result = GuardPolicyEnforcer.enforce(this);
                AuditLog.append(this, "guard-policy-saved:" + next.revision() + ":" + result.verified());
                report(result.verified(), String.join("; ", result.errors()));
            } catch (Exception exception) {
                report(false, exception.getClass().getSimpleName());
            }
        });
    }

    private void installTrustAnchor(String base64) {
        if (!AdminSession.isUnlocked()) { finish(); return; }
        if (base64.trim().isEmpty()) {
            Toast.makeText(this, R.string.guard_quiz_trust_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        final String value = base64.trim();
        PolicyReconciliationCoordinator.runOnPolicyThread(() -> {
            try {
                String keyId = QuizPackStore.installTrustAnchor(this, value);
                AuditLog.append(this, "guard-quiz-trust:" + keyId);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.guard_quiz_trust_saved, Toast.LENGTH_LONG).show();
                    refreshQuizPackStatus();
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.guard_quiz_trust_invalid, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void resetQuizTrust() {
        if (!AdminSession.isUnlocked()) { finish(); return; }
        PolicyReconciliationCoordinator.runOnPolicyThread(() -> {
            boolean cleared = QuizPackStore.resetTrustAndPacks(this);
            if (cleared) AuditLog.append(this, "guard-quiz-trust-reset");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, cleared ? R.string.guard_quiz_trust_reset
                        : R.string.guard_quiz_trust_reset_failed, Toast.LENGTH_LONG).show();
                refreshQuizPackStatus();
            });
        });
    }

    private void importQuizPack(Uri uri) {
        if (!AdminSession.isUnlocked()) { finish(); return; }
        PolicyReconciliationCoordinator.runOnPolicyThread(() -> {
            String message;
            try {
                String envelope = readLimitedUtf8(uri);
                QuizPackStore.ImportResult result = QuizPackStore.importSigned(this, envelope);
                AuditLog.append(this, "guard-quiz-import:" + result.status()
                        + (result.pack() == null ? "" : ":" + result.pack().version()));
                message = result.message();
            } catch (Exception exception) {
                message = exception.getClass().getSimpleName();
            }
            final String shown = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
                refreshQuizPackStatus();
            });
        });
    }

    private String readLimitedUtf8(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Unable to open quiz pack");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 8_000_000) throw new IllegalArgumentException("Quiz pack is larger than 8 MB");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void rollbackQuizPack() {
        if (!AdminSession.isUnlocked()) { finish(); return; }
        PolicyReconciliationCoordinator.runOnPolicyThread(() -> {
            boolean rolledBack = QuizPackStore.rollback(this);
            if (rolledBack) AuditLog.append(this, "guard-quiz-rollback");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, rolledBack ? R.string.guard_quiz_rolled_back
                        : R.string.guard_quiz_rollback_unavailable, Toast.LENGTH_LONG).show();
                refreshQuizPackStatus();
            });
        });
    }

    private void refreshQuizPackStatus() {
        long version = QuizPackStore.activeVersion(this);
        boolean rollback = QuizPackStore.canRollback(this);
        quizPackStatus.setText(getString(R.string.guard_quiz_status, version));
        rollbackButton.setEnabled(rollback);
    }

    private void report(boolean verified, String detail) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            saveButton.setEnabled(true);
            saveButton.setText(R.string.guard_save);
            String message = getString(verified ? R.string.guard_applied : R.string.guard_degraded);
            if (!detail.isEmpty()) message += ": " + detail;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!AdminSession.isUnlocked()) finish();
    }
}
