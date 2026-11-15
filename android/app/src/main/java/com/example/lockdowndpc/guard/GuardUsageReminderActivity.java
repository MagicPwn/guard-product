package com.example.lockdowndpc.guard;

import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.lockdowndpc.R;

/** Child-facing explanation for a local usage reminder; never requires the admin PIN. */
public final class GuardUsageReminderActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.guard_usage_reminder_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        String reasonSource = getIntent().getStringExtra(
                GuardUsageReminderCoordinator.EXTRA_REASON);
        GuardUsageReminderEngine.Reason reason;
        try {
            reason = GuardUsageReminderEngine.Reason.valueOf(reasonSource);
        } catch (RuntimeException exception) {
            reason = GuardUsageReminderEngine.Reason.CONTINUOUS_USE;
        }
        String packageName = getIntent().getStringExtra(
                GuardUsageReminderCoordinator.EXTRA_PACKAGE);
        String label = getIntent().getStringExtra(GuardUsageReminderCoordinator.EXTRA_LABEL);
        if (label == null || label.trim().isEmpty()) {
            label = packageName == null || packageName.isEmpty()
                    ? getString(R.string.guard_usage_reminder_app_fallback) : packageName;
        }
        int minutes = Math.max(1, getIntent().getIntExtra(
                GuardUsageReminderCoordinator.EXTRA_MINUTES, 1));

        TextView title = new TextView(this);
        title.setTextSize(24);
        title.setText(reason == GuardUsageReminderEngine.Reason.BUDGET_WARNING
                ? R.string.guard_usage_budget_title_detail
                : R.string.guard_usage_continuous_title_detail);
        root.addView(title);

        TextView detail = new TextView(this);
        detail.setTextSize(18);
        detail.setPadding(0, 20, 0, 16);
        detail.setText(reason == GuardUsageReminderEngine.Reason.BUDGET_WARNING
                ? getString(R.string.guard_usage_budget_detail, label, minutes)
                : getString(R.string.guard_usage_continuous_detail, label, minutes));
        root.addView(detail);

        TextView intro = new TextView(this);
        intro.setText(R.string.guard_usage_reminder_intro);
        intro.setPadding(0, 0, 0, 24);
        root.addView(intro);

        Button close = new Button(this);
        close.setText(R.string.guard_usage_reminder_close);
        close.setOnClickListener(view -> finish());
        root.addView(close);
    }
}
